package io.github.danisec2601.idelink.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are known fakes or assembled at runtime from fragments, so no secret-shaped literal sits in this file.
 */
class ScrubTest {
    private val aws = "AKIAIOSFODNN7EXAMPLE"
    private val anthropic = "sk-ant-api03-abcdefghij1234567890"
    private val github = "ghp_EXAMPLE0000000000000000000000000000000"
    private val slack = "xoxb-EXAMPLE-000000000000"
    private val google = "AIzaSyEXAMPLE00000000000000000000000000"
    private val pemHead = "-----BEGIN " + "OPENSSH PRIVATE KEY-----"
    private val pemTail = "-----END " + "OPENSSH PRIVATE KEY-----"
    private val pemBody = "b3BlbnNzaC1rZXktdjEAAAAA" + "\n" + "QUJDREVGR0hJSktMTU5PUA=="
    private val age = "AGE-SECRET-" + "KEY-1" + "QQQQ".repeat(14) + "QQ"
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    private val jwt = b64("{\"alg\":\"HS256\"}") + "." + b64("{\"sub\":\"test-only\"}") + "." + b64("not-a-real-signature")

    private fun scrubbed(s: String) = Scrub.scrub(s)

    @Test fun eachShapeIsReplaced() {
        for ((kind, v) in listOf("aws-key" to aws, "anthropic-key" to anthropic, "github-token" to github,
                "slack-token" to slack, "google-key" to google, "age-key" to age, "jwt" to jwt)) {
            val r = scrubbed("x = '$v'\n")
            assertFalse(r.text.contains(v), "$kind not scrubbed: ${r.text}")
            assertTrue(r.text.contains("[REDACTED:$kind:"), "$kind: ${r.text}")
            assertEquals(1, r.redactions[kind], kind)
        }
    }

    @Test fun multiLinePemIsRemovedWhole() {
        val r = scrubbed("before\n$pemHead\n$pemBody\n$pemTail\nafter")
        assertEquals(1, r.redactions["private-key"])
        assertFalse(r.text.contains("b3BlbnNz") || r.text.contains("QUJD"), r.text)
        assertTrue(r.text.startsWith("before\n[REDACTED:private-key:") && r.text.endsWith("]\nafter"), r.text)
    }

    @Test fun pemWithoutItsEndLineIsRemovedToTheEnd() {
        val r = scrubbed("k = '''\n$pemHead\n$pemBody")
        assertFalse(r.text.contains("QUJD"), r.text)
    }

    private val basic = "Basic " + b64("t" + "e" + "st:" + "te" + "st")
    private val bearerTok = "0".repeat(8) + "f".repeat(8)

    @Test fun authorizationAndBearerValues() {
        val r = scrubbed("Authorization: $basic\ntoken = \"Bearer $bearerTok\"")
        assertFalse(r.text.contains(basic.removePrefix("Basic ")), r.text)
        assertTrue(r.text.contains("Authorization: [REDACTED:authorization:"), r.text)
        assertFalse(r.text.contains(bearerTok), r.text)
    }

    @Test fun quotedAuthorizationInPythonOrJson() {
        val r = scrubbed("headers = {\"Authorization\": \"$basic\"}")
        assertFalse(r.text.contains(basic.removePrefix("Basic ")), r.text)
    }

    @Test fun pgpPrivateKeyBlockAndSlackAppToken() {
        val pgp = "-----BEGIN PGP " + "PRIVATE KEY BLOCK-----\n" + pemBody + "\n-----END PGP " + "PRIVATE KEY BLOCK-----"
        assertFalse(scrubbed(pgp).text.contains("QUJD"))
        val app = "xa" + "pp-" + "1-" + "0".repeat(12)
        assertFalse(scrubbed("t = '$app'").text.contains(app))
    }

    @Test fun placeholdersAreRandomPerReadButStableWithinOne() {
        val a = scrubbed("$aws $aws").text.split(' ')
        val b = scrubbed(aws).text
        assertEquals(a[0], a[1])
        assertNotEquals(a[0], b)
    }

    @Test fun ordinaryCodeIsUntouched() {
        val code = "def bearer_of(x):\n    return authorize(x)  # eyJ is how JWTs start\nkey = cfg['api_key']\n"
        val r = scrubbed(code)
        assertEquals(code, r.text)
        assertTrue(r.redactions.isEmpty())
    }

    @Test fun bareHexIsAKnownMiss() {
        // Documented limit: a bare hex/UUID secret has no recognisable shape.
        val hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(hex, scrubbed("SECRET=$hex").text.removePrefix("SECRET="))
    }

    @Test fun aSymlinkAliasCantLaunderASecretName() {
        // .env -> config/app.txt: the real path is innocuous, the name the editor shows is not.
        assertNotNull(Scrub.secretBearingAny("/p/.env", "/p", "config/app.txt"))
        assertNotNull(Scrub.secretBearingAny("/p/config/app.txt", "/p", "keys/id_rsa"))
        assertNull(Scrub.secretBearingAny("/p/config/app.txt", "/p", "config/app.txt"))
        // Path components above the root (a project under ~/secret-stuff/) don't withhold everything.
        assertNull(Scrub.secretBearingAny("/home/d/secret-stuff/p/main.py", "/home/d/secret-stuff/p", "main.py"))
    }

    @Test fun secretBearingNames() {
        for (p in listOf(".env", ".env.local", "deploy/server.PEM", "keys/tls.key", "a.p12", "b.pfx", "id_rsa",
                "id_ed25519.pub", "conf/secrets/db.yaml", "aws_credentials", ".netrc", "x/.pgpass",
                "tmp/test.env", "prod.ENV", "deploy/prod.env.local")) {
            assertNotNull(Scrub.secretBearingName(p), p)
        }
        for (p in listOf("main.py", "environment.py", "src/keyboard.py", "docs/README.md", "config.yaml",
                "venv/bin/activate", ".venv/lib/x.py", "env.py", "src/dotenv_loader.py")) {
            assertNull(Scrub.secretBearingName(p), p)
        }
    }

    /** Inputs that make a backtracking matcher retry long runs from many starts, one or more per pattern. */
    private val adversarial = listOf(
        "ey-", "eyA", "eyAAAAAAAA.", "eyAAAAAAAA.eyAAAAAAAA.", " eyAAAAAAAAAA.eyAAAAAAAAAA", "ey_ey-",
        "-----BEGIN ", "-----BEGIN A", "$pemHead\n-----END A", "-----END ", "PRIVATE KEY ",
        "AKIA", "ASIAAAAA", " ghp_" + "a".repeat(40) + "_", "ghp_a", "github_pat_", "xoxb-", "xapp-", "AIza-", "AIzaA",
        "sk-ant-", "AGE-SECRET-KEY-1", "authorization", "authorization ", "Authorization: \"", "bearer ", "bearer  ",
        " ", "\n", "-", "a", "_", ".",
    )

    @Test fun scrubIsNearLinearOnAdversarialInput() {
        val cap = 256 * 1024
        val slow = mutableListOf<String>()
        Scrub.scrub("warm-up " + jwt + aws)
        for (unit in adversarial + listOf("authorization" + " ".repeat(cap), "bearer " + " ".repeat(cap) + "x",
                "-----BEGIN " + "A".repeat(cap), " ghp_" + "a".repeat(cap) + "_", "ey" + "-".repeat(cap) + ".")) {
            val input = unit.repeat(cap / unit.length + 1).take(cap)
            val t0 = System.nanoTime()
            Scrub.scrub(input)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms > 1500) slow += "${unit.take(30)}: $ms ms"
        }
        assertTrue(slow.isEmpty(), "scrub of a 256K input over budget: $slow")
    }
}
