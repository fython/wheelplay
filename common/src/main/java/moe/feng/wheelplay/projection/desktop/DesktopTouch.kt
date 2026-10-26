package moe.feng.wheelplay.projection.desktop

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** Maintains Android pointer indices while browser IDs remain stable across lifts. */
internal class DesktopTouch(private val config: DesktopDisplayConfig, private val inject: (MotionEvent) -> Unit) {
    private data class Point(val id: Int, val x: Float, val y: Float)
    private val active = mutableListOf<Point>()
    private var downTime = 0L

    fun update(ids: IntArray, xs: FloatArray, ys: FloatArray, down: BooleanArray = BooleanArray(ids.size) { true }) {
        require(ids.size <= 10 && ids.size == xs.size && ids.size == ys.size && ids.size == down.size)
        require(ids.toSet().size == ids.size && ids.all { it in 0..31 })
        require(xs.all { it.isFinite() && it in 0f..1f } && ys.all { it.isFinite() && it in 0f..1f })
        val points = ids.indices.map { Point(ids[it], xs[it] * (config.width - 1), ys[it] * (config.height - 1)) }
        val next = points.filterIndexed { index, _ -> down[index] }
        // Update remaining contacts before emitting a boundary, including the lifting pointer if available.
        active.indices.forEach { i -> points.find { it.id == active[i].id }?.let { active[i] = it } }
        for (index in active.indices.reversed()) if (next.none { it.id == active[index].id }) {
            emit(if (active.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            active.removeAt(index)
        }
        next.forEach { point -> if (active.none { it.id == point.id }) {
            if (active.isEmpty()) downTime = SystemClock.uptimeMillis()
            active.add(point)
            emit(if (active.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or ((active.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        } }
        if (active.isNotEmpty()) emit(MotionEvent.ACTION_MOVE)
    }

    fun cancel() {
        try { if (active.isNotEmpty()) emit(MotionEvent.ACTION_CANCEL) } finally { active.clear() }
    }

    private fun emit(action: Int) {
        val properties = active.map { point -> MotionEvent.PointerProperties().apply { id = point.id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = active.map { point -> MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, active.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { inject(event) } finally { event.recycle() }
    }
}
