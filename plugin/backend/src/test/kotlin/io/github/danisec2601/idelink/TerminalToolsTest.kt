package io.github.danisec2601.idelink

import com.google.gson.JsonObject
import io.github.danisec2601.idelink.core.BadArgs
import io.github.danisec2601.idelink.core.TerminalRelay
import io.github.danisec2601.idelink.shared.TerminalAnswerDto
import io.github.danisec2601.idelink.shared.TerminalRequestEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalToolsTest {
    /** Project key "k1" has a controller client that answers; "k2" has none. */
    private class Transport : TerminalRelay.Transport {
        lateinit var relay: TerminalRelay
        val sentTo = mutableListOf<String>()
        override fun clients(project: String) = if (project == "k1") setOf("c1") else emptySet()
        override fun send(project: String, event: TerminalRequestEvent) {
            sentTo += project
            relay.answer(project, event.requestId, "c1", TerminalAnswerDto(unshared = 2))
        }
    }

    private val transport = Transport()
    private val relay = TerminalRelay(transport, waitMs = 5_000).also { transport.relay = it }
    private val projects = listOf(TerminalTools.Proj("idelink (a)", "k1"), TerminalTools.Proj("idelink (b)", "k2"))
    private val tools = TerminalTools(relay) { projects }.all().associateBy { it.name }

    private fun call(tool: String, args: JsonObject = JsonObject()) = tools.getValue(tool).handler(args).asJsonObject

    @Test
    fun listWithoutProjectReportsEachProjectEvenWhenOneHasNoClient() {
        val r = runCatching { call("list_terminals") }
        assertTrue(r.isSuccess, "one project without a client must not fail the whole list: ${r.exceptionOrNull()}")
        val ps = r.getOrThrow().getAsJsonArray("projects").map { it.asJsonObject }
        assertEquals(listOf("idelink (a)", "idelink (b)"), ps.map { it.get("project").asString })
        assertEquals(2, ps[0].get("unshared").asInt)
        assertEquals(TerminalRelay.NO_CLIENT, ps[1].get("none").asString)
        assertEquals(listOf("k1"), transport.sentTo, "a project with no controller client must not be asked")
    }

    @Test
    fun aProjectIsChosenByLabelAndAddressedByKey() {
        val args = JsonObject().apply { addProperty("project", "idelink (a)"); addProperty("id", "0123456789abcdef")
            addProperty("lines", 1) }
        assertEquals("idelink (a)", call("read_terminal", args).get("project").asString)
        assertEquals(listOf("k1"), transport.sentTo)
        val bare = JsonObject().apply { addProperty("project", "idelink") }
        val e = assertFailsWith<BadArgs> { call("list_terminals", bare) }
        assertTrue(e.message!!.contains("idelink (a), idelink (b)"), e.message)
    }
}
