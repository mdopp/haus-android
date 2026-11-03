package cloud.dopp.homes.ui

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A photo shared to Homes (owner, 2026-11-02: „Foto machen und mit der Homes
 * App teilen“): the camera app's share sheet sends one or several images
 * here, and each goes to Homes' `POST /napi/upload` — the folder Paperless
 * consumes — the way the old capture screen sent its pages.
 *
 * Homes takes JPEG, PNG, WebP and GIF of at most 25 MB (`upload.go`). What
 * the camera gives in another form — HEIC, HEIF — or larger is decoded on
 * the phone and sent as a JPEG, its long edge at most [MAX_EDGE].
 */
object PhotoShare {

    /** What Homes takes as it comes (`uploadTypes` in Homes' upload.go), with the extension it gets. */
    val TAKEN = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif")

    /** Homes' limit per file. */
    const val MAX_BYTES = 25L shl 20

    /** A converted photo's long edge: sharp enough for a document, far below 25 MB. */
    const val MAX_EDGE = 4096

    /** Homes takes at most this many files from one share; the rest is said. */
    const val MAX_PHOTOS = 20

    /** One photo, ready to send. */
    class Photo(val bytes: ByteArray, val mime: String, val ext: String)

    /** Is [intent] a share of images? Text keeps its own way (`/napi/merken`). */
    fun isImageShare(intent: Intent?): Boolean =
        intent != null && intent.type?.startsWith("image/") == true &&
            (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)

    /** The images shared: one (`SEND`) or several (`SEND_MULTIPLE`), each once. */
    @Suppress("DEPRECATION")
    fun uris(intent: Intent): List<Uri> {
        val out = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        return out.distinct()
    }

    /** Must a photo of [mime] and [size] bytes be made a JPEG first? */
    fun needsJpeg(mime: String?, size: Long): Boolean = mime?.lowercase(Locale.ROOT) !in TAKEN || size > MAX_BYTES

    /** The name Homes files it under: `Foto-<date>-<time>`, numbered when several came. */
    fun name(at: Date, index: Int, count: Int, ext: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(at)
        return if (count > 1) "Foto-$stamp-${index + 1}.$ext" else "Foto-$stamp.$ext"
    }

    /** [uri] read and, when Homes would not take it as it is, made a JPEG. Null when it cannot be read. */
    fun read(resolver: ContentResolver, uri: Uri): Photo? {
        val mime = resolver.getType(uri)?.lowercase(Locale.ROOT)
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        if (!needsJpeg(mime, bytes.size.toLong())) return Photo(bytes, mime!!, TAKEN.getValue(mime))
        val jpeg = toJpeg(bytes) ?: return null
        return Photo(jpeg, "image/jpeg", "jpg")
    }

    /** Decode [bytes] (HEIC on Android 9+, and every format the platform reads), at most [MAX_EDGE] long, as JPEG. */
    fun toJpeg(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
            out.toByteArray()
        }
    }
}
