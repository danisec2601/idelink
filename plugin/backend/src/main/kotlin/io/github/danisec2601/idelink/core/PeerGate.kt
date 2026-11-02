package io.github.danisec2601.idelink.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Admits the trusted bridge binary: fully when its parent is one of [claudes], list-only otherwise. A HEURISTIC: it
 * can't tell this claude from its Bash commands, hooks or other sessions, nor stop ptrace; pairing is what holds.
 */
internal class PeerGate(
    private val bridgePath: Path,
    private val claudes: TrustedClaude,
    /** Applies to the bridge and the default claude; a listed claude may be user-writable. */
    private val requireRootOwned: Boolean = true,
) {
    /**
     * [claudeUserWritable] is why a non-root user could replace [claude], or null; [launchWarnings] come from
     * [LaunchFlags].
     */
    data class Peer(
        val pid: Int,
        val ppid: Int,
        val parentStart: Long,
        val parentCwd: String,
        val claude: Path? = null,
        val claudeUserWritable: String? = null,
        val launchWarnings: List<String> = emptyList(),
    )
    sealed class Verdict {
        data class Allow(val peer: Peer) : Verdict()

        /** The bridge without a trusted claude parent (e.g. its `--doctor` from a shell): list_tools only. */
        data class ListOnly(val peer: Peer, val reason: String) : Verdict()
        data class Deny(val reason: String) : Verdict()
    }

    fun check(fd: Int): Verdict {
        val (_, uid, _) = try { Sys.peerCred(fd) } catch (e: Exception) { return Verdict.Deny("peercred: ${e.message}") }
        if (uid != LibC.getuid()) return Verdict.Deny("peer uid $uid is not ours")

        val pidfd = try { Sys.peerPidfd(fd) } catch (e: Exception) { return Verdict.Deny("pidfd: ${e.message}") }
        try {
            val pid = Sys.pidOfPidfd(pidfd)
            if (pid <= 0) return Verdict.Deny("peer already exited")

            trusted(bridgePath)?.let { return Verdict.Deny("bridge binary untrusted: $it") }
            if (!sameFile(Path.of("/proc/$pid/exe"), bridgePath)) return Verdict.Deny("peer $pid is not the bridge binary")

            val (ppid, _) = stat(pid)
            val claude = try { claude(ppid) } catch (e: Exception) {
                TrustedClaude.Match.Refused("parent check failed: ${e.message}")
            }
            if (claude is TrustedClaude.Match.Refused) {
                if (!Sys.pidfdAlive(pidfd)) return Verdict.Deny("peer $pid exited during the check")
                return Verdict.ListOnly(Peer(pid, ppid, 0, "?"), claude.reason)
            }
            claude as TrustedClaude.Match.Trusted
            val (_, parentStart) = stat(ppid)
            val cwd = runCatching { Files.readSymbolicLink(Path.of("/proc/$ppid/cwd")).toString() }.getOrDefault("?")
            val launch = LaunchFlags.read(ppid, pid)

            // The pidfd pins the identity: if the peer died and its pid was reused while we read /proc,
            // the pidfd no longer signals.
            if (!Sys.pidfdAlive(pidfd)) return Verdict.Deny("peer $pid exited during the check")
            return Verdict.Allow(Peer(pid, ppid, parentStart, cwd, claude.path, claude.userWritable, launch))
        } catch (e: Exception) {
            return Verdict.Deny("check failed: ${e.message}")
        } finally {
            LibC.close(pidfd)
        }
    }

    /** [ppid]'s executable matched against [claudes], then pinned by device+inode. */
    private fun claude(ppid: Int): TrustedClaude.Match {
        val proc = Path.of("/proc/$ppid/exe")
        val exe = Files.readSymbolicLink(proc).toString()
        val m = claudes.match(exe)
        return when {
            m !is TrustedClaude.Match.Trusted -> m
            !m.listed && requireRootOwned && m.userWritable != null ->
                TrustedClaude.Match.Refused(TrustedClaude.refusal(exe, m.userWritable))
            !sameFile(proc, m.path) ->
                TrustedClaude.Match.Refused(TrustedClaude.refusal(exe, "replaced since it started"))
            else -> m
        }
    }

    /** null if the binary is root-owned and not writable by group/other (so we can't swap it); else why not. */
    private fun trusted(p: Path): String? {
        if (!requireRootOwned) return null
        val a = Files.readAttributes(p, "unix:uid,mode")
        val mode = a["mode"] as Int
        return when {
            (a["uid"] as Int) != 0 -> "$p not owned by root"
            mode and "022".toInt(8) != 0 -> "$p writable by group/other"
            else -> null
        }
    }

    private fun sameFile(a: Path, b: Path): Boolean {
        val x = Files.readAttributes(a, "unix:dev,ino")
        val y = Files.readAttributes(b, "unix:dev,ino")
        return x["dev"] == y["dev"] && x["ino"] == y["ino"]
    }

    /** (ppid, starttime) from /proc/<pid>/stat; fields counted after the comm's closing paren. */
    private fun stat(pid: Int): Pair<Int, Long> {
        val s = Files.readString(Path.of("/proc/$pid/stat"))
        val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
        return f[1].toInt() to f[19].toLong()
    }
}
