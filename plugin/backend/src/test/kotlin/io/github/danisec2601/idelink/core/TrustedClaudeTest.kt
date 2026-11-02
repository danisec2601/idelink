package io.github.danisec2601.idelink.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustedClaudeTest {
    private val base = Path.of(System.getProperty("user.home"), "dev/tmp/idelink-test/trusted-claude")
    private val etc = base.resolve("etc")
    private val config = etc.resolve("trusted-claude")
    private val me = LibC.getuid()

    private fun perms(p: Path, s: String) = Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(s))

    private fun write(body: String, file: String = "rw-r--r--", dir: String = "rwxr-xr-x") {
        perms(etc, "rwx------")
        Files.writeString(config, body)
        perms(config, file)
        perms(etc, dir)
    }

    private fun refusal(owner: Int = me) =
        assertFailsWith<RootList.Untrusted> { RootList.read(config, owner, "paths") }.message!!

    @BeforeTest
    fun up() {
        if (Files.exists(etc)) perms(etc, "rwx------")
        base.toFile().deleteRecursively()
        Files.createDirectories(etc)
    }

    @AfterTest
    fun down() {
        perms(etc, "rwx------")
    }

    @Test fun parsesCommentsBlanksAndCleansPaths() {
        write("# per-user\n  /home/u/.local/bin/claude  \n\n/usr//bin/./claude\n# end")
        assertEquals(listOf("/home/u/.local/bin/claude", "/usr/bin/claude"), RootList.read(config, me, "paths"))
    }

    @Test fun anAbsentConfigIsNullNotAnError() {
        assertNull(RootList.read(etc.resolve("none"), me, "paths"))
    }

    @Test fun eachOwnershipAndModeRuleRefuses() {
        write("/usr/bin/claude\n")
        assertTrue("is owned by uid $me, not root" in refusal(owner = me + 1))
        for ((file, dir) in listOf("rw-rw-r--" to "rwxr-xr-x", "rw-r--rw-" to "rwxr-xr-x")) {
            write("/usr/bin/claude\n", file, dir)
            assertEquals("$config is writable by group or others (0${if (file[4] == 'w') "664" else "646"})", refusal())
        }
        for (dir in listOf("rwxrwxr-x", "rwxr-xrwx")) {
            write("/usr/bin/claude\n", dir = dir)
            assertTrue(refusal().startsWith("$etc is writable by group or others"), refusal())
        }
    }

    @Test fun badEntriesRefuse() {
        for ((body, want) in listOf(
            "bin/claude\n" to "line 1: \"bin/claude\" is not an absolute path",
            "# c\n~/.local/bin/claude\n" to "line 2: \"~/.local/bin/claude\" is not an absolute path",
            "# nothing\n\n" to "lists no paths",
            "/x\n".repeat(RootList.MAX_BYTES) to "is over ${RootList.MAX_BYTES} bytes",
        )) {
            write(body)
            assertTrue(want in refusal(), "$want: ${refusal()}")
        }
    }

    @Test fun notARegularFileRefusesWithoutBlocking() {
        write("/usr/bin/claude\n")
        perms(etc, "rwx------")
        val link = etc.resolve("link"); Files.createSymbolicLink(link, config)
        val fifo = etc.resolve("fifo"); ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()
        val sub = etc.resolve("sub"); Files.createDirectory(sub)
        perms(etc, "rwxr-xr-x")
        val cases = listOf(link to "too many levels of symbolic links", fifo to "not a regular file",
            sub to "not a regular file")
        for ((p, want) in cases) {
            var msg: String? = null
            val t = Thread { msg = runCatching { RootList.read(p, me, "paths") }.exceptionOrNull()?.message }
            t.isDaemon = true; t.start(); t.join(5_000)
            assertTrue(!t.isAlive, "reading $p blocked")
            assertTrue(msg?.contains(want) == true, "$p: want $want, got $msg")
        }
    }

    @Test fun aSymlinkedOrUnreadableDirectoryRefuses() {
        write("/usr/bin/claude\n")
        val linked = base.resolve("etc-link"); Files.createSymbolicLink(linked, etc)
        assertTrue("$linked is not a directory" in assertFailsWith<RootList.Untrusted> {
            RootList.read(linked.resolve("trusted-claude"), me, "paths")
        }.message!!)
        if (me != 0) {
            perms(etc, "---------")
            assertTrue("permission denied" in refusal(), refusal())
        }
    }

    @Test fun absentConfigTrustsTheDefaultOnly() {
        val t = TrustedClaude(etc.resolve("none"), me, Path.of("/usr/bin/env"))
        val m = assertIs<TrustedClaude.Match.Trusted>(t.match("/usr/bin/env"))
        assertEquals(false, m.listed)
        assertNull(m.userWritable)
        assertIs<TrustedClaude.Match.Refused>(t.match("/home/u/.local/bin/claude"))
    }

    @Test fun aPresentConfigReplacesTheDefaultAndMatchesExactly() {
        write("/home/u/.local/bin/claude\n")
        val t = TrustedClaude(config, me, Path.of("/usr/bin/env"))
        assertIs<TrustedClaude.Match.Trusted>(t.match("/home/u/.local/bin/claude")).also { assertTrue(it.listed) }
        for (exe in listOf("/home/u/.local/bin/claude-evil", "/home/u/.local/bin", "/home/u/.local/bin/claude/x",
            "/home/u/.local/bin/claude (deleted)", "/usr/bin/env")) {
            assertIs<TrustedClaude.Match.Refused>(t.match(exe), exe)
        }
    }

    @Test fun aTrailingSlashKeepsADirectoryEntryAndOnlyThere() {
        write("/a/versions/\n/a//b/./c//\n/a/file\n/a/dot/.\n")
        assertEquals(listOf("/a/versions/", "/a/b/c/", "/a/file", "/a/dot"), RootList.read(config, me, "paths"))
    }

    @Test fun aDirectoryEntryMatchesOnlyBinariesDirectlyInside() {
        write("/a/versions/\n")
        val t = TrustedClaude(config, me, Path.of("/usr/bin/env"))
        assertIs<TrustedClaude.Match.Trusted>(t.match("/a/versions/2.1.0")).also {
            assertTrue(it.listed)
            assertEquals(Path.of("/a/versions/2.1.0"), it.path)
        }
        for (exe in listOf("/a/versions-evil/x", "/a/versionsx", "/a/versions/sub/x", "/a/versions", "/a/x",
            "/usr/bin/env")) {
            assertIs<TrustedClaude.Match.Refused>(t.match(exe), exe)
        }
    }

    @Test fun withoutTheTrailingSlashADirectoryIsAnExactPathThatMatchesNoBinary() {
        write("/a/versions\n")
        val t = TrustedClaude(config, me, Path.of("/usr/bin/env"))
        assertIs<TrustedClaude.Match.Refused>(t.match("/a/versions/2.1.0"))
    }

    @Test fun aBinaryUnderAUserWritableDirectoryEntryIsFlagged() {
        val versions = Files.createDirectories(base.resolve("versions"))
        val bin = Files.writeString(versions.resolve("2.1.0"), "x")
        write("$versions/\n")
        val m = assertIs<TrustedClaude.Match.Trusted>(TrustedClaude(config, me, Path.of("/usr/bin/env")).match("$bin"))
        assertNotNull(m.userWritable)
        assertEquals("$versions is owned by uid $me", TrustedClaude.userWritable(versions))
    }

    @Test fun anUntrustedConfigRefusesEvenItsOwnEntries() {
        write("/usr/bin/env\n", file = "rw-rw-r--")
        val env = Path.of("/usr/bin/env")
        val r = assertIs<TrustedClaude.Match.Refused>(TrustedClaude(config, me, env).match(env.toString()))
        assertTrue("writable by group or others" in r.reason, r.reason)
        assertTrue("no Claude Code binary is trusted" in r.reason, r.reason)
    }

    @Test fun userWritableLooksAtTheFileAndEveryDirectoryAbove() {
        assertNull(TrustedClaude.userWritable(Path.of("/usr/bin/env")))
        val mine = base.resolve("claude"); Files.writeString(mine, "x")
        assertEquals("$mine is owned by uid $me", TrustedClaude.userWritable(mine))
        assertNotNull(TrustedClaude.userWritable(Path.of("/tmp")), "a sticky world-writable dir is not root-only")
    }

    @Test fun theRefusalSaysWhatWhereAndTheTradeoffInFourLinesAtMost() {
        val seen = "/home/u/.local/share/claude/versions/2.1.0"
        val r = TrustedClaude.refusal(seen)
        val lines = r.lines()
        assertTrue(lines.size <= 4, r)
        assertTrue("$seen, which is not a trusted Claude Code binary" in lines[0], r)
        assertTrue("as root, add the line $seen to /etc/idelink/trusted-claude" in r, r)
        assertTrue("root-owned and not group/other-writable" in r, r)
        assertTrue("a Claude binary your account can write can be replaced by any process running as you, so the " +
            "pairing prompt becomes the only protection" in r, r)
    }

    @Test fun anInterpreterIsNeverSuggestedForTheTrustList() {
        for (exe in listOf("/usr/bin/node", "/home/u/.nvm/versions/node/v22.1.0/bin/node", "/usr/bin/python3.12",
                "/bin/bash", "/usr/bin/sh", "/home/u/.bun/bin/bun", "/usr/bin/deno", "/usr/bin/zsh", "/usr/bin/yash",
                "/usr/bin/node22", "/usr/bin/nodejs22", "/usr/bin/java21")) {
            val r = TrustedClaude.refusal(exe)
            assertFalse("add the line" in r, r)
            assertTrue("trusting an interpreter trusts every script it runs" in r && "apt/dnf/apk" in r, r)
            assertTrue(r.lines().size <= 4, r)
        }
        for (exe in listOf("/usr/bin/claude", "/home/u/.local/share/claude/versions/2.1.0", "/opt/nodeapp/claude",
                "/usr/bin/yashsh")) {
            assertFalse(TrustedClaude.isInterpreter(exe), exe)
        }
    }

    @Test fun theRefusalMakesTheSeenPathPlain() {
        val r = TrustedClaude.refusal("/home/u/<html>x\nInjected line‮", "why\nnot")
        assertEquals(3, r.lines().size, r)
        assertTrue('<' !in r && "⟦U+202E⟧" in r, r)
    }
}
