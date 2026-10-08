package com.openminis.app.scheduled

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One fire, durably held until the target chat can accept it. */
data class ScheduledDelivery(
    val task: ScheduledTask,
    val sessionId: String,
    val executionId: String,
    val firedAt: Long,
    val scheduledFire: Boolean,
    val running: Boolean = false,
    val superseded: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("task", task.toJson()).put("sessionId", sessionId).put("executionId", executionId)
        .put("firedAt", firedAt).put("scheduledFire", scheduledFire).put("running", running)
        .put("superseded", superseded)

    companion object {
        fun fromJson(o: JSONObject) = ScheduledDelivery(
            ScheduledTask.fromJson(o.getJSONObject("task")), o.getString("sessionId"),
            o.getString("executionId"), o.getLong("firedAt"), o.optBoolean("scheduledFire"),
            o.optBoolean("running"), o.optBoolean("superseded"),
        )
    }
}

/** The persistence callback must finish successfully before state is published. */
class ScheduledDeliveryQueue(initial: String?, private val persist: (String) -> Unit) {
    private val _deliveries = MutableStateFlow(initial?.let { raw ->
        val array = JSONArray(raw)
        (0 until array.length()).map { ScheduledDelivery.fromJson(array.getJSONObject(it)) }
    }.orEmpty())
    val deliveries = _deliveries.asStateFlow()

    private fun save(next: List<ScheduledDelivery>) {
        persist(JSONArray(next.map { it.toJson() }).toString())
        _deliveries.value = next
    }

    @Synchronized
    fun enqueue(delivery: ScheduledDelivery) {
        if (_deliveries.value.any { it.executionId == delivery.executionId }) return
        // Keep tombstones until the runner durably records Cancelled. A crash
        // between replacement and history persistence must not lose that record.
        save(_deliveries.value.map {
            if (!it.running && it.task.id == delivery.task.id && it.sessionId == delivery.sessionId)
                it.copy(superseded = true) else it
        } + delivery)
    }

    @Synchronized
    fun claim(executionId: String): ScheduledDelivery? {
        val current = _deliveries.value.firstOrNull {
            it.executionId == executionId && !it.running && !it.superseded
        } ?: return null
        // Admission may overlap; the session VM serializes actual model/tool work.
        val claimed = current.copy(running = true)
        save(_deliveries.value.map { if (it.executionId == executionId) claimed else it })
        return claimed
    }

    /** No prompt has been sent yet, so losing admission can safely defer the fire. */
    @Synchronized
    fun defer(executionId: String) {
        save(_deliveries.value.map { if (it.executionId == executionId) it.copy(running = false) else it })
    }

    @Synchronized
    fun finish(executionId: String) {
        save(_deliveries.value.filterNot { it.executionId == executionId })
    }
}
