package io.github.danisec2601.idelink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageAnalysisTest {
    private val at = AnalysisIde.Location(null, 0, null, false, false, null)
    private val generic = AnalysisIde.Symbol("function", "Serve", null, "func Serve(addr string) error {", null, at)
    private val goSdk = AnalysisIde.Sdk("Go 1.24.4", "go1.24.4", "Go SDK")
    private val python = AnalysisIde.Sdk("Python 3.12 (.venv)", "Python 3.12.3", "Python SDK")

    @Test fun withoutAProviderTheSymbolIsTheGenericOne() {
        val s = withExtras(generic, emptyList())
        assertEquals(generic, s)
        assertNull(s.doc)
        assertNull(withExtras(generic, listOf(SymbolExtras())).doc)
    }

    @Test fun aProviderFillsItsFieldsAndLeavesTheRest() {
        val extras = listOf(SymbolExtras(qualifiedName = "pkg.f", doc = "Does f."), SymbolExtras(header = "def f():"))
        val s = withExtras(generic, extras)
        assertEquals("pkg.f", s.qualifiedName)
        assertEquals("Does f.", s.doc)
        assertEquals("def f():", s.header)
        assertEquals("Serve", s.name)
        assertEquals("function", s.kind)
        val two = listOf(SymbolExtras(doc = "Does f."), SymbolExtras(doc = "second"))
        assertEquals("Does f.", withExtras(generic, two).doc)
    }

    @Test fun withoutAProviderSdksAreThePlatformOnes() {
        assertEquals(goSdk, moduleSdk(emptyList(), goSdk))
        assertEquals(goSdk, projectSdk(goSdk) { emptyList() })
        assertNull(moduleSdk(emptyList(), null))
        assertNull(projectSdk(null) { emptyList() })
    }

    @Test fun aProviderSdkWinsForModulesButOnlyFillsAGapForTheProject() {
        assertEquals(python, moduleSdk(listOf(null, python), goSdk))
        assertEquals(goSdk, moduleSdk(listOf(null), goSdk))
        assertEquals(goSdk, projectSdk(goSdk) { error("asked a language although the project has an SDK") })
        assertEquals(python, projectSdk(null) { listOf(python) })
    }
}
