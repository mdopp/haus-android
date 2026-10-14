package cloud.dopp.haus

import java.io.File

/**
 * The repository as files, for the tests that read the tree rather than run
 * code: the names (NamesTest), the paths the app calls (NapiContractTest) and
 * the manifest (ManifestTest). Gradle runs unit tests in the module directory;
 * the root is the first parent that holds `settings.gradle`.
 */
object RepoFiles {

    val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").isFile) dir = dir.parentFile
        requireNotNull(dir) { "no settings.gradle above ${File("").absolutePath}" }
    }

    val main: File get() = File(root, "app/src/main")

    /** Directories nobody reads as source: VCS, build output, IDE state. */
    private val skipDirs = setOf(".git", "build", ".gradle", ".idea", "node_modules")

    /** Every file under [dir] outside [skipDirs]. */
    fun walk(dir: File = root): List<File> =
        dir.walkTopDown()
            .onEnter { it == dir || it.name !in skipDirs }
            .filter { it.isFile }
            .toList()

    /** A file with a NUL byte in its first 8 KB is binary (png, jar). */
    fun isText(f: File): Boolean {
        val head = f.inputStream().use { s -> ByteArray(8192).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
        return head.none { it == 0.toByte() }
    }

    fun relative(f: File): String = f.relativeTo(root).invariantSeparatorsPath

    /** The main Kotlin sources. */
    fun mainKotlin(): List<File> = walk(File(main, "java")).filter { it.extension == "kt" }
}
