package cloud.dopp.haus

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ApiClient.Upload
import cloud.dopp.haus.data.Napi
import cloud.dopp.haus.ui.PhotoShare
import cloud.dopp.haus.ui.ShareActivity
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.Date

/**
 * A photo shared to Haus (owner, 2026-11-02: „Foto machen und mit der Haus
 * App teilen“): the share sheet offers Haus for images, one or several; each
 * goes to `POST /napi/upload` as Haus reads it (multipart, the name, then the
 * file); what Haus answers is said in German. Text keeps its own way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoShareTest {

    private val manifest by lazy { File(RepoFiles.main, "AndroidManifest.xml").readText() }

    @Test
    fun theShareSheetOffersHausForImagesOneOrSeveral() {
        val activity = manifest.substringAfter("""android:name=".ui.ShareActivity"""").substringBefore("</activity>")
        val images = activity.split("<intent-filter>").single { it.contains("image/*") }
        assertTrue(images, images.contains("android.intent.action.SEND\""))
        assertTrue(images, images.contains("android.intent.action.SEND_MULTIPLE"))
        assertTrue("text stays", activity.contains("""android:mimeType="text/plain""""))
        val target = File(RepoFiles.main, "res/xml/shortcuts.xml").readText()
        assertTrue("the assistant's Direct Share takes images too", target.contains("""android:mimeType="image/*""""))
    }

    @Test
    fun anImageShareIsAPhotoATextShareIsNot() {
        val a = Uri.parse("content://media/external/images/1")
        val b = Uri.parse("content://media/external/images/2")
        val one = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, a)
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b, a))
        assertTrue(PhotoShare.isImageShare(one))
        assertTrue(PhotoShare.isImageShare(many))
        assertEquals(listOf(a), PhotoShare.uris(one))
        assertEquals("each once", listOf(a, b), PhotoShare.uris(many))
        assertFalse(PhotoShare.isImageShare(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://x.de")))
        assertFalse(PhotoShare.isImageShare(Intent(Intent.ACTION_VIEW).setType("image/jpeg")))
    }

    /** What Haus takes goes as it is; HEIC, an unknown type or more than 25 MB becomes a JPEG first. */
    @Test
    fun onlyWhatHausTakesGoesAsItIs() {
        for (m in listOf("image/jpeg", "image/png", "image/webp", "image/gif")) assertFalse(m, PhotoShare.needsJpeg(m, 3_000_000))
        for (m in listOf("image/heic", "image/heif", "image/svg+xml", null)) assertTrue("$m", PhotoShare.needsJpeg(m, 3_000_000))
        assertTrue("over 25 MB", PhotoShare.needsJpeg("image/jpeg", (25L shl 20) + 1))
        val at = Date(1_793_000_000_000)
        assertTrue(Regex("""Foto-\d{8}-\d{6}\.jpg""").matches(PhotoShare.name(at, 0, 1, "jpg")))
        assertTrue(Regex("""Foto-\d{8}-\d{6}-2\.png""").matches(PhotoShare.name(at, 1, 3, "png")))
    }

    /** The request as Haus's upload.go reads it: a "filename" part, then a "file" part with its type. */
    @Test
    fun theUploadIsTheNameThenTheFile() {
        assertEquals(listOf("filename", "file"), Napi.UPLOAD.body)
        val body = ApiClient.uploadBody(byteArrayOf(1, 2, 3), "Foto-1.jpg", "image/jpeg")
        assertEquals("multipart/form-data", body.contentType()?.let { "${it.type}/${it.subtype}" })
        assertEquals(2, body.size)
        assertTrue(body.part(0).headers!!["Content-Disposition"]!!.contains("""name="filename""""))
        assertTrue(body.part(1).headers!!["Content-Disposition"]!!.contains("""name="file"; filename="Foto-1.jpg""""))
        assertEquals("image/jpeg", body.part(1).body.contentType().toString())
        val sent = Buffer().also { body.part(1).body.writeTo(it) }.readByteArray()
        assertTrue(sent.contentEquals(byteArrayOf(1, 2, 3)))
    }

    /** Haus answers codes; the person reads a sentence. */
    @Test
    fun whatHausDidIsSaid() {
        assertEquals(Upload.Stored("Foto-1.jpg"), ApiClient.uploadOutcome(200, """{"ok":true,"id":"Foto-1.jpg"}"""))
        assertEquals(Upload.NotPaired, ApiClient.uploadOutcome(401, """{"error":"unauthorized"}"""))
        for (code in listOf(403, 404, 408, 413, 415, 429, 503, 507, 500)) {
            val r = ApiClient.uploadOutcome(code, """{"ok":false,"error":"busy"}""")
            assertTrue("$code: $r", r is Upload.Refused)
            assertFalse("$code says Haus's code word", (r as Upload.Refused).sentence.contains("busy"))
        }
        assertTrue((ApiClient.uploadOutcome(507, null) as Upload.Refused).sentence.contains("kein Platz"))
        assertTrue("not ok is no success", ApiClient.uploadOutcome(200, """{"ok":false}""") is Upload.Refused)
    }

    /** An image share answers at once with what it does, not the note's "Merkt sich …". */
    @Test
    fun anImageShareAnswersAtOnce() {
        val i = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/1"))
        Robolectric.buildActivity(ShareActivity::class.java, i).create()
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Legt das Foto ab …", d.findViewById<TextView>(android.R.id.message)?.text.toString())
    }
}
