package io.github.danisec2601.idelink

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.danisec2601.idelink.core.ApiCheck
import io.github.danisec2601.idelink.core.Approval
import io.github.danisec2601.idelink.core.Audit
import io.github.danisec2601.idelink.core.LibC
import io.github.danisec2601.idelink.core.OutsideGate
import io.github.danisec2601.idelink.core.PeerGate
import io.github.danisec2601.idelink.core.Server
import io.github.danisec2601.idelink.core.TrustedClaude
import io.github.danisec2601.idelink.core.pairingPrompt
import java.nio.file.Path

private val LOG = logger<IdelinkService>()

/** Installed root-owned and immutable (docs/install.md step 2); the gate refuses any other bridge. */
private val BRIDGE = Path.of("/usr/local/libexec/idelink/idelink-bridge")

@Service(Service.Level.APP)
internal class IdelinkService : Disposable {
    private var server: Server? = null

    @Synchronized
    fun ensureStarted() {
        if (server != null) return
        val uid = LibC.getuid()
        val home = System.getProperty("user.home")
        val build = ApplicationInfo.getInstance().build.asString()
        val missing = ApiCheck.missing()
        if (missing.isNotEmpty()) LOG.warn("idelink: IDE build $build lacks ${missing.size} platform API(s) idelink " +
            "was built against; every tool call will fail until idelink is rebuilt for this build. " +
            "Missing: ${missing.joinToString("; ")}")
        val approval = Approval(NotificationPrompter)
        val app = ApplicationManager.getApplication()
        server = Server(
            socketPath = Path.of("/run/user/$uid/idelink/ide.sock"),
            gate = PeerGate(BRIDGE, TrustedClaude()),
            tools = PresentTools(app.getService(EditorStateStore::class.java), OutsideGate(approval),
                app.getService(ArrivalService::class.java).arrivals).all() + AnalysisTools().all() +
                app.getService(TerminalRelayService::class.java).tools().all(),
            pair = { approval.ask(pairingPrompt(it, BRIDGE)) },
            audit = Audit(Path.of(home, ".local/state/idelink/audit.log")),
            ideBuild = build,
            apiMissing = missing,
            warn = { msg, t -> LOG.warn(msg, t) },
        ).also { it.start() }
        LOG.info("idelink listening")
    }

    override fun dispose() {
        server?.close()
        server = null
    }
}

internal class IdelinkStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().getService(IdelinkService::class.java).ensureStarted()
    }
}
