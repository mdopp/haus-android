package cloud.dopp.homes.hochladen

import android.app.Activity
import android.os.Bundle
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.PhotoShare
import cloud.dopp.homes.ui.StandortImportActivity
import cloud.dopp.homes.widget.ActionDialog

/**
 * Share → „Zur Musik hinzufügen“ (owner, 2026-11-04): songs and album ZIPs
 * go to Homes' music (`POST /napi/musik`, beets sorts them in). Each file is
 * copied into the app's own storage — streamed, never held in memory — and
 * handed to the Hochladen job ([Hochladen]), which uploads it with the screen
 * off too and says in its notification how far it got and what came of it.
 * Translucent, its own task; the one dialog says what happens.
 */
class MusikTeilenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActionDialog.scrim(this)
        val uris = intent?.let(PhotoShare::uris).orEmpty() // one (SEND) or several (SEND_MULTIPLE), each once
        if (uris.isEmpty()) {
            finish()
            return
        }
        if (ServerStore.baseUrl(this).isNullOrBlank() || TokenStore.get(this).isNullOrBlank()) {
            sagen(getString(R.string.musik_nicht), getString(R.string.share_not_paired))
            return
        }
        val dateien = uris.map { it to StandortImportActivity.beschreibe(contentResolver, it).first }
            .filter { (u, n) -> MusikDatei.nimmt(n, contentResolver.getType(u) ?: intent.type) }
        if (dateien.isEmpty()) {
            sagen(getString(R.string.musik_nicht), getString(R.string.musik_falsch))
            return
        }
        Uebergabe.uebergeben(this, getString(R.string.musik_titel), dateien.mapIndexed { i, (u, n) ->
            u to MusikDatei.name(n, contentResolver.getType(u) ?: intent.type, i + 1)
        }, Ziel.MUSIK) { genommen ->
            val fehlen = uris.size - genommen
            resources.getQuantityString(R.plurals.musik_im_hintergrund, genommen, genommen) +
                (if (fehlen > 0) " " + resources.getQuantityString(R.plurals.musik_nicht_alle, fehlen, fehlen) else "")
        }
    }

    private fun sagen(title: String, message: String) = Uebergabe.sagen(this, title, message)
}
