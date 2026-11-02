package io.github.danisec2601.idelink.core

import io.github.danisec2601.idelink.shared.ArrivalKind
import io.github.danisec2601.idelink.shared.ArrivalOutcome
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * The backend half of the client's arrival gate: briefly awaits the client's routing report, and holds each
 * project's latest open_file offer. Reports are untrusted hints: they change the tool result, never what opens.
 */
internal class Arrivals<T : Any>(private val waitMs: Long = WAIT_MS, private val log: (String) -> Unit = {}) {
    /** How the client routed an arrival, as far as the backend learned within the wait. */
    enum class Routed { DIRECT, ALLOWED, ASKED, NO_REPLY }

    private data class Key(val kind: ArrivalKind, val id: Long)
    private class Pending(val project: String, val userAllowed: Boolean) { val routed = CompletableFuture<Routed>() }
    private data class Offer<T>(val id: Long, val value: T)

    // Seeded from the clock so an offer still on screen from before a backend restart can't match a new id.
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val pending = ConcurrentHashMap<Key, Pending>()
    private val offers = ConcurrentHashMap<String, Offer<T>>()

    /** A fresh id for an arrival that keeps no offer. */
    fun newId(): Long = nextId.incrementAndGet()

    /** Stores [value] as [project]'s current offer, replacing the previous one; returns its id. */
    fun offer(project: String, value: T): Long = nextId.incrementAndGet().also { offers[project] = Offer(it, value) }

    /** The value of [project]'s current offer if it is [id], removing it so one answer opens it at most once. */
    fun take(project: String, id: Long): T? {
        val o = offers[project]?.takeIf { it.id == id } ?: return null
        return if (offers.remove(project, o)) o.value else null
    }

    /**
     * Runs [send] and waits up to the configured time for the client's first routing report on [id]. An ALLOWED
     * report counts only when [userAllowed], i.e. the user clicked Allow once for this very call.
     */
    fun send(project: String, kind: ArrivalKind, id: Long, userAllowed: Boolean, send: () -> Unit): Routed {
        val key = Key(kind, id)
        val p = Pending(project, userAllowed)
        pending[key] = p
        try {
            send()
            return p.routed.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return Routed.NO_REPLY
        } finally {
            pending.remove(key, p)
        }
    }

    /** Records the client's [outcome] for arrival [id]; returns why it was ignored, or null. */
    fun report(project: String, kind: ArrivalKind, id: Long, outcome: ArrivalOutcome): String? {
        log("idelink: arrival $kind $id: $outcome")
        if (outcome == ArrivalOutcome.DECLINED && kind == ArrivalKind.OPEN) offers.computeIfPresent(project) { _, o ->
            o.takeUnless { it.id == id }
        }
        val routed = when (outcome) {
            ArrivalOutcome.DIRECT -> Routed.DIRECT
            ArrivalOutcome.ALLOWED -> Routed.ALLOWED
            ArrivalOutcome.ASKED -> Routed.ASKED
            else -> null
        }
        val p = pending[Key(kind, id)]
        return when {
            routed == null -> null
            p == null -> "no tool call is waiting for this arrival"
            p.project != project -> "the report came from another project"
            routed == Routed.ALLOWED && !p.userAllowed -> "the user didn't allow this arrival"
            else -> null.also { p.routed.complete(routed) }
        }
    }

    companion object {
        /** How long a tool call waits for the client's routing report. */
        const val WAIT_MS = 3_000L
    }
}
