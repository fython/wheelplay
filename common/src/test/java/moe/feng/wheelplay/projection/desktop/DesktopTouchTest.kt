package moe.feng.wheelplay.projection.desktop

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DesktopTouchTest {
    private data class Event(val action: Int, val index: Int, val ids: List<Int>, val x: Float, val y: Float)
    private val events = mutableListOf<Event>()
    private val touch = DesktopTouch(DesktopDisplayConfig()) { event ->
        events += Event(event.actionMasked, event.actionIndex, (0 until event.pointerCount).map { event.getPointerId(it) }, event.x, event.y)
    }
    @Test fun customLandscapeAndPortraitDisplaysMapNormalizedCoordinatesToTheirOwnBounds() {
        for (config in listOf(DesktopDisplayConfig(1920, 1080, 240), DesktopDisplayConfig(720, 1280, 320))) {
            val positions = mutableListOf<Pair<Float, Float>>()
            val custom = DesktopTouch(config) { positions += it.x to it.y }
            custom.update(intArrayOf(0), floatArrayOf(0f), floatArrayOf(0f))
            assertEquals(0f to 0f, positions.first())
            custom.update(intArrayOf(0), floatArrayOf(.5f), floatArrayOf(.5f))
            assertEquals((config.width - 1) * .5f to (config.height - 1) * .5f, positions.last())
            custom.update(intArrayOf(0), floatArrayOf(1f), floatArrayOf(1f), booleanArrayOf(false))
            assertEquals((config.width - 1).toFloat() to (config.height - 1).toFloat(), positions.last())
        }
    }
    @Test fun stablePointerIdsSurviveReorderingAndLifts() {
        touch.update(intArrayOf(7), floatArrayOf(1f), floatArrayOf(1f))
        assertEquals(MotionEvent.ACTION_DOWN, events.first().action)
        assertEquals(1279f, events.first().x); assertEquals(719f, events.first().y)
        touch.update(intArrayOf(2, 7), floatArrayOf(.2f, .7f), floatArrayOf(.3f, .8f))
        val down = events.single { it.action == MotionEvent.ACTION_POINTER_DOWN }
        assertEquals(listOf(7, 2), down.ids); assertEquals(1, down.index)
        touch.update(intArrayOf(2), floatArrayOf(.4f), floatArrayOf(.5f))
        val up = events.single { it.action == MotionEvent.ACTION_POINTER_UP }
        assertEquals(listOf(7, 2), up.ids); assertEquals(0, up.index)
        touch.update(intArrayOf(), floatArrayOf(), floatArrayOf())
        assertEquals(MotionEvent.ACTION_UP, events.last().action)
        assertEquals(listOf(2), events.last().ids)
    }
    @Test fun upUsesFinalCoordinatesEvenWithoutAnIntermediateMove() {
        touch.update(intArrayOf(0), floatArrayOf(0f), floatArrayOf(0f))
        touch.update(intArrayOf(0), floatArrayOf(1f), floatArrayOf(1f), booleanArrayOf(false))
        assertEquals(MotionEvent.ACTION_UP, events.last().action)
        assertEquals(1279f, events.last().x); assertEquals(719f, events.last().y)
    }
    @Test fun cancellationReleasesAllContactsOnceAndNextGestureStartsFresh() {
        touch.update(intArrayOf(1, 3), floatArrayOf(.1f, .3f), floatArrayOf(.2f, .4f))
        touch.cancel(); touch.cancel()
        assertEquals(1, events.count { it.action == MotionEvent.ACTION_CANCEL })
        assertEquals(listOf(1, 3), events.last().ids)
        touch.update(intArrayOf(4), floatArrayOf(.5f), floatArrayOf(.5f))
        assertEquals(MotionEvent.ACTION_DOWN, events[events.size - 2].action)
    }
    @Test fun malformedCoordinatesAndDuplicateIdsNeverReachAndroid() {
        for (input in listOf(floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(1.1f))) {
            assertThrows(IllegalArgumentException::class.java) { touch.update(intArrayOf(0), input, floatArrayOf(0f)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            touch.update(intArrayOf(1, 1), floatArrayOf(0f, 0f), floatArrayOf(0f, 0f))
        }
        assertThrows(IllegalArgumentException::class.java) { touch.update(intArrayOf(32), floatArrayOf(0f), floatArrayOf(0f)) }
        assertTrue(events.isEmpty())
    }
}
