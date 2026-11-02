package io.github.danisec2601.idelink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiCheckTest {
    @Test fun everyRequiredPlatformApiExistsInTheBuildWeCompileAgainst() {
        assertEquals(emptyList(), ApiCheck.missing())
    }

    @Test fun aMissingMemberIsReportedByName() {
        val needs = ApiCheck.REQUIRED + listOf(
            ApiCheck.Need.Method("com.intellij.codeWithMe.ClientId\$Companion", "withExplicitClientId",
                listOf("java.lang.String", "java.lang.String")),
            ApiCheck.Need.Field("com.intellij.openapi.client.ClientKind", "NO_SUCH_KIND"),
            ApiCheck.Need.Cls("com.intellij.platform.rpc.topics.Gone"),
            ApiCheck.Need.Ctor("fleet.rpc.RpcSignature", emptyList()),
        )
        val missing = ApiCheck.missing(needs)
        assertEquals(4, missing.size, missing.toString())
        assertTrue(missing[0].contains("withExplicitClientId(String, String)"), missing[0])
        assertTrue(missing[1].endsWith("ClientKind.NO_SUCH_KIND") && missing[2].endsWith("Gone"), missing.toString())
    }
}
