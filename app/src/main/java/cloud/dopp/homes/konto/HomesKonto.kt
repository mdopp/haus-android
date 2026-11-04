package cloud.dopp.homes.konto

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.kann.Liste
import cloud.dopp.homes.kann.hat
import java.security.MessageDigest

/**
 * Das Homes-Konto (#206, the owner's call of 2026-11-03): an Android account
 * „Homes“ whose contacts and calendars hold the household's shared data from
 * Homes (`/napi/gemeinsam/…`, homes#218), so the dialer, WhatsApp, mail and
 * the calendar app see them like any account's. Read only on the phone:
 * what changes, changes in Homes.
 *
 * Two parts, each behind its own switch and permission: [Teil.KONTAKTE]
 * (ContactsContract) and [Teil.KALENDER] (CalendarContract, the reminders
 * too). Android's SyncManager runs each part's sync adapter: once at the
 * switch, on „Jetzt abgleichen“, when Homes says a part changed while the
 * live stream is open ([anstossen]), and every [STUNDE] while the account syncs
 * automatically — no job, alarm or worker of the app's own. The account
 * stays while one part is on; with both off it goes, and Android removes its
 * contacts and calendars with it.
 *
 * The other way stays as it was: a person's own contacts go from the phone
 * to their archive („Kontakte übertragen“), and this account's are never
 * among them ([ohneHomes], `HandyKontakte`).
 */
object HomesKonto {

    /** The account type: the app's own name (NamesTest), declared in res/xml/konto.xml. */
    const val TYP = "cloud.dopp.homes"

    /** The account's name, what Android's settings and the contacts app show. */
    const val NAME = "Homes"

    /** How often Android syncs the account by itself, in seconds. */
    const val STUNDE = 60L * 60L

    /** A part: its provider, and its key in `/napi/kann`'s `konto` (homes ADR 0001 §2). */
    enum class Teil(val authority: String, val key: String) {
        KONTAKTE(ContactsContract.AUTHORITY, Napi.TEIL_KONTAKTE),
        KALENDER(CalendarContract.AUTHORITY, Napi.TEIL_KALENDER),
    }

    val konto = Account(NAME, TYP)

    /** Whether the account is on the phone. */
    fun da(ctx: Context): Boolean = AccountManager.get(ctx).getAccountsByType(TYP).any { it.name == NAME }

    /** Whether [teil] syncs: the account is there and the part is syncable. */
    fun an(ctx: Context, teil: Teil): Boolean = da(ctx) && ContentResolver.getIsSyncable(konto, teil.authority) > 0

    /** Turn [teil] on: the account made when missing, the part synced now and every hour. */
    fun einschalten(ctx: Context, teil: Teil) {
        if (!da(ctx)) {
            AccountManager.get(ctx).addAccountExplicitly(konto, null, null)
            // a new account is syncable for every authority until told otherwise
            Teil.values().forEach { ContentResolver.setIsSyncable(konto, it.authority, 0) }
        }
        ContentResolver.setIsSyncable(konto, teil.authority, 1)
        ContentResolver.setSyncAutomatically(konto, teil.authority, true)
        ContentResolver.addPeriodicSync(konto, teil.authority, Bundle.EMPTY, STUNDE)
        jetzt(teil)
    }

    /**
     * Turn [teil] off: its entries go from the phone. With the other part off
     * too the account goes, and Android takes the rest with it.
     */
    fun ausschalten(ctx: Context, teil: Teil) {
        if (!da(ctx)) return
        anhalten(teil)
        KontoStore(ctx).vergiss(teil)
        // a part held while the box lacks it keeps the account: its entries are not the person's to lose here
        if (Teil.values().none { it != teil && (an(ctx, it) || KontoStore(ctx).angehalten(it)) }) {
            AccountManager.get(ctx).removeAccountExplicitly(konto)
            return
        }
        runCatching { leeren(ctx, teil) } // without the permission any more: the entries stay until the account goes
    }

    /**
     * The parts follow the box (homes ADR 0001 §3): a part on whose key [kann]
     * lacks stops syncing — its entries stay on the phone, nothing is deleted
     * without asking — and syncs again once the box offers it again. Unknown:
     * nothing changes.
     */
    fun folgen(ctx: Context, kann: Kann?) {
        val store = KontoStore(ctx)
        Teil.values().forEach { teil ->
            val bietet = kann.hat(Liste.KONTO, teil.key)
            when {
                bietet && store.angehalten(teil) -> fortsetzen(ctx, teil, store)
                !bietet && an(ctx, teil) -> {
                    anhalten(teil)
                    store.setzeAngehalten(teil, true)
                }
            }
        }
    }

    /** A held part the box offers again: it syncs as before, where the account still is. */
    private fun fortsetzen(ctx: Context, teil: Teil, store: KontoStore) {
        store.setzeAngehalten(teil, false)
        if (da(ctx)) einschalten(ctx, teil)
    }

    /** [teil] syncs no more, by Android or by the app; what it wrote stays. */
    private fun anhalten(teil: Teil) {
        ContentResolver.removePeriodicSync(konto, teil.authority, Bundle.EMPTY)
        ContentResolver.setSyncAutomatically(konto, teil.authority, false)
        ContentResolver.cancelSync(konto, teil.authority)
        ContentResolver.setIsSyncable(konto, teil.authority, 0)
    }

    /** The account and everything in it off the phone: on unpairing. */
    fun entfernen(ctx: Context) {
        Teil.values().forEach { KontoStore(ctx).vergiss(it) }
        if (da(ctx)) AccountManager.get(ctx).removeAccountExplicitly(konto)
    }

    /** Ask Android to sync [teil] now, past its backoff. */
    fun jetzt(teil: Teil) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(konto, teil.authority, extras)
    }

    /**
     * Homes said [teil] changed (a `gemeinsam` frame, #209): sync it soon, if its
     * switch is on. Not marked manual, so Android's own setting „Daten
     * automatisch synchronisieren“ holds, as for the hourly sync.
     */
    fun anstossen(ctx: Context, teil: Teil) {
        if (!an(ctx, teil)) return
        ContentResolver.requestSync(konto, teil.authority, Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true) })
    }

    /** Whether Android syncs accounts by itself at all (Einstellungen → Konten). */
    fun automatisch(): Boolean = ContentResolver.getMasterSyncAutomatically()

    private fun leeren(ctx: Context, teil: Teil) {
        when (teil) {
            Teil.KONTAKTE -> ctx.contentResolver.delete(
                KontakteAbgleich.alsAdapter(ContactsContract.RawContacts.CONTENT_URI),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} = ?",
                arrayOf(TYP, NAME),
            )
            Teil.KALENDER -> ctx.contentResolver.delete(
                KalenderAbgleich.alsAdapter(CalendarContract.Calendars.CONTENT_URI),
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.ACCOUNT_NAME} = ?",
                arrayOf(TYP, NAME),
            )
        }
    }

    /**
     * A shared card's SOURCE_ID on the phone: its UID's SHA-1 in hex. Never
     * a dot in it, so Android's lookup key carries it as it is (`<hash>i<id>`)
     * and [ohneHomes] can take it out again.
     */
    fun quelle(uid: String): String =
        MessageDigest.getInstance("SHA-1").digest(uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * The part of a contact's lookup key Android adds for a raw contact of
     * this account with SOURCE_ID [quelle] (ContactsProvider's
     * ContactLookupKey: the account's 12-bit hash, `i`, the source id).
     */
    fun schluesselTeil(quelle: String): String = "${(TYP.hashCode() xor NAME.hashCode()) and 0xFFF}i$quelle"

    /**
     * [lookupKey] of a contact Android joined from the person's own raw
     * contacts and raw contacts of this account with [quellen], as it would
     * be without the latter: the contact keeps the UID it has in the archive
     * (`KontakteLauf`), whether or not a shared card is joined to it.
     */
    fun ohneHomes(lookupKey: String, quellen: Collection<String>): String {
        if (quellen.isEmpty()) return lookupKey
        val weg = quellen.map(::schluesselTeil).toSet()
        return lookupKey.split('.').let { teile ->
            // a source id with a dot is escaped as `..`: such parts are never ours (ours are hex), keep them whole
            val ganz = mutableListOf<String>()
            var i = 0
            while (i < teile.size) {
                var t = teile[i]
                while (i + 1 < teile.size && teile[i + 1].isEmpty() && i + 2 < teile.size) {
                    t += ".." + teile[i + 2]
                    i += 2
                }
                ganz += t
                i++
            }
            ganz.filter { it !in weg }.joinToString(".")
        }
    }
}
