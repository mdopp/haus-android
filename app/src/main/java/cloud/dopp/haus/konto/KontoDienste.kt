package cloud.dopp.haus.konto

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import cloud.dopp.haus.data.GemeinsamClient
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.sicherung.Lauf
import cloud.dopp.haus.ui.HausKontoActivity

/**
 * The Haus account's three bound services (#206): its authenticator, and one
 * sync adapter for each part. Android binds them; none runs on its own, none
 * goes foreground, none posts anything (ManifestTest). The authenticator
 * holds no password: the account is the device token's, which stays in
 * TokenStore. Adding the account from Android's settings opens the app's
 * screen, where the switches are.
 */
class KontoAuthenticator(private val ctx: Context) : AbstractAccountAuthenticator(ctx) {

    override fun addAccount(
        response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
        requiredFeatures: Array<out String>?, options: Bundle?,
    ): Bundle = Bundle().apply {
        putParcelable(AccountManager.KEY_INTENT, Intent(ctx, HausKontoActivity::class.java))
    }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?,
    ): Bundle = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, "no auth token")
    }

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?,
    ): Bundle? = null

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

class KontoDienst : Service() {
    private lateinit var authenticator: KontoAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = KontoAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}

/** One part's sync adapter: the device token's client, the part's writer, run once per sync Android asks for. */
class KontoSyncAdapter(ctx: Context, private val teil: HausKonto.Teil) : AbstractThreadedSyncAdapter(ctx, true, false) {

    override fun onPerformSync(account: Account, extras: Bundle?, authority: String, provider: ContentProviderClient, result: SyncResult) {
        if (account.type != HausKonto.TYP) return
        val store = KontoStore(context)
        val base = ServerStore.baseUrl(context)
        val token = TokenStore.get(context)
        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            store.melde(teil, Lauf.NICHT_GEKOPPELT)
            return
        }
        val client = GemeinsamClient(base, token)
        when (teil) {
            HausKonto.Teil.KONTAKTE -> KontakteAbgleich(provider, store, client::kontakte).laufen(result)
            HausKonto.Teil.KALENDER -> KalenderAbgleich(provider, store, client::kalender).laufen(result)
        }
    }
}

class KontakteSyncDienst : Service() {
    private val adapter by lazy { KontoSyncAdapter(applicationContext, HausKonto.Teil.KONTAKTE) }

    override fun onBind(intent: Intent?): IBinder? = adapter.syncAdapterBinder
}

class KalenderSyncDienst : Service() {
    private val adapter by lazy { KontoSyncAdapter(applicationContext, HausKonto.Teil.KALENDER) }

    override fun onBind(intent: Intent?): IBinder? = adapter.syncAdapterBinder
}
