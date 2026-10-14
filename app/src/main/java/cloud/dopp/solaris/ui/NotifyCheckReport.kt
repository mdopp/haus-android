package cloud.dopp.solaris.ui

import cloud.dopp.solaris.widget.PwaLauncher

/**
 * The text the diagnostics "Meldungen prüfen" run writes into the diagnostics
 * screen (#110).
 *
 * #45 sat for months because its trigger — a **rise** in pending updates while the
 * screen is off — cannot be provoked on demand: with nothing pending on the box the
 * case never fires, and the baseline is persisted, so a missed rise never returns.
 * What actually broke twice on that path was never the (pure, unit-tested) trigger
 * but the visible end: the icon (#88/#89) and the tap target (#45/#109). This
 * report shows the numbers the poll would have seen and the URL the tap resolves
 * to, so both are checkable without a pending update.
 *
 * Pure / Android-free so it's unit-testable; the caller supplies what it fetched.
 */
object NotifyCheckReport {

    /** Shown instead of a target URL while the app isn't paired to a server. */
    const val NO_SERVER = "(kein Server gekoppelt)"

    /** Where a ServiceBay tap lands for [base] — the admin host, or [NO_SERVER]. */
    fun target(base: String?): String = PwaLauncher.serviceBayUrl(base) ?: NO_SERVER

    /**
     * One report block. [approvals]/[updates] are the counts just fetched from
     * `/napi/box/home`, or null when the fetch failed — then [error] carries
     * the reason. [notificationsEnabled] false explains the otherwise baffling case
     * "test ran, nothing appeared" (POST_NOTIFICATIONS denied or channel muted).
     */
    fun format(
        base: String?,
        approvals: Int?,
        updates: Int?,
        error: String?,
        notificationsEnabled: Boolean = true,
    ): String {
        val sb = StringBuilder("── Meldungs-Prüfung ──\n")
        if (approvals == null || updates == null) {
            sb.append("Abruf fehlgeschlagen: ")
                .append(error?.takeIf { it.isNotBlank() } ?: "unbekannter Fehler").append('\n')
        } else {
            sb.append("Freigaben offen: ").append(approvals).append('\n')
            sb.append("Updates ausstehend: ").append(updates).append('\n')
        }
        sb.append("Tippziel: ").append(target(base)).append('\n')
        sb.append("Testmeldungen zugestellt: Freigabe + Update\n")
        if (!notificationsEnabled) sb.append("Achtung: Benachrichtigungen sind für Solaris aus.\n")
        sb.append("Grundlinie zurückgesetzt — der nächste Durchgang bei Bildschirm aus meldet erneut.")
        return sb.toString()
    }
}
