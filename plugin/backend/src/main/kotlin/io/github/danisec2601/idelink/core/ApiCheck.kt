package io.github.danisec2601.idelink.core

/** Checks by reflection that the @Internal/@Experimental platform members idelink calls exist as expected. */
internal object ApiCheck {
    sealed class Need {
        abstract val owner: String
        abstract fun describe(): String

        data class Cls(override val owner: String) : Need() {
            override fun describe() = owner
        }

        data class Method(override val owner: String, val name: String, val params: List<String>) : Need() {
            override fun describe() = "$owner.$name(${params.joinToString(", ") { it.substringAfterLast('.') }})"
        }

        data class Ctor(override val owner: String, val params: List<String>) : Need() {
            override fun describe() = "$owner(${params.joinToString(", ") { it.substringAfterLast('.') }})"
        }

        data class Field(override val owner: String, val name: String) : Need() {
            override fun describe() = "$owner.$name"
        }
    }

    private const val PROJECT = "com.intellij.openapi.project.Project"
    private const val CLIENT_ID = "com.intellij.codeWithMe.ClientId"
    private const val FUNCTION0 = "kotlin.jvm.functions.Function0"
    private const val SERIALIZER = "kotlinx.serialization.KSerializer"
    private const val TOPIC = "com.intellij.platform.rpc.topics.ProjectRemoteTopic"
    private const val SESSION = "com.intellij.openapi.client.ClientProjectSession"
    private const val DESCRIPTOR = "fleet.rpc.RemoteApiDescriptor"

    /** Present in PyCharm 262.11879.98 and 263.7307.50; withExplicitClientId and sendToClient are @Internal. */
    val REQUIRED: List<Need> = listOf(
        Need.Method("$CLIENT_ID\$Companion", "withExplicitClientId", listOf(CLIENT_ID, FUNCTION0)),
        Need.Method("$CLIENT_ID\$Companion", "getCurrentOrNull", emptyList()),
        Need.Method("com.intellij.openapi.client.ClientSessionsManager\$Companion", "getProjectSessions",
            listOf(PROJECT, "com.intellij.openapi.client.ClientKind")),
        Need.Field("com.intellij.openapi.client.ClientKind", "CONTROLLER"),
        Need.Method(SESSION, "getClientId", emptyList()),
        Need.Method(SESSION, "getProject", emptyList()),
        Need.Method(SESSION, "isDisposed", emptyList()),
        Need.Method(SESSION, "getUserData", listOf("com.intellij.openapi.util.Key")),
        Need.Method(SESSION, "putUserData", listOf("com.intellij.openapi.util.Key", "java.lang.Object")),
        Need.Cls(TOPIC),
        Need.Method("com.intellij.platform.rpc.topics.ProjectRemoteTopicKt", "ProjectRemoteTopic",
            listOf("java.lang.String", SERIALIZER)),
        Need.Method("com.intellij.platform.rpc.topics.ProjectRemoteTopicKt", "sendToClient",
            listOf(TOPIC, PROJECT, "java.lang.Object", CLIENT_ID)),
        Need.Cls("fleet.rpc.RemoteApi"),
        Need.Cls(DESCRIPTOR),
        Need.Ctor("fleet.rpc.RpcSignature",
            listOf("java.lang.String", "[Lfleet.rpc.ParameterDescriptor;", "fleet.rpc.RemoteKind")),
        Need.Ctor("fleet.rpc.ParameterDescriptor", listOf("java.lang.String", "fleet.rpc.RemoteKind")),
        Need.Ctor("fleet.rpc.RemoteKind\$Data", listOf(SERIALIZER)),
        Need.Method("com.intellij.platform.rpc.backend.RemoteApiProvider\$Sink", "remoteApi",
            listOf(DESCRIPTOR, FUNCTION0)),
        Need.Cls("com.intellij.platform.rpc.RemoteApiProviderService"),
    )

    /** Descriptions of the [needs] that [loader] can't satisfy; empty when all are present. */
    fun missing(needs: List<Need> = REQUIRED, loader: ClassLoader = ApiCheck::class.java.classLoader): List<String> =
        needs.filter { n ->
            runCatching {
                val c = Class.forName(n.owner, false, loader)
                fun types(ps: List<String>) = ps.map { Class.forName(it, false, loader) }.toTypedArray()
                when (n) {
                    is Need.Cls -> Unit
                    is Need.Method -> c.getMethod(n.name, *types(n.params))
                    is Need.Ctor -> c.getConstructor(*types(n.params))
                    is Need.Field -> c.getField(n.name)
                }
            }.isFailure
        }.map { it.describe() }
}
