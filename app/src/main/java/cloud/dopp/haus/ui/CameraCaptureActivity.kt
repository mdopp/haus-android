package cloud.dopp.haus.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ServerStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Native camera capture + upload (#36). A tap on the camera trigger widget opens
 * this screen (NOT Haus's pages): take one photo or a series, name the file (date/time
 * prefilled + editable), optionally combine the series into a single multi-page
 * PDF, and upload to the paired Haus via `POST /napi/upload` (Haus's
 * `internal/napi/upload.go`). The upload reports a 404 plainly: Haus serves
 * the path only when it has an upload folder.
 */
class CameraCaptureActivity : AppCompatActivity() {

    private val pages = mutableListOf<File>()
    private var pendingCapture: File? = null

    private lateinit var countView: TextView
    private lateinit var statusView: TextView
    private lateinit var filenameField: EditText
    private lateinit var pdfSwitch: Switch
    private lateinit var bwSwitch: Switch
    private lateinit var uploadBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var takeBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var gallery: HorizontalScrollView
    private lateinit var galleryRow: LinearLayout

    // Largest edge we scale captures to for PDF/upload — keeps size/memory sane
    // while staying legible for documents.
    private val maxEdge = 1600

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = pendingCapture
            pendingCapture = null
            if (ok && f != null && f.exists() && f.length() > 0) {
                pages.add(f)
                refresh()
            } else {
                f?.delete()
            }
        }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCapture()
            else toast(getString(R.string.camera_capture_no_permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_capture)

        countView = findViewById(R.id.cap_count)
        statusView = findViewById(R.id.cap_status)
        filenameField = findViewById(R.id.cap_filename)
        pdfSwitch = findViewById(R.id.cap_pdf)
        bwSwitch = findViewById(R.id.cap_bw)
        uploadBtn = findViewById(R.id.cap_upload)
        clearBtn = findViewById(R.id.cap_clear)
        takeBtn = findViewById(R.id.cap_take)
        progress = findViewById(R.id.cap_progress)
        gallery = findViewById(R.id.cap_gallery)
        galleryRow = findViewById(R.id.cap_gallery_row)

        // Prefill the filename with a sortable date/time stamp; the user edits the
        // suffix. e.g. "2026-07-14_183045_".
        resetFilename()

        // PDF is the default upload format (#67) — most captures are documents.
        pdfSwitch.isChecked = true

        takeBtn.setOnClickListener { onTake() }
        clearBtn.setOnClickListener { clearPages() }
        uploadBtn.setOnClickListener { onUpload() }

        refresh()

        // Open the camera straight away on a fresh start (#62) — no extra tap for
        // the first shot. Only on the first create, so returning from a capture or
        // rotating doesn't re-launch it.
        if (savedInstanceState == null && pages.isEmpty()) onTake()
    }

    /** Fresh sortable date/time prefix, seconds-precision so each upload is unique. */
    private fun datePrefix(): String =
        SimpleDateFormat("yyyy-MM-dd_HHmmss_", Locale.GERMANY).format(Date())

    /** Reset the filename field to a fresh timestamp, cursor at the end (#67). */
    private fun resetFilename() {
        filenameField.setText(datePrefix())
        filenameField.setSelection(filenameField.text.length)
    }

    private fun onTake() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchCapture() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun launchCapture() {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "page_${System.currentTimeMillis()}.jpg")
        pendingCapture = f
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            pendingCapture = null
            f.delete()
            toast(getString(R.string.camera_capture_no_camera))
        }
    }

    private fun clearPages() {
        pages.forEach { it.delete() }
        pages.clear()
        refresh()
    }

    private fun refresh() {
        val n = pages.size
        countView.text = when (n) {
            0 -> getString(R.string.camera_capture_none)
            1 -> getString(R.string.camera_capture_one)
            else -> getString(R.string.camera_capture_many, n)
        }
        clearBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
        uploadBtn.isEnabled = n > 0
        // A PDF only makes sense as a "combine" for the series; still allowed for one.
        pdfSwitch.isEnabled = n > 0
        takeBtn.text = getString(
            if (n > 0) R.string.camera_capture_take_more else R.string.camera_capture_take,
        )
        rebuildGallery()
    }

    /** Rebuild the thumbnail gallery (#62) from [pages], wiring delete + reorder. */
    private fun rebuildGallery() {
        galleryRow.removeAllViews()
        gallery.visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        pages.forEachIndexed { index, file ->
            val item = inflater.inflate(R.layout.camera_page_item, galleryRow, false)
            item.findViewById<ImageView>(R.id.page_thumb).setImageBitmap(decodeThumb(file))
            item.findViewById<TextView>(R.id.page_no).text = (index + 1).toString()
            item.findViewById<TextView>(R.id.page_delete).setOnClickListener { deletePage(index) }
            item.findViewById<TextView>(R.id.page_left).apply {
                isEnabled = index > 0
                alpha = if (index > 0) 1f else 0.3f
                setOnClickListener { movePage(index, index - 1) }
            }
            item.findViewById<TextView>(R.id.page_right).apply {
                isEnabled = index < pages.size - 1
                alpha = if (index < pages.size - 1) 1f else 0.3f
                setOnClickListener { movePage(index, index + 1) }
            }
            galleryRow.addView(item)
        }
    }

    private fun deletePage(index: Int) {
        if (index !in pages.indices) return
        pages.removeAt(index).delete()
        refresh()
    }

    private fun movePage(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices) return
        pages.add(to, pages.removeAt(from))
        refresh()
    }

    /** A small centre-cropped thumbnail (≤300px) for the gallery. */
    private fun decodeThumb(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val longer = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longer > 0 && longer / (sample * 2) >= 300) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return applyExifRotation(f, bmp)
    }

    private fun onUpload() {
        if (pages.isEmpty()) return
        val server = ServerStore.baseUrl(this)
        if (server.isNullOrBlank()) {
            toast(getString(R.string.camera_capture_not_paired))
            return
        }
        val base = sanitize(filenameField.text.toString().ifBlank { datePrefix() })
        val asPdf = pdfSwitch.isChecked
        val mono = bwSwitch.isChecked
        setBusy(true)
        statusView.text = getString(R.string.camera_capture_uploading)

        thread {
            val api = ApiClient(applicationContext)
            val result: Int = try {
                if (asPdf) {
                    val pdf = buildPdf(pages, mono)
                    api.uploadFile(pdf, "$base.pdf", "application/pdf")
                } else if (pages.size == 1) {
                    api.uploadFile(readScaledJpeg(pages[0], mono), "$base.jpg", "image/jpeg")
                } else {
                    // Upload each page as its own image; overall result = worst code.
                    var worst = 200
                    pages.forEachIndexed { i, f ->
                        val code = api.uploadFile(readScaledJpeg(f, mono), "${base}-${i + 1}.jpg", "image/jpeg")
                        if (!(code in 200..299)) worst = code
                    }
                    worst
                }
            } catch (e: Throwable) {
                -1
            }
            runOnUiThread { onUploadDone(result) }
        }
    }

    private fun onUploadDone(code: Int) {
        setBusy(false)
        when {
            code in 200..299 -> {
                statusView.text = getString(R.string.camera_capture_done)
                toast(getString(R.string.camera_capture_done))
                clearPages()
                // Fresh name for the next document so the old one isn't reused (#67).
                resetFilename()
            }
            code == 404 -> statusView.text = getString(R.string.camera_capture_no_endpoint)
            code == 401 || code == 403 -> statusView.text = getString(R.string.camera_capture_auth)
            else -> statusView.text = getString(R.string.camera_capture_failed, code)
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
        uploadBtn.isEnabled = !busy && pages.isNotEmpty()
        findViewById<Button>(R.id.cap_take).isEnabled = !busy
    }

    /** Decode [f] downsampled so its longer edge is ≤ [maxEdge], re-encoded as JPEG. */
    private fun readScaledJpeg(f: File, mono: Boolean): ByteArray {
        val bmp = decodeScaled(f)?.let { if (mono) toGrayscale(it) else it } ?: return f.readBytes()
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Assemble the captured pages into one multi-page PDF (one page per image). */
    private fun buildPdf(files: List<File>, mono: Boolean): ByteArray {
        val doc = PdfDocument()
        try {
            files.forEachIndexed { i, f ->
                val bmp = decodeScaled(f)?.let { if (mono) toGrayscale(it) else it }
                    ?: return@forEachIndexed
                val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
                bmp.recycle()
            }
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun decodeScaled(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val longer = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longer > 0 && longer / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        return applyExifRotation(f, bmp)
    }

    /**
     * Rotate/flip [bmp] to match the JPEG's EXIF orientation tag (#67). The camera
     * often stores a landscape sensor frame with an orientation flag rather than
     * rotating the pixels; [BitmapFactory] ignores that flag, so documents land
     * sideways in the PDF/JPEG unless we apply it here.
     */
    private fun applyExifRotation(f: File, bmp: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(f.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return try {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            bmp
        }
    }

    /** Desaturate [bmp] to greyscale — smaller upload, better document contrast. */
    private fun toGrayscale(bmp: Bitmap): Bitmap {
        val gray = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(gray).drawBitmap(bmp, 0f, 0f, paint)
        bmp.recycle()
        return gray
    }

    /** Keep it filesystem-safe: letters/digits/_-. only; collapse the rest to '_'. */
    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "upload" }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) pages.forEach { it.delete() }
    }
}
