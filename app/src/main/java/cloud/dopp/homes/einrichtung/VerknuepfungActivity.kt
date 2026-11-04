package cloud.dopp.homes.einrichtung

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import cloud.dopp.homes.R
import cloud.dopp.homes.ui.ShareActivity
import java.io.File

/**
 * Where a launcher shortcut that is no device lands (owner, 2026-11-04): a
 * shortcut can only start an activity, so this one, without a screen of its
 * own, opens the shortcut's address in a Custom Tab — where the person's
 * login is — or, for „Dokument scannen“, the phone's camera app. The photo
 * goes the way a photo shared to Homes goes ([ShareActivity], into Homes'
 * document inbox, `POST /napi/upload`), with the same dialog saying how it went.
 *
 * The camera app writes into the app's cache through the one file provider
 * (`res/xml/scan_pfade.xml`); the app has no camera of its own. Android
 * lets an app that declares the camera — the pairing's QR scanner does —
 * start the camera app only once the person allowed it, so it asks first.
 * Not exported: only the app's own shortcuts start it.
 */
class VerknuepfungActivity : ComponentActivity() {

    /** Where the camera app puts the photo; kept across a recreate while the camera is open. */
    private var foto: Uri? = null

    private val kamera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        foto?.takeIf { ok }?.let(::ablegen)
        finish()
    }

    private val erlaubnis = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ja ->
        if (ja) aufnehmen() else sagen(R.string.scannen_erlaubnis)
    }

    @Suppress("DEPRECATION") // getParcelable without its class, below Android 13
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { // back from the camera after a recreate: its answer comes to [kamera]
            foto = if (Build.VERSION.SDK_INT >= 33) savedInstanceState.getParcelable(FOTO, Uri::class.java) else savedInstanceState.getParcelable(FOTO)
            return
        }
        when (intent?.action) {
            SCANNEN -> if (erlaubt()) aufnehmen() else erlaubnis.launch(Manifest.permission.CAMERA)
            Intent.ACTION_VIEW -> {
                Einrichtung.https(intent.dataString)?.let { Abschnitte.oeffnen(this, it.toUri()) }
                finish()
            }
            else -> finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(FOTO, foto)
    }

    private fun erlaubt() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** The camera app, writing into `cache/scan/`; the last scan's file is taken again. */
    private fun aufnehmen() {
        val datei = File(File(cacheDir, ORDNER).apply { mkdirs() }, "dokument.jpg")
        val uri = FileProvider.getUriForFile(this, packageName + AUTHORITY, datei)
        foto = uri
        if (runCatching { kamera.launch(uri) }.isFailure) sagen(R.string.scannen_keine_kamera)
    }

    /** The photo into Homes' document inbox, the share's way: its dialog says what became of it. */
    private fun ablegen(uri: Uri) {
        startActivity(
            Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun sagen(text: Int) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        /** The shortcut „Dokument scannen“. */
        const val SCANNEN = "cloud.dopp.homes.einrichtung.SCANNEN"

        /** After the package name: the scan's file provider (AndroidManifest.xml). */
        private const val AUTHORITY = ".scan"
        private const val ORDNER = "scan"
        private const val FOTO = "foto"
    }
}
