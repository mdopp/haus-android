#!/usr/bin/env python3
"""A fake Haus for the live-widget e2e (e2e/live_e2e.sh).

Serves over TLS what the app's live service and its widgets ask for, and logs
what the e2e asserts on, one timestamped line each:

  OPEN / CLOSE      an SSE stream on /napi/portal/events opened or went away
  WATCH <body>      a POST /napi/portal/watch
  GET <path>        every other authed GET
  FRAME <id> -> n   a card_state pushed through /ctl/frame to n open streams
  ACTIVE_SET <ids> -> n
                    an active_set pushed through /ctl/active_set to n open streams;
                    /napi/portal/active answers those ids from then on, as Haus would

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
from urllib.parse import parse_qs, urlparse

TOKEN = "haus_device_E2E"
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
    "/napi/whoami": {"uid": "e2e", "via": "haus"},
    "/napi/persona": {"name": "haus", "display": "Haus", "greeting": "", "accent": "#3b82f6", "ground": "", "icon": ""},
    "/napi/portal/active": {"ok": True, "active": []},
    "/napi/portal/energy": {"ok": True, "energy": {"flow": [], "totals": [], "circuits": []}},
    "/napi/portal/start/addable": {"ok": True, "rooms": [], "automations": []},
    "/napi/defs/tool": {"ok": True, "kind": "tool", "defs": []},
    "/napi/box/home": {"servicesUp": 1, "servicesFailed": 0, "servicesDown": 0, "pendingApprovals": 0, "pendingUpdates": 0},
    "/napi/box/services": {"services": []},
}


class Haus(BaseHTTPRequestHandler):
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
        if url.path in REPLIES:
            return self.reply(200, REPLIES[url.path])
        return self.reply(404, {"ok": False, "error": "no_such_path"})

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
    p.add_argument("--log", default="haus.log")
    a = p.parse_args()
    log_file = open(a.log, "a")
    server = ThreadingHTTPServer(("0.0.0.0", a.port), Haus)
    server.daemon_threads = True
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(a.cert, a.key)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    log("LISTEN %d" % a.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
