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
 * The names are spelled in halves here, so this file does not find itself.
 */
class NamesTest {

    private val old = listOf(
        "sol" + "aris", // the old app, its package id cloud.dopp.<it>, its server
        "sol" + "_device_", // the old token prefix
        "/napi/" + "service" + "bay", // the old Box path
        "service" + "bay", // the old name of the Box
    )

    private fun exempt(f: File) = f.name.startsWith("CHANGELOG", ignoreCase = true)

    @Test
    fun noFileIsNamedAfterTheOldApp() {
        val hits = RepoFiles.walk().filterNot(::exempt).map(RepoFiles::relative)
            .filter { p -> old.any { p.contains(it, ignoreCase = true) } }
        assertTrue("files named after the old app:\n" + hits.take(60).joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun noFileSaysAnOldName() {
        val hits = mutableListOf<String>()
        for (f in RepoFiles.walk().filterNot(::exempt).filter(RepoFiles::isText)) {
            f.readLines().forEachIndexed { i, line ->
                old.firstOrNull { line.contains(it, ignoreCase = true) }?.let {
                    hits.add("${RepoFiles.relative(f)}:${i + 1}: $it")
                }
            }
        }
        assertTrue("${hits.size} old names left, the first:\n" + hits.take(80).joinToString("\n"), hits.isEmpty())
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
