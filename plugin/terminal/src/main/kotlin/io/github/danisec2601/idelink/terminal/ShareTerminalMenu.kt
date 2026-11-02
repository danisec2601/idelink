@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.terminal

import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.ex.ActionRuntimeRegistrar
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer
import com.intellij.openapi.diagnostic.logger

private val LOG = logger<ShareTerminalMenu>()

internal const val SHARE_ACTION_ID = "idelink.ShareTerminal"

// 2026.3 has a tool window tab menu; 2026.2 puts tab actions in the tool window menu.
internal val TAB_MENU_GROUPS = listOf("ToolWindowTabContextMenu", "ToolWindowContextMenu")

/** Adds the share toggle to the first of [TAB_MENU_GROUPS] this IDE defines. */
internal class ShareTerminalMenu : ActionConfigurationCustomizer {
    override fun customize(): ActionConfigurationCustomizer.CustomizeStrategy =
        object : ActionConfigurationCustomizer.LightCustomizeStrategy {
            override suspend fun customize(actionRegistrar: ActionRuntimeRegistrar) {
                val action = actionRegistrar.getActionOrStub(SHARE_ACTION_ID) ?: return
                val group = TAB_MENU_GROUPS.firstNotNullOfOrNull(actionRegistrar::getActionOrStub)
                if (group == null) {
                    LOG.warn("idelink: no tab menu group $TAB_MENU_GROUPS; no Share with Claude")
                    return
                }
                actionRegistrar.addToGroup(group, action, Constraints.LAST)
            }
        }
}
