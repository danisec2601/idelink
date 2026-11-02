package io.github.danisec2601.idelink.core

/** Who may call the backend's RPCs: only a project's controller clients, never a Code With Me guest. */
internal object ControllerCheck {
    /** True when [caller] (the RPC's ClientId, null if unset) is one of [controllers]. */
    fun accepts(caller: String?, controllers: Set<String>): Boolean = caller != null && caller in controllers
}
