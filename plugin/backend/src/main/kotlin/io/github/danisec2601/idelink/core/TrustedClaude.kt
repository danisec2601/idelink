package io.github.danisec2601.idelink.core

import io.github.danisec2601.idelink.shared.Plain
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * A list of absolute paths read with the bridge's rules for /etc/idelink/trusted-ide-dirs (bridge/trust.go); an
 * entry written with a trailing `/` keeps it.
 */
internal object RootList {
    const val MAX_BYTES = 64 shl 10
    private const val O_RDONLY = 0
    private const val O_NONBLOCK = 0x800 // x86_64 values, like the rest of Native.kt
    private const val O_NOFOLLOW = 0x20000
    private const val O_CLOEXEC = 0x80000
    private const val ENOENT = 2
    private const val EACCES = 13
    private const val ENOTDIR = 20
    private const val ELOOP = 40
    private const val CHUNK = 8192
    private val errnos = mapOf(EACCES to "permission denied", ENOTDIR to "not a directory",
        ELOOP to "too many levels of symbolic links")

    class Untrusted(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun untrusted(message: String, cause: Throwable? = null): Nothing = throw Untrusted(message, cause)

    /**
     * [config]'s entries, cleaned, or null if it doesn't exist. Throws [Untrusted] unless it is a regular file that,
     * with its directory, is owned by [ownerUid] and writable by no one else, and lists at least one absolute [noun].
     */
    fun read(config: Path, ownerUid: Int, noun: String): List<String>? {
        val fd = LibC.open(config.toString(), O_RDONLY or O_NOFOLLOW or O_NONBLOCK or O_CLOEXEC)
        if (fd < 0) {
            val e = Sys.errno()
            return if (e == ENOENT) null else untrusted("can't open $config: ${strerror(e)}")
        }
        try {
            // Stat through the fd's /proc link: the file we opened, not whatever the path names by now.
            val opened = Path.of("/proc/self/fd/$fd")
            if (!Files.isRegularFile(opened)) untrusted("$config is not a regular file")
            rootOnly(Files.readAttributes(opened, "unix:uid,mode"), ownerUid)?.let { untrusted("$config $it") }
            val parent = config.toAbsolutePath().parent ?: Path.of("/")
            val pa = try {
                Files.readAttributes(parent, "unix:uid,mode,isDirectory", NOFOLLOW_LINKS)
            } catch (e: IOException) {
                untrusted("$parent: ${e.message}", e)
            }
            if (pa["isDirectory"] != true) untrusted("$parent is not a directory")
            rootOnly(pa, ownerUid)?.let { untrusted("$parent $it") }
            return parse(config, String(readAll(fd, config), Charsets.UTF_8), noun)
        } finally {
            LibC.close(fd)
        }
    }

    private fun readAll(fd: Int, config: Path): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(CHUNK)
        while (true) {
            val n = LibC.read(fd, buf, buf.size.toLong()).toInt()
            if (n < 0) untrusted("can't read $config: ${strerror(Sys.errno())}")
            if (n == 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) untrusted("$config is over $MAX_BYTES bytes")
        }
    }

    private fun parse(config: Path, body: String, noun: String): List<String> {
        val entries = mutableListOf<String>()
        for ((i, raw) in body.split('\n').withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (!line.startsWith("/")) untrusted("$config line ${i + 1}: \"$line\" is not an absolute path")
            entries += try {
                Path.of(line).normalize().toString().let { if (line.endsWith('/') && it != "/") "$it/" else it }
            } catch (e: InvalidPathException) {
                untrusted("$config line ${i + 1}: ${e.message}", e)
            }
        }
        if (entries.isEmpty()) untrusted("$config lists no $noun")
        return entries
    }

    private fun rootOnly(a: Map<String, Any?>, ownerUid: Int): String? {
        val uid = a["uid"] as Int
        val mode = a["mode"] as Int
        return when {
            uid != ownerUid -> "is owned by uid $uid, not root"
            mode and GROUP_OTHER_WRITE != 0 -> "is writable by group or others (%04o)".format(mode and PERM_BITS)
            else -> null
        }
    }

    private fun strerror(e: Int) = errnos[e] ?: "errno $e"
}

private const val GROUP_OTHER_WRITE = 0b000_010_010
private const val PERM_BITS = 0b111_111_111_111

/**
 * Which claude binaries may start a bridge: the paths in [config] (owned by [configOwnerUid]), or [default] alone
 * while [config] doesn't exist. An entry ending in `/` matches any binary directly inside that directory.
 */
internal class TrustedClaude(
    private val config: Path = CONFIG,
    private val configOwnerUid: Int = 0,
    private val default: Path = DEFAULT,
) {
    /** The verdict on one parent executable. */
    sealed class Match {
        /** [path] matched the config if [listed]; [userWritable] says why a non-root user could replace it, or null. */
        data class Trusted(val path: Path, val listed: Boolean, val userWritable: String?) : Match()

        /** Not trusted; [reason] is what the bridge is told. */
        data class Refused(val reason: String) : Match()
    }

    /** Whether [exe], a parent's resolved executable, is trusted; [config] is read on every call. */
    fun match(exe: String): Match {
        val listed = try {
            RootList.read(config, configOwnerUid, "paths")
        } catch (e: IOException) {
            val why = Plain.text(e.message ?: e.javaClass.name, MAX_SHOWN)
            return Match.Refused("$why; until it is fixed (as root) or removed, no Claude Code binary is trusted.")
        }
        val hit = (listed ?: listOf(default.toString())).any { covers(it, exe) }
        return if (!hit) Match.Refused(refusal(exe))
        else Match.Trusted(Path.of(exe), listed != null, userWritable(Path.of(exe)))
    }

    private fun covers(entry: String, exe: String): Boolean =
        if (entry.endsWith('/')) Path.of(exe).parent?.toString() == entry.dropLast(1).ifEmpty { "/" }
        else entry == exe

    /** The config's location and wording, shared with the refusal the bridge relays. */
    companion object {
        /** Where extra claude binaries are listed. */
        val CONFIG: Path = Path.of("/etc/idelink/trusted-claude")

        /** The only claude trusted while [CONFIG] doesn't exist; it must be root-only. */
        val DEFAULT: Path = Path.of("/usr/bin/claude")

        /** The cost of listing a user-writable claude, as the refusal and the docs state it. */
        const val TRADEOFF = "a Claude binary your account can write can be replaced by any process running as you, " +
            "so the pairing prompt becomes the only protection"
        private const val MAX_SHOWN = 300

        /** Script interpreters by file name (HEURISTIC: a renamed or unlisted interpreter isn't recognised). */
        private val interpreter = Regex(
            """(?:(?:node(?:js)?|bun|deno|python|pypy|perl|ruby|php|lua|luajit|java)[0-9.]*|busybox|osascript|""" +
                """(?:ba|z|da|k|mk|c|tc|fi|a)?sh|yash)""")

        fun isInterpreter(exe: String): Boolean = interpreter.matches(exe.substringAfterLast('/'))

        /** What a bridge started by [exe] is told, [why] being the detail if [exe] matched but failed a check. */
        fun refusal(exe: String, why: String? = null): String {
            val p = Plain.text(exe, MAX_SHOWN)
            val head = "this bridge's parent runs $p, which is not a trusted Claude Code binary" +
                (why?.let { " (${Plain.text(it, MAX_SHOWN)})" } ?: "") + ".\n"
            if (isInterpreter(exe)) return head +
                "$p is a script interpreter (an npm install of Claude Code runs under node): " +
                "don't list it in $CONFIG, " +
                "since trusting an interpreter trusts every script it runs.\n" +
                "Install Claude Code from Anthropic's apt/dnf/apk package or its native installer instead."
            return head +
                "If that is your Claude Code, allow it without a system install: as root, add the line $p to $CONFIG " +
                "(the file must be root-owned and not group/other-writable), or its directory with a trailing / " +
                "if updates replace the binary.\n" +
                "Tradeoff: $TRADEOFF."
        }

        /** Why a non-root user could replace [p] (it or a directory above it isn't root-only), or null. */
        fun userWritable(p: Path): String? =
            generateSequence(p) { it.parent }.firstNotNullOfOrNull { q ->
                try {
                    val a = Files.readAttributes(q, "unix:uid,mode", NOFOLLOW_LINKS)
                    when {
                        a["uid"] as Int != 0 -> "$q is owned by uid ${a["uid"]}"
                        (a["mode"] as Int) and GROUP_OTHER_WRITE != 0 -> "$q is writable by group or others"
                        else -> null
                    }
                } catch (e: IOException) {
                    "$q: ${e.message}"
                }
            }
    }
}
