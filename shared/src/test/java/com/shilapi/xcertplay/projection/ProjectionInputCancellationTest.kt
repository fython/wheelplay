package com.shilapi.xcertplay.projection

import com.shilapi.xcertplay.androidauto.AapTouch
import org.junit.Assert.*
import org.junit.Test

class ProjectionInputCancellationTest {
    @Test fun androidAutoCancelDoesNotEmitUpAndNextGestureStartsFresh() {
        val touch = AapTouch(1280, 720)
        val contacts = listOf(ProjectionContact(0, .2, .3, true), ProjectionContact(1, .6, .7, true))
        touch.update(contacts)
        val cancel = touch.cancel().single()
        assertEquals(3, cancel.action); assertEquals(2, cancel.pointers.size)
        assertTrue(touch.cancel().isEmpty())
        assertEquals(0, touch.update(contacts.take(1)).single().action)
        assertEquals(1, touch.update(emptyList()).single().action)
    }
}
