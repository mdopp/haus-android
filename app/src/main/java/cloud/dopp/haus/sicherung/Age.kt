package cloud.dopp.haus.sicherung

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.util.encoders.Base64
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * age v1 (https://age-encryption.org/v1) to one X25519 recipient, encrypt
 * only: the SMS and the call log are encrypted on the phone for the person's
 * archive Briefkasten (laib ADR 0029) before a byte leaves it. Streaming: the
 * payload goes in 64 KiB chunks, never the whole file in memory.
 *
 * The primitives are BouncyCastle's lightweight classes (X25519, HKDF-SHA256,
 * HMAC-SHA256, ChaCha20-Poly1305), the same provider kage builds on; the app
 * runs from API 23, where Android's own providers have neither X25519 nor
 * ChaCha20-Poly1305. kage itself needs a newer Kotlin than this build has.
 *
 * Proven in `AgeTest`: the bytes for fixed randomness equal those the real
 * `age` CLI decrypts, and a decrypter that passes the C2SP age testkit reads
 * what this writes.
 */
object Age {

    /** The payload's chunk: 64 KiB of plaintext, 16 bytes of tag each. */
    const val STUECK = 64 * 1024

    private const val VERSION = "age-encryption.org/v1"
    private const val X25519_INFO = "age-encryption.org/v1/X25519"
    private const val TAG = 16

    /** An `age1…` recipient as its 32 raw bytes; [IllegalArgumentException] when it is none. */
    fun empfaenger(text: String): ByteArray {
        val (hrp, daten) = Bech32.decode(text.trim())
        require(hrp == "age") { "not an age recipient" }
        require(daten.size == 32) { "an X25519 recipient has 32 bytes" }
        return daten
    }

    /** [ein] encrypted to [empfaenger] (32 bytes, see [empfaenger]) into [aus]. Neither stream is closed. */
    fun verschluesseln(empfaenger: ByteArray, ein: InputStream, aus: OutputStream, zufall: SecureRandom = SecureRandom()) {
        val dateiSchluessel = ByteArray(16).also(zufall::nextBytes)
        val ephemer = ByteArray(32).also(zufall::nextBytes)
        val nonce = ByteArray(16).also(zufall::nextBytes)
        verschluesseln(empfaenger, ein, aus, dateiSchluessel, ephemer, nonce)
    }

    /** The same with the randomness given: the tests hold the bytes to a fixture. */
    internal fun verschluesseln(
        empfaenger: ByteArray,
        ein: InputStream,
        aus: OutputStream,
        dateiSchluessel: ByteArray,
        ephemer: ByteArray,
        nonce: ByteArray,
    ) {
        require(empfaenger.size == 32 && dateiSchluessel.size == 16 && ephemer.size == 32 && nonce.size == 16)
        aus.write(kopf(empfaenger, dateiSchluessel, ephemer))
        nutzlast(dateiSchluessel, nonce, ein, aus)
    }

    /** The header: the version line, one X25519 stanza, its MAC. */
    private fun kopf(empfaenger: ByteArray, dateiSchluessel: ByteArray, ephemer: ByteArray): ByteArray {
        val anteil = ByteArray(32)
        X25519.scalarMultBase(ephemer, 0, anteil, 0)
        val geteilt = ByteArray(32)
        if (!X25519.calculateAgreement(ephemer, 0, empfaenger, 0, geteilt, 0)) {
            throw IOException("the recipient is a low-order point")
        }
        val umschlag = hkdf(geteilt, anteil + empfaenger, X25519_INFO)
        val body = aead(umschlag, ByteArray(12), dateiSchluessel, dateiSchluessel.size)
        val ohneMac = "$VERSION\n-> X25519 ${b64(anteil)}\n${b64(body)}\n---"
        val mac = HMac(SHA256Digest()).run {
            init(KeyParameter(hkdf(dateiSchluessel, null, "header")))
            val b = ohneMac.toByteArray(Charsets.US_ASCII)
            update(b, 0, b.size)
            ByteArray(macSize).also { doFinal(it, 0) }
        }
        return "$ohneMac ${b64(mac)}\n".toByteArray(Charsets.US_ASCII)
    }

    /**
     * The payload: the nonce, then STREAM over ChaCha20-Poly1305 — an 11-byte
     * big-endian counter and a last-chunk flag as the nonce of each chunk. A
     * plaintext of a multiple of [STUECK] ends with a full chunk flagged last;
     * only an empty plaintext has an empty chunk.
     */
    internal fun nutzlast(dateiSchluessel: ByteArray, nonce: ByteArray, ein: InputStream, aus: OutputStream) {
        aus.write(nonce)
        val schluessel = hkdf(dateiSchluessel, nonce, "payload")
        val aead = ChaCha20Poly1305()
        var jetzt = ByteArray(STUECK)
        var naechstes = ByteArray(STUECK)
        val raus = ByteArray(STUECK + TAG)
        var n = lies(ein, jetzt)
        var zaehler = 0L
        while (true) {
            var m = 0
            val letztes = if (n < STUECK) true else lies(ein, naechstes).also { m = it } == 0
            aead.init(true, AEADParameters(KeyParameter(schluessel), TAG * 8, stromNonce(zaehler, letztes)))
            var k = aead.processBytes(jetzt, 0, n, raus, 0)
            k += aead.doFinal(raus, k)
            aus.write(raus, 0, k)
            if (letztes) return
            zaehler++
            val t = jetzt
            jetzt = naechstes
            naechstes = t
            n = m
        }
    }

    private fun stromNonce(zaehler: Long, letztes: Boolean): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[10 - i] = (zaehler ushr (8 * i)).toByte()
        if (letztes) n[11] = 1
        return n
    }

    private fun aead(schluessel: ByteArray, nonce: ByteArray, daten: ByteArray, laenge: Int): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(schluessel), TAG * 8, nonce))
        val out = ByteArray(c.getOutputSize(laenge))
        val k = c.processBytes(daten, 0, laenge, out, 0)
        c.doFinal(out, k)
        return out
    }

    /** HKDF-SHA256 to 32 bytes; a null salt is the empty one (HMAC pads either to zeros). */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray?, info: String): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
        return ByteArray(32).also { g.generateBytes(it, 0, it.size) }
    }

    /** Base64 without padding, as age writes it. */
    internal fun b64(b: ByteArray): String = Base64.toBase64String(b).trimEnd('=')

    /** Fill [puffer] from [ein]; fewer bytes only at its end. */
    private fun lies(ein: InputStream, puffer: ByteArray): Int {
        var n = 0
        while (n < puffer.size) {
            val r = ein.read(puffer, n, puffer.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}

/** Bech32 (BIP 173, not bech32m), as age spells its keys: decode only. */
internal object Bech32 {
    private const val ZEICHEN = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** The human-readable part (lower case) and the data bytes; [IllegalArgumentException] on any flaw. */
    fun decode(text: String): Pair<String, ByteArray> {
        require(text == text.lowercase() || text == text.uppercase()) { "mixed case" }
        val s = text.lowercase()
        val trenner = s.lastIndexOf('1')
        require(trenner >= 1 && s.length - trenner - 1 >= 6) { "no separator" }
        val hrp = s.substring(0, trenner)
        require(hrp.all { it.code in 33..126 }) { "bad prefix" }
        val werte = s.substring(trenner + 1).map { c ->
            ZEICHEN.indexOf(c).also { require(it >= 0) { "bad character" } }
        }
        require(polymod(hrpErweitert(hrp) + werte) == 1) { "bad checksum" }
        return hrp to bits5zu8(werte.dropLast(6))
    }

    private fun polymod(werte: List<Int>): Int {
        var chk = 1
        for (v in werte) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpErweitert(hrp: String): List<Int> =
        hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }

    private fun bits5zu8(werte: List<Int>): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (v in werte) {
            acc = ((acc shl 5) or v) and 0x1fff // at most 12 bits are pending
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((acc ushr bits) and 0xff).toByte())
            }
        }
        require(bits < 5 && (acc and ((1 shl bits) - 1)) == 0) { "bad padding" }
        return out.toByteArray()
    }
}
