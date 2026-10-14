package cloud.dopp.haus

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.widget.ActionDialog
import cloud.dopp.haus.widget.ActionItem
import cloud.dopp.haus.widget.ActionRow
import cloud.dopp.haus.widget.ActionSheet
import cloud.dopp.haus.widget.ActionSheets
import cloud.dopp.haus.widget.ActionTone
import cloud.dopp.haus.widget.ConfirmVerb
import cloud.dopp.haus.widget.LightColors
import cloud.dopp.haus.widget.LockChoice
import cloud.dopp.haus.widget.WidgetActionActivity
import cloud.dopp.haus.widget.WidgetActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * The common shape of every widget action dialog (#113) and the task they run in
 * (#114).
 *
 * Reported from the device: "Abbrechen" sat in a different place for the door
 * than for the gate. Three dialog forms stood side by side — a confirm with a
 * positive button, the lock chooser with a list *plus* a neutral button (which
 * Material puts far left), and the colour palette with a bare list — so the
 * safe answer moved with the dialog. Muscle memory then finds the wrong thing,
 * and on a front door that is not a cosmetic problem.
 *
 * What is asserted here is the arrangement, which is pure: every sheet ends with
 * the cancel footer, no action can ever occupy that place, and the one dangerous
 * entry (`lock.open`) stays last, marked and warned — the #92 rule, kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionSheetTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** Every sheet the app can show today, including both lock variants. */
    private fun allSheets(): List<Pair<String, ActionSheet>> = buildList {
        for (verb in ConfirmVerb.values()) add("confirm/$verb" to ActionSheets.confirm(verb))
        add("tool" to ActionSheets.toolConfirm())
        add("lock/no-latch" to ActionSheets.lock(null))
        add("lock/no-latch-0" to ActionSheets.lock(0))
        add("lock/latch" to ActionSheets.lock(1))
        add("colors" to ActionSheets.colors())
        // The tool row's sheet (#90) is the fourth dialog and must obey the same
        // rules as the other three — that is what keeps it from becoming a fourth
        // *form*.
        add("tool-row/one" to ActionSheets.toolActions(listOf(null)))
        add("tool-row/several" to ActionSheets.toolActions(listOf("1 Stunde", "4 Stunden", null)))
    }

    // --- the shape ------------------------------------------------------------

    /** The whole ticket in one assertion: Abbrechen is always the last row. */
    @Test fun cancelIsTheLastRowOfEverySheet() {
        for ((name, sheet) in allSheets()) {
            val rows = sheet.rows
            assertEquals("$name: cancel is the footer", ActionRow.Cancel, rows.last())
            assertEquals("$name", rows.lastIndex, sheet.cancelIndex)
            // …and it appears exactly once, so there is one place to look.
            assertEquals("$name", 1, rows.count { it == ActionRow.Cancel })
        }
    }

    /** No action — dangerous or not — may sit where another dialog puts Abbrechen. */
    @Test fun noActionEverSitsInTheCancelSlot() {
        for ((name, sheet) in allSheets()) {
            val last = sheet.rows[sheet.cancelIndex]
            assertFalse("$name: an action reached the footer", last is ActionRow.Pick)
            for (row in sheet.rows.dropLast(1)) {
                assertNotEquals("$name: cancel above an action", ActionRow.Cancel, row)
            }
        }
    }

    /** The actions keep the order the sheet declared — nothing is re-shuffled. */
    @Test fun everyActionIsPickableExactlyOnceInOrder() {
        for ((name, sheet) in allSheets()) {
            val picks = sheet.rows.filterIsInstance<ActionRow.Pick>()
            assertEquals("$name", sheet.items.size, picks.size)
            picks.forEachIndexed { i, pick ->
                assertEquals("$name", i, pick.index)
                assertEquals("$name", sheet.items[i], pick.item)
            }
        }
    }

    // --- the dangerous entry (#92, unchanged) ---------------------------------

    /**
     * "Tür öffnen" is the last entry, marked dangerous, and preceded by its own
     * warning line — set apart, but above the footer, not in it.
     */
    @Test fun theDoorIsLastMarkedAndWarned() {
        val sheet = ActionSheets.lock(1)
        assertEquals(3, sheet.items.size)
        assertEquals(LockChoice.OPEN.service, sheet.items.last().service)
        assertEquals(ActionTone.DANGEROUS, sheet.items.last().tone)
        assertTrue(sheet.hasDangerous)
        // The bolt directions stay ordinary rows.
        for (item in sheet.items.dropLast(1)) assertEquals(ActionTone.NORMAL, item.tone)

        val rows = sheet.rows
        val note = rows.filterIsInstance<ActionRow.Note>().single()
        val danger = rows.indexOfFirst { it is ActionRow.Pick && it.item.tone == ActionTone.DANGEROUS }
        assertEquals("the warning stands directly above the door", danger - 1, rows.indexOf(note))
        assertEquals("the door is the last action", sheet.items.lastIndex, (rows[danger] as ActionRow.Pick).index)
        assertEquals(R.string.widget_open_door_note, note.textRes)
        assertEquals("Öffnet die Tür – nicht nur das Schloss.", ctx.getString(note.textRes))
    }

    /** No latch bit ⇒ no door entry and nothing dangerous at all (#92). */
    @Test fun withoutALatchNothingDangerousIsOffered() {
        for (features in listOf(null, 0, 2, 4, 8)) {
            val sheet = ActionSheets.lock(features)
            assertFalse("features $features", sheet.hasDangerous)
            assertEquals("features $features", 2, sheet.items.size)
            assertTrue("features $features", sheet.rows.none { it is ActionRow.Note })
            for (item in sheet.items) assertNotEquals("lock.open", item.service)
        }
    }

    /** `lock.open` reads as dangerous wherever it turns up, confirm gate included. */
    @Test fun theLatchIsDangerousInEveryDialog() {
        val unlatch = ActionSheets.confirm(ConfirmVerb.UNLATCH)
        assertTrue(unlatch.hasDangerous)
        assertEquals(R.string.widget_open_door_note, unlatch.noteRes)
        for (verb in ConfirmVerb.values().filter { it != ConfirmVerb.UNLATCH }) {
            val sheet = ActionSheets.confirm(verb)
            assertFalse("$verb", sheet.hasDangerous)
            assertEquals("$verb", ActionTone.PRIMARY, sheet.items.single().tone)
        }
    }

    /** A dangerous entry above an ordinary one cannot even be built. */
    @Test fun theArrangementIsStructuralNotAConvention() {
        val door = ActionItem(R.string.widget_confirm_unlatch, ActionTone.DANGEROUS)
        val bolt = ActionItem(R.string.widget_confirm_lock, ActionTone.NORMAL)
        for (bad in listOf(listOf(door, bolt), listOf(bolt, door, bolt))) {
            try {
                ActionSheet(bad, R.string.widget_open_door_note)
                throw AssertionError("a dangerous action before an ordinary one must not build")
            } catch (e: IllegalArgumentException) {
                // exactly right
            }
        }
        // …and a dangerous entry without its warning is refused too.
        try {
            ActionSheet(listOf(door))
            throw AssertionError("a dangerous action must carry its warning")
        } catch (e: IllegalArgumentException) {
        }
    }

    // --- wording and payloads -------------------------------------------------

    /** The words still come from the #85 table, not from a second set here. */
    @Test fun theWordsComeFromTheConfirmTable() {
        assertEquals("Tür öffnen", ctx.getString(ActionSheets.confirm(ConfirmVerb.UNLATCH).items.single().labelRes))
        assertEquals("Abschließen", ctx.getString(ActionSheets.lock(null).items[1].labelRes))
        assertEquals("Ausführen", ctx.getString(ActionSheets.toolConfirm().items.single().labelRes))
        // Abbrechen is one string for every sheet — one place, one word.
        assertEquals("Abbrechen", ctx.getString(R.string.widget_confirm_no))
    }

    /** The colour sheet keeps the palette's own order, so a pick means what it says. */
    @Test fun theColourSheetIsThePaletteInOrder() {
        val sheet = ActionSheets.colors()
        assertEquals(LightColors.PALETTE.size, sheet.items.size)
        assertFalse(sheet.hasDangerous)
        sheet.items.forEachIndexed { i, item ->
            val swatch = LightColors.PALETTE[i]
            assertEquals(swatch.labelRes, item.labelRes)
            assertEquals(swatch.rgb, item.swatchRgb)
            assertNull("a colour names no service", item.service)
        }
    }

    // --- the task the dialog runs in (#114) -----------------------------------

    /**
     * The dialogs used to show the last open app screen because they started
     * inside the app's task and are translucent. Asserted over the manifest, since
     * nothing about a missing attribute would fail to compile.
     */
    @Test fun everyDialogActivityRunsInItsOwnTask() {
        val manifest = File(locate("src/main"), "AndroidManifest.xml").readText()
        val activities = Regex("<activity[^>]*?/>", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { it.value }.toList()
        for (name in listOf(
            ".widget.WidgetActionActivity",
            ".widget.ToolActionActivity",
        )) {
            val decl = activities.single { it.contains("android:name=\"$name\"") }
            assertTrue("$name needs its own task (#114)", decl.contains("android:taskAffinity=\"\""))
            assertTrue("$name must stay out of Recents", decl.contains("android:excludeFromRecents=\"true\""))
            assertTrue("$name is not exported", decl.contains("android:exported=\"false\""))
        }
        // The keyguard rule of #92 is untouched by any of this.
        for (flag in listOf("showWhenLocked", "turnScreenOn", "dismissKeyguard")) {
            assertFalse(flag, manifest.contains(flag))
        }
    }

    /** A second action must not be folded back into the first one's task. */
    @Test fun theLaunchFlagsMatchThatTask() {
        assertTrue(ActionDialog.TASK_FLAGS and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(ActionDialog.TASK_FLAGS and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
        // Nothing that would drag the activity back into the app's task.
        assertEquals(0, ActionDialog.TASK_FLAGS and Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /**
     * One shape means one code path: no widget dialog builds its own button row
     * any more, so the fourth sheet (#90) cannot quietly become a fourth form.
     */
    @Test fun noWidgetDialogBuildsItsOwnButtonRow() {
        val dir = File(locate("src/main"), "java/cloud/dopp/haus/widget")
        for (src in dir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            if (src.name == "ActionDialog.kt") continue
            val text = src.readText()
            for (call in listOf("setNeutralButton", "setPositiveButton", "setNegativeButton", "setItems(")) {
                assertFalse("${src.name} must go through ActionDialog (#113): $call", text.contains(call))
            }
        }
    }

    // --- the drawn dialog -----------------------------------------------------

    /**
     * The lock chooser as it actually renders: one card, the actions stacked, and
     * the cancel footer under them. The framework button bar — where the neutral
     * slot used to put "Tür öffnen" far left and "Abbrechen" right — is empty, so
     * the old three-forms problem cannot come back through a builder call.
     */
    @Test fun theLockChooserRendersAsOneCardWithACancelFooter() {
        val intent = Intent(RuntimeEnvironment.getApplication(), WidgetActionActivity::class.java)
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, "lock.haustuer")
            .putExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, true)
        val activity = Robolectric.buildActivity(WidgetActionActivity::class.java, intent).setup().get()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue("a dialog is on screen", dialog.isShowing)
        for (button in listOf(AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            val b = dialog.getButton(button)
            assertTrue("the framework button bar stays empty (#113)", b == null || !b.isShown)
        }
        val cancel = dialog.findViewById<Button>(R.id.ad_cancel)!!
        assertEquals("Abbrechen", cancel.text.toString())
        val stack = dialog.findViewById<LinearLayout>(R.id.ad_items)!!
        // Without a widget there is no latch bit, so two rows and no warning.
        assertEquals(2, stack.childCount)
        // The footer is not inside the scrolling stack — that is what pins it.
        assertNotEquals(stack, cancel.parent)

        // #114: the activity paints its own backdrop instead of showing the app.
        val bg = activity.window.decorView.background
        assertTrue("the dialog gets a defined scrim", bg is ColorDrawable)
        assertEquals(activity.getColor(R.color.haus_scrim), (bg as ColorDrawable).color)
    }

    /**
     * #119: the backdrop lets a little more through than it did in v2.34.0 (85 %),
     * without giving up what #114 bought — it is still an opaque-enough scrim of
     * the Haus ground, so the dialog looks the same whatever lies behind it.
     * The dialog's own card is fully opaque, so the text is untouched by this.
     */
    @Test fun theScrimIsLighterButStillAScrim() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val scrim = ctx.getColor(R.color.haus_scrim)
        val alpha = (scrim ushr 24) and 0xFF
        assertTrue("the scrim got lighter than v2.34.0's 0xD9: $alpha", alpha < 0xD9)
        assertTrue("…but it is still a scrim, not a see-through window: $alpha", alpha >= 0x99)
        // Same near-black ground as before — only the opacity moved.
        assertEquals(0x070709, scrim and 0xFFFFFF)
    }

    /** The colour palette uses the same card, footer and all — ten rows, one shape. */
    @Test fun theColourPaletteUsesTheSameCard() {
        val intent = Intent(RuntimeEnvironment.getApplication(), WidgetActionActivity::class.java)
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, "light.wohnzimmer")
            .putExtra(WidgetActionActivity.EXTRA_PICK_COLOR, true)
        Robolectric.buildActivity(WidgetActionActivity::class.java, intent).setup().get()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val stack = dialog.findViewById<LinearLayout>(R.id.ad_items)!!
        assertEquals(LightColors.PALETTE.size, stack.childCount)
        assertEquals("Abbrechen", dialog.findViewById<Button>(R.id.ad_cancel)!!.text.toString())
        // Ten rows and not one of them warned: colour is not a sensitive action.
        assertFalse(ActionSheets.colors().hasDangerous)
    }

    /** Unit tests run from the module dir, but don't depend on it. */
    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, rel), File(dir, "app/$rel"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $rel from ${File("").absolutePath}")
    }
}
