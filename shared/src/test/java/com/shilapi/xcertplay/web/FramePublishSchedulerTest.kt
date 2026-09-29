package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class FramePublishSchedulerTest {
    private class Fixture {
        var time = 0L
        var viewing = true
        var latest = 0
        val published = mutableListOf<Int>()
        val tasks = linkedMapOf<Runnable, Long>()
        val scheduler = FramePublishScheduler(
            now = { time }, wanted = { viewing },
            post = { task, delay -> tasks[task] = time + delay }, remove = { tasks.remove(it) },
            publish = { published.add(latest) },
        )
        fun frame(value: Int) { latest = value; scheduler.request() }
        fun advance(to: Long) {
            while (true) {
                val next = tasks.entries.minByOrNull { it.value } ?: break
                if (next.value > to) break
                time = next.value; tasks.remove(next.key); next.key.run()
            }
            time = to
        }
    }

    @Test fun finalFrameInsideThrottleWindowIsPublishedWithoutAnotherArrival() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        f.advance(33); f.frame(2)
        f.advance(65); assertEquals(listOf(1), f.published)
        f.advance(66); assertEquals(listOf(1, 2), f.published)
        f.advance(1000); assertEquals(2, f.published.size)
        assertTrue(f.tasks.isEmpty())
    }

    @Test fun burstKeepsOneTaskAndPublishesOnlyTheLatestTexture() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        for (i in 2..60) { f.advance(i.toLong()); f.frame(i) }
        assertEquals(1, f.tasks.size)
        f.advance(66)
        assertEquals(listOf(1, 60), f.published)
    }

    @Test fun viewerReturningGetsTheLatestStaticFrame() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        f.advance(33); f.frame(2)
        f.viewing = false; f.advance(100)
        f.frame(3); f.advance(200)
        assertEquals(listOf(1), f.published)
        assertTrue(f.tasks.isEmpty())
        f.viewing = true; f.scheduler.request(); f.advance(200)
        assertEquals(listOf(1, 3), f.published)
    }

    @Test fun closingCancelsTheTrailingFrameAndRejectsLateNotifications() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        f.advance(33); f.frame(2); f.scheduler.close()
        f.frame(3); f.advance(1000)
        assertEquals(listOf(1), f.published)
        assertTrue(f.tasks.isEmpty())
    }

    @Test fun pendingStaticFrameResumesAfterAShortViewerGapWithoutRepeatedCompression() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        f.viewing = false; f.advance(100); f.frame(2)
        f.viewing = true; f.scheduler.resume(); f.advance(200)
        assertEquals(listOf(1, 2), f.published)
        repeat(10) { f.scheduler.resume(); f.advance(400L + it * 200) }
        assertEquals(2, f.published.size)
    }
}
