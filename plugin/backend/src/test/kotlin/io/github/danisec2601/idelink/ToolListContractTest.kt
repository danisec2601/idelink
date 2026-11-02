package io.github.danisec2601.idelink

import io.github.danisec2601.idelink.core.Approval
import io.github.danisec2601.idelink.core.OutsideGate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bridge refuses a whole list that breaks these limits. The list is also written to build/tool-list.json,
 * which bridge/tools.json copies and the bridge's tests check.
 */
class ToolListContractTest {
    @Test
    fun theRealToolListPassesTheBridgesChecks() {
        val noTransport = object : io.github.danisec2601.idelink.core.TerminalRelay.Transport {
            override fun clients(project: String) = emptySet<String>()
            override fun send(project: String, event: io.github.danisec2601.idelink.shared.TerminalRequestEvent) = Unit
        }
        val terminal = TerminalTools(io.github.danisec2601.idelink.core.TerminalRelay(noTransport)) { emptyList() }
        val gate = OutsideGate(Approval({ _, _ -> Approval.Shown.Nowhere("test") }))
        val specs = (PresentTools(EditorStateStore(), gate, io.github.danisec2601.idelink.core.Arrivals()).all() +
            AnalysisTools().all() + terminal.all()).map { it.spec() }
        assertTrue(specs.size in 1..64)
        var total = 0
        for (s in specs) {
            val name = s.get("name").asString
            val desc = s.get("description").asString
            val schema = s.get("inputSchema").toString()
            assertTrue(Regex("^[a-z][a-z0-9_]{0,63}$").matches(name), name)
            assertTrue(desc.toByteArray().size <= 4096, "$name description")
            assertTrue(schema.toByteArray().size <= 16 * 1024, "$name schema")
            assertTrue(s.getAsJsonObject("inputSchema").get("type").asString == "object", name)
            assertTrue(s.keySet() == setOf("name", "description", "inputSchema"), name)
            total += desc.toByteArray().size + schema.toByteArray().size
        }
        assertTrue(total <= 128 * 1024, "total $total")
        val out = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "tool-list.json")
        java.nio.file.Files.writeString(out, com.google.gson.JsonArray().apply { specs.forEach { add(it) } }.toString())
        println("tool list: ${specs.size} tools, $total bytes -> $out")
    }
}
