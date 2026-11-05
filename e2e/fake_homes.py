#!/usr/bin/env python3
"""A fake Homes for the live-widget e2e (e2e/live_e2e.sh).

Serves over TLS what the app's live service and its widgets ask for, and logs
what the e2e asserts on, one timestamped line each:

  OPEN / CLOSE      an SSE stream on /napi/portal/events opened or went away
  WATCH <body>      a POST /napi/portal/watch
  UPLOAD ok|bad     a POST /napi/upload, whether it is Homes' multipart shape
  GEMEINSAM <part> <code>
                    a GET /napi/gemeinsam/kontakte|kalender (the Homes account, #206):
                    200 with the household's cards or calendars, 304 when the
                    phone named the ETag it has
  SCHREIBEN <method> <path> <code>
                    a PUT or DELETE of one entry of the Homes account (owner 2026-11-05,
                    homes#278): 201/200 with ETag "2" and the entry in the feed from then
                    on, 204 and out of the feed; „Müll raus“ (e2e-muell) changed on the
                    phone is 412, as if it had changed in the house meanwhile
  GEMEINSAM_FRAME <part> -> n
                    /ctl/gemeinsam?teil=kalender: the shared calendar gets a second
                    entry („Elternabend“) and a gemeinsam frame goes to n open streams
  GET <path>        every other authed GET
  FRAME <id> -> n   a card_state pushed through /ctl/frame to n open streams
  ACTIVE_SET <ids> -> n
                    an active_set pushed through /ctl/active_set to n open streams;
                    /napi/portal/active answers those ids from then on, as Homes would

The stream writes ": ping" every second and watches its socket, so a stream
the phone dropped shows as CLOSE within about 0.1 s. /ctl/* is the e2e's own control, unauthed, local.
Standard library only.
"""
import argparse
import json
import select
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

TOKEN = "homes_device_E2E"
lock = threading.Lock()
streams = []  # (frames, wake) per open stream
log_file = None


def log(event):
    line = "%.3f %s" % (time.time(), event)
    with lock:
        log_file.write(line + "\n")
        log_file.flush()
    print(line, flush=True)


def card(entity_id, state):
    return {"entity_id": entity_id, "name": "E2E", "domain": entity_id.split(".")[0], "state": state}


REPLIES = {
    "/napi/whoami": {"uid": "e2e", "via": "homes"},
    "/napi/assistent": {"name": "Homes"},
    "/napi/portal/active": {"ok": True, "active": []},
    "/napi/portal/favorites": {"ok": True, "favorites": []},
    "/napi/portal/energy": {"ok": True, "energy": {
        "flow": [{"entity_id": "sensor.e2e_power", "name": "E2E", "state": "812", "unit": "W", "label": "PV", "sense": "supply"}],
        "totals": [{"entity_id": "sensor.e2e_total", "name": "E2E", "state": "5012.4", "unit": "kWh", "label": "PV-Erzeugung", "today": 12.4}],
        "circuits": []}},
    "/napi/portal/start/addable": {"ok": True, "rooms": [], "automations": []},
    "/napi/defs/tool": {"ok": True, "kind": "tool", "defs": []},
    "/napi/box/home": {"servicesUp": 1, "servicesFailed": 0, "servicesDown": 0, "pendingApprovals": 0, "pendingUpdates": 0},
    "/napi/box/services": {"services": []},
}


# The Homes account (#206): what Homes' /napi/gemeinsam serves (homes#218), in its shapes.
# Oma Erna is also the person's own contact on the phone (LiveE2EKonto), with the same mobile number.
GEMEINSAM = {
    "kontakte": ("text/vcard; charset=utf-8",
                 "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:urn:uuid:e2e-oma\r\nFN:Oma Erna\r\nN:Müller;Erna;;;\r\n"
                 "TEL;TYPE=CELL:+49 170 1111111\r\nTEL;TYPE=HOME:+49 89 2222222\r\nEMAIL;TYPE=HOME:erna@homes.example\r\n"
                 "X-HOMES-ETAG:\"1\"\r\nEND:VCARD\r\n"
                 "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:urn:uuid:e2e-klempner\r\nFN:Klempner Huber\r\nTEL;TYPE=WORK:+49 89 3333333\r\n"
                 "ORG:Huber Sanitär\r\nX-HOMES-ETAG:\"1\"\r\nEND:VCARD\r\n"),
    "kalender": ("text/calendar; charset=utf-8",
                 "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Haushalt\r\nX-HAUS-KALENDER:haushalt\r\nX-HAUS-ART:haushalt\r\n"
                 "X-HOMES-SCHREIBBAR:TRUE\r\n"
                 "BEGIN:VEVENT\r\nUID:e2e-muell\r\nDTSTART:20300105T060000Z\r\nDTEND:20300105T063000Z\r\nX-HOMES-ETAG:\"1\"\r\n"
                 "SUMMARY:Müll raus\r\nEND:VEVENT\r\n"
                 "END:VCALENDAR\r\n"
                 "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Erinnerungen\r\nX-HAUS-KALENDER:erinnerungen\r\nX-HAUS-ART:erinnerungen\r\n"
                 "BEGIN:VEVENT\r\nUID:e2e-arzt\r\nDTSTART:20300106T080000Z\r\nDTEND:20300106T080000Z\r\nSUMMARY:Arzt anrufen\r\n"
                 "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:PT0S\r\nEND:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"),
}


def ohne(text, kind, uid):
    """text without the BEGIN:<kind> … END:<kind> blocks of that UID."""
    begin, end, out = "BEGIN:%s\r\n" % kind, "END:%s\r\n" % kind, ""
    while begin in text:
        i = text.index(begin)
        j = text.index(end, i) + len(end)
        block = text[i:j]
        out += text[:i] + ("" if ("\r\nUID:%s\r\n" % uid) in block else block)
        text = text[j:]
    return out + text


class Homes(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authed(self):
        return self.headers.get("Authorization") == "Bearer " + TOKEN

    def do_GET(self):
        url = urlparse(self.path)
        if url.path.startswith("/ctl/"):
            return self.control(url)
        log("GET " + url.path)
        if not self.authed():
            return self.reply(401, {"error": "unauthorized"})
        if url.path == "/napi/portal/events":
            return self.events()
        if url.path == "/napi/portal/state":
            entity = parse_qs(url.query).get("entity_id", ["light.e2e"])[0]
            return self.reply(200, {"ok": True, "card": card(entity, "on")})
        if url.path.startswith("/napi/gemeinsam/") and url.path.rsplit("/", 1)[1] in GEMEINSAM:
            return self.gemeinsam(url.path.rsplit("/", 1)[1])
        if url.path in REPLIES:
            return self.reply(200, REPLIES[url.path])
        return self.reply(404, {"ok": False, "error": "no_such_path"})

    def gemeinsam(self, part):
        ctype, text = GEMEINSAM[part]
        body = text.encode()
        etag = '"%s-%d"' % (part, len(body))
        if self.headers.get("If-None-Match") == etag:
            log("GEMEINSAM %s 304" % part)
            self.send_response(304)
            self.send_header("ETag", etag)
            self.end_headers()
            return
        log("GEMEINSAM %s 200" % part)
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("ETag", etag)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_PUT(self):
        self.schreiben()

    def do_DELETE(self):
        self.schreiben()

    def schreiben(self):
        """One entry of the Homes account written back (homes#278), kept in the feed as Homes would."""
        url = urlparse(self.path)
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
        teile = url.path.split("/")  # '', napi, gemeinsam, kalender|kontakte, [kalender,] uid
        if not self.authed() or len(teile) < 5 or teile[3] not in GEMEINSAM:
            return self.reply(404, {"fehler": "nicht hier"})
        part, uid = teile[3], unquote(teile[-1])
        kind = "VEVENT" if part == "kalender" else "VCARD"
        code = 204 if self.command == "DELETE" else (201 if self.headers.get("If-None-Match") == "*" else 200)
        if uid == "e2e-muell" and self.command == "PUT":
            code = 412
        with lock:
            ctype, text = GEMEINSAM[part]
            if code in (200, 201, 204):
                text = ohne(text, kind, uid)
            if code in (200, 201):
                block = body[body.index("BEGIN:" + kind):body.index("END:%s\r\n" % kind)] + "X-HOMES-ETAG:\"2\"\r\nEND:%s\r\n" % kind
                text = text.replace("END:VCALENDAR\r\n", block + "END:VCALENDAR\r\n", 1) if kind == "VEVENT" else text + block
            GEMEINSAM[part] = (ctype, text)
        log("SCHREIBEN %s %s %d" % (self.command, url.path, code))
        if code == 204:
            self.send_response(204)
            self.end_headers()
            return
        if code == 412:
            return self.reply(412, {"fehler": "anders im Haus"})
        self.send_response(code)
        self.send_header("ETag", '"2"')
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        url = urlparse(self.path)
        if url.path.startswith("/ctl/"):
            return self.control(url)
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode(errors="replace")
        if not self.authed():
            log("POST " + url.path + " 401")
            return self.reply(401, {"error": "unauthorized"})
        if url.path == "/napi/portal/watch":
            log("WATCH " + body)
            return self.reply(200, {"ok": True})
        if url.path == "/napi/upload":
            # Homes' upload.go reads a "filename" part, then a "file" part
            good = 'name="filename"' in body and 'name="file"; filename="Foto-' in body and "PNG" in body
            log("UPLOAD " + ("ok" if good else "bad"))
            return self.reply(200, {"ok": True, "id": "Foto.png"})
        log("POST " + url.path)
        return self.reply(200, {"ok": True})

    def events(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        frames, wake = [], threading.Event()
        mine = (frames, wake)
        with lock:
            streams.append(mine)
        log("OPEN")
        try:
            self.wfile.write(b": hello\n\n")
            self.wfile.flush()
            next_ping = time.time() + 1.0
            while not self.gone():
                wake.wait(0.1)
                wake.clear()
                with lock:
                    out = frames[:]
                    frames.clear()
                for f in out:
                    self.wfile.write(f)
                if time.time() >= next_ping:
                    self.wfile.write(b": ping\n\n")
                    next_ping = time.time() + 1.0
                self.wfile.flush()
        except (OSError, ssl.SSLError):
            pass
        finally:
            with lock:
                streams.remove(mine)
            log("CLOSE")

    def gone(self):
        """The phone closed the stream: its end is readable and reads as nothing."""
        conn = self.connection
        if not select.select([conn], [], [], 0)[0]:
            return False
        try:
            return conn.recv(1) == b""
        except ssl.SSLWantReadError:
            return False
        except (OSError, ssl.SSLError):
            return True

    def push(self, event, data):
        """Hand one SSE frame to every open stream; how many took it."""
        frame = ("event: %s\ndata: %s\n\n" % (event, json.dumps(data))).encode()
        with lock:
            for frames, wake in streams:
                frames.append(frame)
                wake.set()
            return len(streams)

    def control(self, url):
        q = parse_qs(url.query)
        if url.path == "/ctl/frame":
            entity = q.get("entity_id", ["light.e2e"])[0]
            n = self.push("card_state", {"entity_id": entity, "card": card(entity, q.get("state", ["on"])[0])})
            log("FRAME %s -> %d" % (entity, n))
            return self.reply(200, {"streams": n})
        if url.path == "/ctl/active_set":
            ids = sorted(i for i in q.get("ids", [""])[0].split(",") if i)
            with lock:
                REPLIES["/napi/portal/active"] = {"ok": True, "active": [card(i, "on") for i in ids]}
            n = self.push("active_set", {"entity_ids": ids})
            log("ACTIVE_SET %s -> %d" % (",".join(ids), n))
            return self.reply(200, {"streams": n})
        if url.path == "/ctl/gemeinsam":
            teil = q.get("teil", ["kalender"])[0]
            with lock:
                ctype, text = GEMEINSAM[teil]
                if teil == "kalender" and "Elternabend" not in text:
                    text = text.replace("SUMMARY:Müll raus\r\nEND:VEVENT\r\n",
                                        "SUMMARY:Müll raus\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:e2e-eltern\r\n"
                                        "DTSTART:20300107T170000Z\r\nDTEND:20300107T183000Z\r\nSUMMARY:Elternabend\r\nEND:VEVENT\r\n", 1)
                    GEMEINSAM[teil] = (ctype, text)
            n = self.push("gemeinsam", {"teil": teil})
            log("GEMEINSAM_FRAME %s -> %d" % (teil, n))
            return self.reply(200, {"streams": n})
        if url.path == "/ctl/streams":
            with lock:
                return self.reply(200, {"streams": len(streams)})
        return self.reply(404, {})


def main():
    global log_file
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8443)
    p.add_argument("--cert", required=True)
    p.add_argument("--key", required=True)
    p.add_argument("--log", default="homes.log")
    a = p.parse_args()
    log_file = open(a.log, "a")
    server = ThreadingHTTPServer(("0.0.0.0", a.port), Homes)
    server.daemon_threads = True
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(a.cert, a.key)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    log("LISTEN %d" % a.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
