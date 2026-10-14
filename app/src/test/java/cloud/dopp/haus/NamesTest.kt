package cloud.dopp.haus

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The new app carries no old name (#176, laib ADR 0022 §1 and §4): not the old
 * package id, not the old token prefix, not the old Box path, not the old app
 * or server name — in no file and no file name of this repository. Only a
 * CHANGELOG and the git history may still say them.
 *
 * The Box's old short prefix counts too, in any case (`s`+`b_` in a resource,
 * an id, a string, a constant): the Box is the Box, so its names start with
 * `box_`. So does the phrase a blind rename of the server's old name left in
 * comments, which then said things that are no longer true.
 *
 * The names are spelled in halves here, so this file does not find itself.
 */
class NamesTest {

    private val old = listOf(
        "sol" + "aris", // the old app, its package id cloud.dopp.<it>, its server
        "sol" + "_device_", // the old token prefix
        "/napi/" + "service" + "bay", // the old Box path
        "service" + "bay", // the old name of the Box
    )

    /** The Box's old prefix as a name of its own: not inside a word (`usb_`), but after `_` or `.`. */
    private val oldPrefix = Regex("(?<![A-Za-z0-9])" + "s" + "b_", RegexOption.IGNORE_CASE)

    /** What a blind rename of the server's old name left in comments. */
    private val leftover = "the old " + "server"

    private fun exempt(f: File) = f.name.startsWith("CHANGELOG", ignoreCase = true)

    private fun says(text: String): String? =
        old.firstOrNull { text.contains(it, ignoreCase = true) }
            ?: oldPrefix.find(text)?.value
            ?: leftover.takeIf { text.contains(it, ignoreCase = true) }

    @Test
    fun noFileIsNamedAfterTheOldApp() {
        val hits = RepoFiles.walk().filterNot(::exempt).map(RepoFiles::relative)
            .filter { p -> old.any { p.contains(it, ignoreCase = true) } || oldPrefix.containsMatchIn(p) }
        assertTrue("files named after the old app:\n" + hits.take(60).joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun noFileSaysAnOldName() {
        val hits = mutableListOf<String>()
        for (f in RepoFiles.walk().filterNot(::exempt).filter(RepoFiles::isText)) {
            f.readLines().forEachIndexed { i, line ->
                says(line)?.let {
                    hits.add("${RepoFiles.relative(f)}:${i + 1}: $it")
                }
            }
        }
        assertTrue("${hits.size} old names left, the first:\n" + hits.take(80).joinToString("\n"), hits.isEmpty())
    }

    /** The test itself would not let an old prefix through. */
    @Test
    fun theOldPrefixIsFoundWhereverItStands() {
        val p = "s" + "b_"
        listOf("${p}overview_widget_info.xml", "widget_${p}services", "R.id.${p}title", "${p.uppercase()}OVERVIEW_REFRESH")
            .forEach { assertTrue(it, says(it) != null) }
        listOf("usb_device", "box_overview", "R.id.box_title").forEach { assertTrue(it, says(it) == null) }
        assertTrue(says("until " + leftover + " is live") != null)
    }

    @Test
    fun theAppIsHaus() {
        val gradle = File(RepoFiles.root, "app/build.gradle").readText()
        assertTrue("applicationId", Regex("""applicationId\s+["']cloud\.dopp\.haus["']""").containsMatchIn(gradle))
        assertTrue("namespace", Regex("""namespace\s+["']cloud\.dopp\.haus["']""").containsMatchIn(gradle))
        val names = RepoFiles.walk(File(RepoFiles.main, "res/values")).joinToString("\n") { it.readText() }
        assertTrue("the app is called Haus", names.contains("""<string name="appName">Haus</string>"""))
    }

    @Test
    fun theOnlySchemeIsTheNewOne() {
        val manifest = File(RepoFiles.main, "AndroidManifest.xml").readText()
        val schemes = Regex("""android:scheme="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }
            .toSet() - setOf("http", "https")
        assertTrue("own schemes: $schemes", schemes == setOf("cloud.dopp.haus"))
        assertTrue(
            "the pair link opens the app",
            Regex("""android:scheme="cloud\.dopp\.haus"\s+android:host="pair"""").containsMatchIn(manifest),
        )
    }
}
