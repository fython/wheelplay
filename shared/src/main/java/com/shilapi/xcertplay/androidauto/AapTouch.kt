package com.shilapi.xcertplay.androidauto

import com.shilapi.xcertplay.projection.ProjectionContact

/** Converts snapshots into ordered Android pointer transitions. Released pointers stay in UP packets. */
class AapTouch(private val width: Int, private val height: Int) {
    data class Event(val action: Int, val index: Int, val pointers: List<ProjectionContact>)
    private var active = linkedMapOf<Int, ProjectionContact>()
    fun update(contacts: List<ProjectionContact>): List<Event> {
        require(contacts.size <= 10 && contacts.map { it.id }.distinct().size == contacts.size)
        require(contacts.all { it.id in 0..31 && it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0 })
        val next = contacts.filter { it.down }.associateBy { it.id }
        val events = mutableListOf<Event>()
        val previous = active.toMap()
        contacts.forEach { if (active.containsKey(it.id)) active[it.id] = it }
        active.keys.filter { it !in next }.forEach { id ->
            events += Event(if (active.size == 1) 1 else 6, active.keys.indexOf(id), active.values.toList())
            active.remove(id)
        }
        next.forEach { (id, point) -> if (id !in active) {
            active[id] = point
            events += Event(if (active.size == 1) 0 else 5, active.keys.indexOf(id), active.values.toList())
        } }
        if (events.isEmpty() && active.isNotEmpty() && previous != active) events += Event(2, 0, active.values.toList())
        return events
    }
    fun cancel(): List<Event> {
        if (active.isEmpty()) return emptyList()
        return listOf(Event(3, 0, active.values.toList())).also { active.clear() }
    }
    fun encode(event: Event, nanos: Long): ByteArray = AapProto.build {
        number(1, nanos)
        bytes(3, AapProto.build {
            event.pointers.forEach { point -> bytes(1, AapProto.build {
                number(1, (point.x * (width - 1)).toLong()); number(2, (point.y * (height - 1)).toLong()); number(3, point.id.toLong())
            }) }
            number(2, event.index.toLong()); number(3, event.action.toLong())
        })
    }
}
