package io.github.danisec2601.idelink.core

import io.github.danisec2601.idelink.shared.SharedTerminalDto
import io.github.danisec2601.idelink.shared.TerminalAnswerDto
import io.github.danisec2601.idelink.shared.TerminalRequestEvent
import io.github.danisec2601.idelink.shared.TerminalStatus
import io.github.danisec2601.idelink.shared.TerminalText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalRelayTest {
    private val id = "0123456789abcdef"
    private val aws = "AKIAIOSFODNN7EXAMPLE"

    /** Stands in for the frontend: [onSend] runs on the request, like a client answering through the RPC. */
    private class Fake(val clientsOf: (String) -> Set<String> = { setOf("client-A") }) : TerminalRelay.Transport {
        constructor(clients: Set<String>) : this({ _: String -> clients })
        val sent = mutableListOf<TerminalRequestEvent>()
        var onSend: (TerminalRequestEvent) -> Unit = {}
        override fun clients(project: String) = clientsOf(project)
        override fun send(project: String, event: TerminalRequestEvent) { sent += event; onSend(event) }
    }

    private var clock = 1_000_000L
    private val logs = mutableListOf<String>()
    private val unknown = "unknown or already answered request"

    private fun relay(t: Fake, waitMs: Long = 2_000) = TerminalRelay(t, waitMs, { clock }, { logs += it })

    private fun answering(
        t: Fake, r: TerminalRelay, caller: String? = "client-A", a: (TerminalRequestEvent) -> TerminalAnswerDto,
    ) {
        t.onSend = { e -> assertNull(r.answer("p", e.requestId, caller, a(e))) }
    }

    @Test fun readReturnsTheLastLinesScrubbedAndPlain() {
        val t = Fake(); val r = relay(t)
        val out = (1..30).joinToString("\n") { "line $it" } + "\nkey=$aws <b>bold</b> \u001b[31m\n\n\n"
        answering(t, r) { TerminalAnswerDto(text = out, title = "zsh <i>", name = "Local <b>") }
        val o = r.read("p", id, 3)
        val text = o.get("text").asString
        assertEquals(listOf("line 29", "line 30"), text.lines().take(2))
        assertEquals(3, o.get("lines").asInt)
        assertFalse(text.contains(aws), text)
        assertTrue(text.contains("[REDACTED:aws-key:"), text)
        assertTrue(text.contains("‹b›bold‹/b›") && text.contains("⟦U+001B⟧"), text)
        assertEquals("zsh ‹i›", o.get("shell_title").asString)
        assertEquals("Local ‹b›", o.get("name").asString)
        assertEquals(1, o.getAsJsonObject("redacted").get("aws-key").asInt)
        assertEquals(id, t.sent.single().terminalId)
        assertEquals(3, t.sent.single().lines)
    }

    @Test fun placeholdersDifferPerRead() {
        val t = Fake(); val r = relay(t)
        answering(t, r) { TerminalAnswerDto(text = "k=$aws") }
        assertNotEquals(r.read("p", id, 1).get("text").asString, r.read("p", id, 1).get("text").asString)
    }

    @Test fun linesAreCappedEvenIfTheClientIgnoresTheCap() {
        val t = Fake(); val r = relay(t)
        answering(t, r) { TerminalAnswerDto(text = (1..2000).joinToString("\n") { "l$it" }) }
        val o = r.read("p", id, 100_000)
        assertEquals(TerminalText.MAX_LINES, t.sent.single().lines)
        assertEquals(TerminalText.MAX_LINES, o.get("text").asString.lines().size)
        assertEquals("l1501", o.get("text").asString.lines().first())
    }

    @Test fun textIsBoundedInCharacters() {
        val t = Fake(); val r = relay(t)
        answering(t, r) { TerminalAnswerDto(text = "x".repeat(TerminalText.MAX_CHARS * 3)) }
        val o = r.read("p", id, 1)
        assertEquals(TerminalText.MAX_CHARS, o.get("text").asString.length)
        assertEquals(TerminalText.MAX_CHARS, o.get("truncated_to_chars").asInt)
    }

    @Test fun notSharedIsReportedWithoutText() {
        val t = Fake(); val r = relay(t)
        answering(t, r) { TerminalAnswerDto(status = TerminalStatus.NOT_SHARED, text = "leaked?") }
        val o = r.read("p", id, 5)
        assertTrue(o.has("not_shared"))
        assertFalse(o.has("text"))
    }

    @Test fun listIsSanitisedAndBounded() {
        val t = Fake(); val r = relay(t)
        val tabs = listOf(SharedTerminalDto(id, "<html>x $aws"), SharedTerminalDto("../../etc", "bad id")) +
            (1..100).map { SharedTerminalDto("%016x".format(it), "t$it") }
        answering(t, r) { TerminalAnswerDto(shared = tabs, unshared = -5) }
        val o = r.list("p")
        val terms = o.getAsJsonArray("terminals")
        assertNull(t.sent.single().terminalId)
        assertEquals(TerminalRelay.MAX_TABS - 1, terms.size())
        val first = terms[0].asJsonObject.get("shell_title").asString
        assertFalse(first.contains("<") || first.contains(aws), first)
        assertFalse(terms[0].asJsonObject.has("name"))
        assertTrue(terms.none { it.asJsonObject.get("id").asString == "../../etc" })
        assertEquals(0, o.get("unshared").asInt)
    }

    @Test fun listNamesAreSanitisedAndDuplicatesKeepTheirIds() {
        val t = Fake(); val r = relay(t)
        val other = "fedcba9876543210"
        answering(t, r) {
            TerminalAnswerDto(shared = listOf(
                SharedTerminalDto(id, "~/a", "Remote (2) <i>$aws\u202E"), SharedTerminalDto(other, "~/b", "Remote (2)"),
            ))
        }
        val terms = r.list("p").getAsJsonArray("terminals").map { it.asJsonObject }
        assertEquals(listOf(id, other), terms.map { it.get("id").asString })
        val name = terms[0].get("name").asString
        assertTrue(name.startsWith("Remote (2) ‹i›[REDACTED:aws-key:") && name.endsWith("⟦U+202E⟧"), name)
        assertEquals("Remote (2)", terms[1].get("name").asString)
        assertEquals(listOf("~/a", "~/b"), terms.map { it.get("shell_title").asString })
    }

    @Test fun requestIdsAreRandomAndSingleUse() {
        val t = Fake(); val r = relay(t)
        val refusals = mutableListOf<String?>()
        t.onSend = { e ->
            refusals += r.answer("p", e.requestId, "client-A", TerminalAnswerDto(text = "first"))
            refusals += r.answer("p", e.requestId, "client-A", TerminalAnswerDto(text = "second"))
        }
        assertEquals("first", r.read("p", id, 1).get("text").asString)
        assertEquals(listOf(null, "unknown or already answered request"), refusals)
        r.read("p", id, 1)
        val (a, b) = t.sent.map { it.requestId }
        assertNotEquals(a, b)
        assertTrue(Regex("[0-9a-f]{32}").matches(a), a)
    }

    @Test fun unknownIdIsRefused() {
        val r = relay(Fake())
        assertEquals(unknown, r.answer("p", "deadbeef", "client-A", TerminalAnswerDto(text = "x")))
    }

    @Test fun aLateAnswerIsRefusedAfterTheRequesterGaveUp() {
        val t = Fake(); val r = relay(t, waitMs = 50)
        val e = assertFailsWith<BadArgs> { r.read("p", id, 1) }
        assertTrue(e.message!!.startsWith("no answer"), e.message)
        assertEquals(unknown, r.answer("p", t.sent.single().requestId, "client-A", TerminalAnswerDto(text = "x")))
    }

    @Test fun anExpiredAnswerIsRefused() {
        val t = Fake(); val r = relay(t, waitMs = 200)
        var refusal: String? = null
        t.onSend = { e ->
            clock += 201
            refusal = r.answer("p", e.requestId, "client-A", TerminalAnswerDto(text = "x"))
        }
        val e = assertFailsWith<BadArgs> { r.read("p", id, 1) }
        assertEquals("the request expired", refusal)
        assertTrue(e.message!!.contains("expired"), e.message)
    }

    @Test fun anAnswerJustInsideTheExpiryIsAccepted() {
        val t = Fake(); val r = relay(t, waitMs = 10_000)
        t.onSend = { e ->
            clock += 10_000
            assertNull(r.answer("p", e.requestId, "client-A", TerminalAnswerDto(text = "x")))
        }
        assertEquals("x", r.read("p", id, 1).get("text").asString)
    }

    @Test fun anAnswerFromAClientThatWasNotAskedIsRefusedAndLogged() {
        val t = Fake(setOf("client-A")); val r = relay(t, waitMs = 300)
        var refusal: String? = null
        t.onSend = { e -> refusal = r.answer("p", e.requestId, "client-B", TerminalAnswerDto(text = "x")) }
        val e = assertFailsWith<BadArgs> { r.read("p", id, 1) }
        assertEquals("the answer came from a client that wasn't asked", refusal)
        assertTrue(e.message!!.contains("wasn't asked"), e.message)
        assertTrue(logs.any { it.contains("matched=false") && it.contains("caller=client-B") }, logs.toString())
    }

    @Test fun anAnswerWithNoCallerIdIsRefused() {
        val t = Fake(setOf("client-A")); val r = relay(t, waitMs = 300)
        var refusal: String? = null
        t.onSend = { e -> refusal = r.answer("p", e.requestId, null, TerminalAnswerDto(text = "x")) }
        assertFailsWith<BadArgs> { r.read("p", id, 1) }
        assertEquals("the answer came from a client that wasn't asked", refusal)
    }

    @Test fun aGuestsAnswerDoesNotSpoilTheControllersAnswer() {
        val t = Fake(setOf("client-A")); val r = relay(t)
        val refusals = mutableListOf<String?>()
        t.onSend = { e ->
            refusals += r.answer("p", e.requestId, "guest", TerminalAnswerDto(text = "forged"))
            refusals += r.answer("other", e.requestId, "client-A", TerminalAnswerDto(text = "forged"))
            refusals += r.answer("p", e.requestId, "client-A", TerminalAnswerDto(text = "real"))
        }
        val read = runCatching { r.read("p", id, 1) }
        assertEquals(listOf("the answer came from a client that wasn't asked", "the answer came from another project",
            null), refusals, "refused answers must leave the request open for the real one")
        assertEquals("real", read.getOrThrow().get("text").asString)
    }

    @Test fun noControllerClientFailsAtOnceWithoutSending() {
        val t = Fake(emptySet()); val r = relay(t, waitMs = 10_000)
        val started = System.nanoTime()
        val e = assertFailsWith<BadArgs> { r.list("p") }
        assertEquals(TerminalRelay.NO_CLIENT, e.message)
        assertTrue(t.sent.isEmpty())
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "must not wait for an answer no one can give")
    }

    @Test fun resultsCarryTheLabelNotTheKey() {
        val t = Fake(); val r = relay(t)
        answering(t, r) { TerminalAnswerDto(text = "x") }
        assertEquals("idelink (wt-a)", r.read("p", id, 1, label = "idelink (wt-a)").get("project").asString)
    }

    @Test fun anAnswerForAnotherProjectIsRefused() {
        val t = Fake(); val r = relay(t, waitMs = 300)
        var refusal: String? = null
        t.onSend = { e -> refusal = r.answer("other", e.requestId, "client-A", TerminalAnswerDto(text = "x")) }
        assertFailsWith<BadArgs> { r.read("p", id, 1) }
        assertEquals("the answer came from another project", refusal)
    }

    @Test fun aMalformedTerminalIdIsRefusedBeforeAsking() {
        val t = Fake(); val r = relay(t)
        assertFailsWith<BadArgs> { r.read("p", "ABC/../x", 1) }
        assertTrue(t.sent.isEmpty())
    }

    @Test fun anAnswerFromAnotherThreadIsDelivered() {
        val t = Fake(); val r = relay(t)
        t.onSend = { e -> Thread { r.answer("p", e.requestId, "client-A", TerminalAnswerDto(text = "async")) }.start() }
        assertEquals("async", r.read("p", id, 1).get("text").asString)
    }

    @Test fun tailCapsLinesItself() {
        assertEquals(TerminalText.MAX_LINES, TerminalText.tail((1..2000).joinToString("\n"), 100_000).lines().size)
    }

    @Test fun tailDropsTrailingBlankLines() {
        assertEquals("b\nc", TerminalText.tail("a\nb\nc\n  \n\n", 2))
        assertEquals("c", TerminalText.tail("a\nb\nc", 1))
        assertEquals("a\nb", TerminalText.tail("a\nb", 10))
        assertEquals("", TerminalText.tail("\n\n", 3))
    }
}
