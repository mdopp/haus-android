package cloud.dopp.haus.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/**
 * Encrypted-at-rest storage for the native client's device-token bearer
 * (`haus_device_…` — the prefix is checked by
 * [PairLinks] before saving, not here), backed by [EncryptedSharedPreferences]
 * + an AndroidKeyStore master key.
 *
 * **Durability (#46).** Two hazards used to silently delete the token — which
 * blanked every widget "after a while / on certain events":
 *  1. `EncryptedSharedPreferences.create` was called on *every* access. Several
 *     widgets refresh on background threads at once, and concurrent `create()`
 *     is a well-known source of intermittent exceptions. → We now build the store
 *     **once** (memoised, synchronised) and reuse it.
 *  2. The old code wiped the token on *any* `Throwable`. A transient keystore
 *     failure (keystore briefly unavailable after a reboot/update, a concurrency
 *     hiccup) therefore destroyed a perfectly good token **permanently**. → We
 *     now wipe **only** on a genuinely corrupt keyset (`AEADBadTagException`),
 *     keep an in-memory token cache, and let transient failures fail *this* read
 *     without touching the stored token (a later read recovers it).
 */
object TokenStore {
    private const val PREFS = "haus_secure_prefs"
    private const val KEY_TOKEN = "device_token"

    @Volatile private var memo: SharedPreferences? = null
    // Once the token is known (read or saved) it's kept here, so a transient
    // store failure never makes the app look "unpaired".
    @Volatile private var tokenCache: String? = null
    @Volatile private var tokenLoaded = false

    private fun build(ctx: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            ctx.applicationContext,
            PREFS,
            MasterKey.Builder(ctx.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /**
     * The encrypted store, created at most once. On a genuinely corrupt keyset
     * (only) it is wiped and recreated. On a transient failure it rethrows WITHOUT
     * wiping, so the stored token survives and callers fall back to [tokenCache].
     */
    @Synchronized
    private fun prefs(ctx: Context): SharedPreferences {
        memo?.let { return it }
        val app = ctx.applicationContext
        val p = try {
            build(app)
        } catch (e: Throwable) {
            if (isCorruptKeyset(e)) {
                // Restored file without its AndroidKeyStore key, etc. — unrecoverable.
                // The token is re-mintable via pairing, so dropping it is acceptable.
                wipe(app)
                build(app)
            } else {
                // Transient (keystore locked/unavailable, concurrent access) — do NOT
                // wipe. Fail this access; a later one succeeds with the token intact.
                throw e
            }
        }
        memo = p
        return p
    }

    /** True only for the "keyset can't be decrypted" signature, not transient errors. */
    private fun isCorruptKeyset(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is AEADBadTagException) return true
            val n = t.javaClass.name
            if (n.contains("AEADBadTag")) return true
            if (t is GeneralSecurityException &&
                t.message?.contains("decrypt", ignoreCase = true) == true
            ) return true
            t = t.cause
        }
        return false
    }

    private fun wipe(ctx: Context) {
        memo = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ctx.deleteSharedPreferences(PREFS)
            File(ctx.applicationInfo.dataDir, "shared_prefs/$PREFS.xml").delete()
        }
    }

    fun save(ctx: Context, token: String) {
        tokenCache = token
        tokenLoaded = true
        runCatching { prefs(ctx).edit().putString(KEY_TOKEN, token).apply() }
    }

    fun get(ctx: Context): String? {
        if (tokenLoaded) return tokenCache
        val v = runCatching { prefs(ctx).getString(KEY_TOKEN, null) }.getOrNull()
        if (v != null) {
            tokenCache = v
            tokenLoaded = true
        }
        // A transient failure (v == null but not truly loaded) returns the last
        // known cache (may be null) — it never deletes the stored token.
        return v ?: tokenCache
    }

    fun clear(ctx: Context) {
        tokenCache = null
        tokenLoaded = true // an intentional logout — really is unpaired now
        runCatching { prefs(ctx).edit().remove(KEY_TOKEN).apply() }
    }

    fun isPaired(ctx: Context): Boolean = !get(ctx).isNullOrBlank()
}
