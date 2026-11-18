package moe.feng.wheelplay.projection.carlife

import com.shilapi.xcertplay.projection.ProjectionContact
import moe.feng.wheelplay.carlife.VideoSize
import kotlin.math.roundToInt

/** Stable two-slot mapping for CarLife's fixed pointer IDs 0 and 1. */
internal class CarLifeTouch(private val send: (Int, Int, Int, Int, Int) -> Unit) {
    private data class Point(val id: Int, val x: Int, val y: Int)
    private var size = VideoSize.DEFAULT
    private var first: Point? = null
    private var second: Point? = null
    @Synchronized fun resize(width: Int, height: Int) {
        val next = VideoSize(width, height)
        if (next != size) { release(); size = next }
    }
    private fun emit(action: Int) {
        val a = first ?: return
        send(action, a.x, a.y, second?.x ?: 0, second?.y ?: 0)
    }
    @Synchronized fun accept(contacts: List<ProjectionContact>) {
        val points = contacts.filter { it.down }.take(2).map {
            Point(it.id, size.clampX((it.x * (size.width - 1)).roundToInt()),
                size.clampY((it.y * (size.height - 1)).roundToInt()))
        }
        // vivo MOVE resets the sole pointer ID to 0 even after POINTER_UP(index=0).
        // End that gesture and rebase the survivor, instead of sending inconsistent IDs.
        if (first != null && points.none { it.id == first?.id }) release()
        if (points.isEmpty()) return
        if (first == null) { first = points.first(); emit(0) }
        val oldFirst = first
        first = points.first { it.id == first?.id }
        val nextSecond = points.firstOrNull { it.id != first?.id }
        val oldSecond = second
        if (oldSecond != null && oldSecond.id != nextSecond?.id) {
            emit(262); second = null
        }
        if (nextSecond != null && second == null) {
            second = nextSecond; emit(261)
        } else {
            second = nextSecond
            if (first != oldFirst || (second != null && second != oldSecond)) emit(2)
        }
    }
    @Synchronized fun release() {
        if (second != null) { emit(262); second = null }
        if (first != null) emit(1)
        first = null
    }
}
