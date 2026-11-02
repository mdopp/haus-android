package cloud.dopp.haus

import cloud.dopp.haus.sicherung.SmsXml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.util.Base64
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The SyncTech XML the box imports (`msgvault import-synctech-sms`, whose
 * parser is Go's strict encoding/xml): attributes escaped so a parser gives
 * back exactly what the phone had, MMS parts with their media in base64,
 * calls in a file of their own. Each file is parsed back here.
 */
class SmsXmlTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun parse(xml: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement

    private fun Element.kinder(name: String): List<Element> {
        val l = getElementsByTagName(name)
        return (0 until l.length).map { l.item(it) as Element }
    }

    @Test
    fun attributesAreEscaped() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;", SmsXml.attribut("a & b <c> \"d\" 'e'"))
        assertEquals("eins&#10;zwei&#13;&#10;drei&#9;vier", SmsXml.attribut("eins\nzwei\r\ndrei\tvier"))
        assertEquals("Grüße 😀", SmsXml.attribut("Grüße 😀"))
        assertEquals("a�b�c�", SmsXml.attribut("a\u0001b\uD800c￿"))
    }

    @Test
    fun smsComeBackAsTheyWere() {
        val body = "Hallo \"du\" & <ihr>,\nZeile zwei\tmit Tab\r\nund 😀 – ünd \u0007"
        val w = StringWriter()
        SmsXml(w, utc).apply {
            nachrichtenAnfang(1, 1_762_000_000_000)
            sms(
                listOf(
                    "protocol" to "0", "address" to "+491701234567", "date" to "1762050000000", "type" to "1",
                    "subject" to null, "body" to body, "read" to "1",
                ),
                1_762_050_000_000, "Anna & Ben",
            )
            nachrichtenEnde()
        }
        val xml = w.toString()
        assertTrue(xml.startsWith("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<smses count=\"1\""))
        val sms = parse(xml).kinder("sms").single()
        assertEquals(body.replace("\u0007", "�"), sms.getAttribute("body"))
        assertEquals("null", sms.getAttribute("subject"))
        assertEquals("+491701234567", sms.getAttribute("address"))
        assertEquals("1762050000000", sms.getAttribute("date"))
        assertEquals("Anna & Ben", sms.getAttribute("contact_name"))
        assertEquals("02.11.2025 02:20:00", sms.getAttribute("readable_date"))
    }

    @Test
    fun anUnknownNumberIsSyncTechsUnknown() {
        val w = StringWriter()
        SmsXml(w, utc).apply {
            nachrichtenAnfang(1, 0)
            sms(listOf("address" to "12345", "body" to "x"), 0, null)
            nachrichtenEnde()
        }
        assertEquals("(Unknown)", parse(w.toString()).kinder("sms").single().getAttribute("contact_name"))
    }

    @Test
    fun anMmsCarriesItsPartsAndAddresses() {
        val bild = ByteArray(50_000) { (it * 7 + it / 300).toByte() } // more than one base64 piece
        val w = StringWriter()
        SmsXml(w, utc).apply {
            nachrichtenAnfang(1, 0)
            mmsAnfang(listOf("date" to "1762050000000", "msg_box" to "1", "m_type" to "132", "read" to "1", "sub" to null), "+4917011~+4917022", 1_762_050_000_000, "Anna, Ben")
            teil(listOf("seq" to "-1", "ct" to "application/smil", "text" to "<smil><body/></smil>"), null)
            teil(listOf("seq" to "0", "ct" to "text/plain", "chset" to "106", "text" to "Schau mal & staune"), null)
            teil(listOf("seq" to "0", "ct" to "image/jpeg", "name" to "bild.jpg", "cl" to "bild.jpg", "text" to null), ByteArrayInputStream(bild))
            teil(listOf("seq" to "0", "ct" to "image/png", "cl" to "leer.png"), ByteArrayInputStream(ByteArray(0)))
            adressen(
                listOf(
                    listOf("address" to "+4917011", "type" to "137", "charset" to "106"),
                    listOf("address" to "+4917022", "type" to "151", "charset" to "106"),
                ),
            )
            mmsEnde()
            nachrichtenEnde()
        }
        val wurzel = parse(w.toString())
        assertEquals("smses", wurzel.tagName)
        val mms = wurzel.kinder("mms").single()
        assertEquals("+4917011~+4917022", mms.getAttribute("address"))
        assertEquals("1", mms.getAttribute("msg_box"))
        assertEquals("null", mms.getAttribute("sub"))
        assertEquals("Anna, Ben", mms.getAttribute("contact_name"))
        val teile = mms.kinder("part")
        assertEquals(4, teile.size)
        assertEquals("<smil><body/></smil>", teile[0].getAttribute("text"))
        assertEquals("Schau mal & staune", teile[1].getAttribute("text"))
        assertEquals("image/jpeg", teile[2].getAttribute("ct"))
        assertArrayEquals(bild, Base64.getDecoder().decode(teile[2].getAttribute("data")))
        assertEquals("", teile[3].getAttribute("data"))
        val adressen = mms.kinder("addr")
        assertEquals(listOf("137", "151"), adressen.map { it.getAttribute("type") })
        assertEquals("parts before addrs", listOf("parts", "addrs"), (0 until mms.childNodes.length).map { mms.childNodes.item(it) }.filterIsInstance<Element>().map { it.tagName })
    }

    @Test
    fun callsAreTheirOwnFile() {
        val w = StringWriter()
        SmsXml(w, utc).apply {
            anrufeAnfang(2, 0)
            anruf(listOf("number" to "+491701234567", "duration" to "62", "date" to "1762050000000", "type" to "1", "presentation" to "1"), 1_762_050_000_000, "Oma")
            anruf(listOf("number" to "", "duration" to "0", "date" to "1762050100000", "type" to "3", "presentation" to "2"), 1_762_050_100_000, null)
            anrufeEnde()
        }
        val wurzel = parse(w.toString())
        assertEquals("calls", wurzel.tagName)
        assertEquals("2", wurzel.getAttribute("count"))
        val anrufe = wurzel.kinder("call")
        assertEquals(listOf("Oma", "(Unknown)"), anrufe.map { it.getAttribute("contact_name") })
        assertEquals("62", anrufe[0].getAttribute("duration"))
        assertEquals("", anrufe[1].getAttribute("number"))
    }
}
