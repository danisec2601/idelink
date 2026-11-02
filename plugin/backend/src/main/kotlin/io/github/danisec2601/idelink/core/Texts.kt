package io.github.danisec2601.idelink.core

import io.github.danisec2601.idelink.shared.Invisible
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object Texts {
    const val MAX_FILE = 1024 * 1024

    class FileText(val text: String, val sha256: String)

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Strict UTF-8: a file that isn't valid UTF-8 is refused rather than shown with replacement characters. */
    fun decode(b: ByteArray, what: String): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) { throw BadArgs("$what is not valid UTF-8 text") }

    fun readFile(p: Path): FileText {
        val b = Files.newInputStream(p).use { it.readNBytes(MAX_FILE + 1) }
        if (b.size > MAX_FILE) throw BadArgs("file is larger than ${MAX_FILE / 1024} KiB")
        return FileText(decode(b, "the file"), sha256(b))
    }

    /** One-line summary for a diff side's title and for Claude's result; null if the side is clean. */
    fun invisibleWarning(text: String): String? {
        val n = Invisible.count(text)
        if (n == 0) return null
        val first = Invisible.scan(text, 5).joinToString(", ")
        return "⚠ $n bidi/invisible character${if (n == 1) "" else "s"}: $first${if (n > 5) ", …" else ""}"
    }

    /** A git revision without option syntax or path syntax: no leading '-', no ':', no whitespace. */
    private val ref = Regex("""[A-Za-z0-9][A-Za-z0-9._/@{}~^-]{0,199}""")

    fun checkRef(r: String): String {
        if (!ref.matches(r) || r.contains("..")) throw BadArgs("from_ref must be a plain git revision (branch, tag, sha, HEAD~n)")
        return r
    }

    private val gitEnv = listOf("PATH", "HOME", "XDG_CONFIG_HOME")

    /**
     * [rel] at [rev] via `git cat-file blob <rev>:./<rel>`, fixed argv, run by [git]. Lazy fetch is off by flag, so
     * a promisor remote never runs; git before 2.45 rejects `--no-lazy-fetch`, and that is the refusal.
     */
    fun gitBlob(root: Path, rev: String, rel: String, git: String = "git"): String {
        val pb = ProcessBuilder(git, "--no-pager", "--no-lazy-fetch", "-C", root.toString(), "-c", "core.fsmonitor=",
            "-c", "protocol.allow=never", "cat-file", "blob", "--end-of-options", "${checkRef(rev)}:./$rel")
        pb.environment().apply {
            val keep = gitEnv.mapNotNull { k -> get(k)?.let { k to it } }
            clear(); keep.forEach { (k, v) -> put(k, v) }
            put("LC_ALL", "C"); put("GIT_TERMINAL_PROMPT", "0"); put("GIT_OPTIONAL_LOCKS", "0")
            put("GIT_NO_LAZY_FETCH", "1")
        }
        val proc = pb.start()
        proc.outputStream.close()
        val out = CompletableFuture.supplyAsync { proc.inputStream.readNBytes(MAX_FILE + 1) }
        val err = CompletableFuture.supplyAsync { proc.errorStream.readNBytes(512) }
        if (!proc.waitFor(10, TimeUnit.SECONDS)) { proc.destroyForcibly(); throw BadArgs("git timed out") }
        val bytes = out.get(5, TimeUnit.SECONDS)
        if (bytes.size > MAX_FILE) throw BadArgs("file at $rev is larger than ${MAX_FILE / 1024} KiB")
        if (proc.exitValue() != 0) {
            val msg = String(err.get(5, TimeUnit.SECONDS)).trim()
            if (msg.startsWith("unknown option: --no-lazy-fetch")) throw BadArgs("from_ref needs git 2.45 or newer")
            throw BadArgs("git: " + msg.take(200))
        }
        return decode(bytes, "the file at $rev")
    }
}
