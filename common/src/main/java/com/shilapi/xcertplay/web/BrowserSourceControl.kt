package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.projection.*
import org.json.JSONObject

/** The browser can execute only actions advertised for an exact source/lifecycle snapshot. */
internal class BrowserSourceControl(private val runtime: ProjectionRuntime) {
    data class Selection(val id: String, val name: String, val action: String = "connect", val notice: String? = null)
    private var selection: Selection? = null
    private var start: ((String) -> Unit)? = null
    private var revision = 0L
    private var fingerprint: List<Any?> = emptyList()
    private var lastStartAt = Long.MIN_VALUE

    @Synchronized fun select(value: Selection) { selection = value }
    @Synchronized fun foreground(handler: ((String) -> Unit)?) { start = handler }

    @Synchronized fun snapshot(): JSONObject {
        val runtimeState = runtime.controlSnapshot()
        val state = runtimeState.state
        val occupied = runtimeState.occupied
        val active = runtimeState.session
        val selected = selection
        val blocked = state.cleanup == ProjectionCleanup.UNCONFIRMED
        val phase = if (occupied) state.phase.name.lowercase() else "idle"
        val reconfiguring = runtimeState.reconfiguring
        val action = when {
            blocked || phase == "stopping" && !reconfiguring -> ""
            occupied -> "stop"
            selected == null || selected.action == "unavailable" -> ""
            start == null -> ""
            else -> selected.action
        }
        val generation = runtimeState.generation
        val key = listOf(reconfiguring, generation, selected, active, state.source, phase, state.cleanup, action, start != null)
        if (key != fingerprint) { fingerprint = key; revision++ }
        return JSONObject().put("version", 1).put("revision", revision).put("generation", generation)
            .put("notice", selected?.notice ?: JSONObject.NULL)
            .put("selectedId", selected?.id ?: JSONObject.NULL).put("selectedName", selected?.name ?: JSONObject.NULL)
            .put("activeId", if (occupied) state.source?.id ?: JSONObject.NULL else JSONObject.NULL)
            .put("activeName", if (occupied) state.source?.displayName ?: JSONObject.NULL else JSONObject.NULL)
            .put("reconfiguring", reconfiguring).put("phase", phase).put("cleanup", state.cleanup.name.lowercase()).put("action", action)
            .put("needsForeground", !occupied && selected != null && selected.action != "unavailable" && start == null)
            .put("unavailable", selected == null || selected.action == "unavailable")
    }

    /** Called on main so native navigation and lifecycle callbacks remain ordered. */
    @Synchronized fun execute(action: String, sourceId: String, expectedRevision: Long, now: Long, reply: (String) -> Unit) {
        val current = snapshot()
        val target = current.optString("activeId").takeUnless { it == "null" } ?: current.optString("selectedId")
        if (current.getLong("revision") != expectedRevision || target != sourceId ||
            action.isEmpty() || action != current.getString("action")) { reply("stale"); return }
        if (action == "stop") {
            runtime.stopIfGeneration(current.getLong("generation")).whenComplete { _, failure -> reply(if (failure == null) "stopped" else "failed") }
        } else {
            if (lastStartAt != Long.MIN_VALUE && now - lastStartAt < 3000) { reply("pending"); return }
            lastStartAt = now
            try { start!!.invoke(sourceId); reply(if (action == "configure") "configure" else "requested") }
            catch (_: Exception) { reply("failed") }
        }
    }
}
