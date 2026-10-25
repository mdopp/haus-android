package cloud.dopp.haus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How a build reaches a phone while the app is not on Play (#182): every
 * signed build carries a growing versionCode, and a build of main is a
 * pre-release that never becomes the latest release.
 */
class ReleaseTest {

    private val gradle by lazy { File(RepoFiles.root, "app/build.gradle").readText() }
    private val workflow by lazy { File(RepoFiles.root, ".github/workflows/android-build.yml").readText() }

    @Test
    fun theVersionComesFromCi() {
        assertFalse("a fixed versionCode", Regex("""versionCode\s+\d""").containsMatchIn(gradle))
        assertTrue("versionCode from HAUS_VERSION_CODE", gradle.contains("System.getenv(\"HAUS_VERSION_CODE\")"))
        assertTrue("versionName from HAUS_VERSION_NAME", gradle.contains("System.getenv(\"HAUS_VERSION_NAME\")"))
        assertTrue("the code is the run number", workflow.contains("HAUS_VERSION_CODE=\$GITHUB_RUN_NUMBER"))
    }

    @Test
    fun aDevBuildIsAPreReleaseNeverLatest() {
        assertTrue("prerelease", workflow.contains("prerelease: true"))
        assertTrue("not latest", workflow.contains("make_latest: false"))
        assertTrue("a dev tag starts no run", workflow.contains("'!v*-dev.*'"))
    }
}
