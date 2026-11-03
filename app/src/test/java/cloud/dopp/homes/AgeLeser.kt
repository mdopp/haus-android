package cloud.dopp.homes

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.util.encoders.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * An age v1 decrypter for an X25519 identity, for the tests only: what the
 * box's archive does with a file the phone sent. Strict where the C2SP age
 * testkit is (canonical base64, the share's and the body's length, the
 * all-zero secret, the header MAC, STREAM's last chunk), so passing the
 * testkit makes it a fair judge of what [cloud.dopp.homes.sicherung.Age] writes.
 */
object AgeLeser {

    /** The header is broken, or no stanza is for this identity ("header failure", "no match"). */
    class Kopffehler(m: String) : Exception(m)

    /** The header was fine, the payload is not ("payload failure"). */
    class Nutzlastfehler(m: String) : Exception(m)

    private const val STUECK = 64 * 1024

    /** `AGE-SECRET-KEY-1…` as its 32-byte scalar. */
    fun identitaet(text: String): ByteArray {
        val (hrp, daten) = bech32(text)
        require(hrp == "age-secret-key-" && daten.size == 32)
        return daten
    }

    /** The recipient (raw 32 bytes) of [identitaet]. */
    fun empfaenger(identitaet: ByteArray): ByteArray = ByteArray(32).also { X25519.scalarMultBase(identitaet, 0, it, 0) }

    fun entschluesseln(identitaet: ByteArray, ein: InputStream): ByteArray {
        val kopf = ByteArrayOutputStream()
        fun zeile(): String {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = ein.read()
                if (c < 0) throw Kopffehler("header ends early")
                kopf.write(c)
                if (c == '\n'.code) break
                b.write(c)
            }
            return String(b.toByteArray(), Charsets.ISO_8859_1)
        }
        if (zeile() != "age-encryption.org/v1") throw Kopffehler("version")
        val ich = empfaenger(identitaet)
        var dateiSchluessel: ByteArray? = null
        var z = zeile()
        val macZeile: String
        while (true) {
            if (z.startsWith("--- ")) {
                macZeile = z
                break
            }
            if (!z.startsWith("-> ")) throw Kopffehler("not a stanza: $z")
            val args = z.substring(3).split(' ')
            if (args.any { it.isEmpty() || it.any { c -> c.code !in 33..126 } }) throw Kopffehler("bad argument")
            val body = ByteArrayOutputStream()
            while (true) {
                val l = zeile()
                if (l.length > 64) throw Kopffehler("long body line")
                body.write(b64(l))
                if (l.length < 64) break
            }
            if (args[0] == "X25519") {
                if (args.size != 2) throw Kopffehler("X25519 takes one argument")
                val anteil = b64(args[1])
                if (anteil.size != 32) throw Kopffehler("share length")
                val b = body.toByteArray()
                if (b.size != 32) throw Kopffehler("body length")
                val geteilt = ByteArray(32)
                if (!X25519.calculateAgreement(identitaet, 0, anteil, 0, geteilt, 0)) throw Kopffehler("all-zero secret")
                if (dateiSchluessel == null) {
                    val k = hkdf(geteilt, anteil + ich, "age-encryption.org/v1/X25519")
                    dateiSchluessel = runCatching { aead(false, k, ByteArray(12), b) }.getOrNull()
                }
            }
            z = zeile()
        }
        val fk = dateiSchluessel ?: throw Kopffehler("no match")
        val roh = kopf.toByteArray()
        val ohneMac = roh.copyOf(roh.size - macZeile.length - 1 + 3)
        val mac = b64(macZeile.substring(4))
        val soll = HMac(SHA256Digest()).run {
            init(KeyParameter(hkdf(fk, null, "header")))
            update(ohneMac, 0, ohneMac.size)
            ByteArray(32).also { doFinal(it, 0) }
        }
        if (!mac.contentEquals(soll)) throw Kopffehler("header MAC")
        val nonce = ByteArray(16)
        if (lies(ein, nonce) != 16) throw Kopffehler("no nonce")
        val schluessel = hkdf(fk, nonce, "payload")
        val out = ByteArrayOutputStream()
        val stueck = ByteArray(STUECK + 16)
        var n = lies(ein, stueck)
        var zaehler = 0L
        while (true) {
            if (n < 16) throw Nutzlastfehler("short chunk")
            val mehr = if (n == stueck.size) ein.read() else -1
            val letztes = mehr < 0
            val nonceZ = ByteArray(12)
            for (i in 0 until 8) nonceZ[10 - i] = (zaehler ushr (8 * i)).toByte()
            if (letztes) nonceZ[11] = 1
            val klar = runCatching { aead(false, schluessel, nonceZ, stueck.copyOf(n)) }.getOrNull()
                ?: throw Nutzlastfehler("chunk $zaehler")
            if (letztes && klar.isEmpty() && zaehler > 0) throw Nutzlastfehler("empty last chunk")
            out.write(klar)
            if (letztes) return out.toByteArray()
            stueck[0] = mehr.toByte()
            n = 1 + lies(ein, stueck, 1)
            zaehler++
        }
    }

    private fun lies(ein: InputStream, b: ByteArray, ab: Int = 0): Int {
        var n = ab
        while (n < b.size) {
            val r = ein.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n - ab
    }

    /** Canonical unpadded base64 only. */
    private fun b64(s: String): ByteArray {
        if (s.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '+' && it != '/' }) throw Kopffehler("base64")
        if (s.length % 4 == 1) throw Kopffehler("base64 length")
        val d = Base64.decode(s + "=".repeat((4 - s.length % 4) % 4))
        if (Base64.toBase64String(d).trimEnd('=') != s) throw Kopffehler("non-canonical base64")
        return d
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray?, info: String): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info.toByteArray()))
        return ByteArray(32).also { g.generateBytes(it, 0, 32) }
    }

    private fun aead(verschl: Boolean, k: ByteArray, nonce: ByteArray, daten: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(verschl, AEADParameters(KeyParameter(k), 128, nonce))
        val out = ByteArray(c.getOutputSize(daten.size))
        val n = c.processBytes(daten, 0, daten.size, out, 0)
        val m = c.doFinal(out, n)
        return out.copyOf(n + m)
    }

    private fun bech32(text: String): Pair<String, ByteArray> {
        val zeichen = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val s = text.lowercase()
        val t = s.lastIndexOf('1')
        val werte = s.substring(t + 1).dropLast(6).map { zeichen.indexOf(it) }
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (v in werte) {
            acc = ((acc shl 5) or v) and 0x1fff
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((acc ushr bits) and 0xff).toByte())
            }
        }
        return s.substring(0, t) to out.toByteArray()
    }
}
