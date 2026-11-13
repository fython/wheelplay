package moe.feng.wheelplay.plugin.carlink

import android.os.Handler
import android.os.HandlerThread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SdkVideoCleanupTest {
    private class Cast {
        @JvmField var mWfdSessionId: String? = "session"
        @JvmField var mVideoPlayer: Any? = Any()
    }
    private class Adapter(val mWorkerHandler: Handler) {
        @JvmField var mIsInitialized = true
        @JvmField var mVideoInitialized = true
        @JvmField var mCastManager: Cast? = Cast()
        fun release() {
            mIsInitialized = false; mVideoInitialized = false
            mCastManager!!.mWfdSessionId = null; mCastManager!!.mVideoPlayer = null
        }
    }
    @Test fun localStopWithoutState5WaitsForQueuedCleanupAndThenSucceeds() {
        val thread = HandlerThread("fake-sdk").apply { start() }
        try {
            val adapter = Adapter(Handler(thread.looper))
            adapter.mWorkerHandler.post { adapter.release() }
            SdkVideoCleanup.awaitStopped(adapter)
            assertFalse(adapter.mIsInitialized)
        } finally { thread.quitSafely() }
    }
    @Test fun everyOutstandingResourceRejectsCleanStop() {
        val thread = HandlerThread("fake-sdk").apply { start() }
        try {
            val adapter = Adapter(Handler(thread.looper))
            for (keepActive in listOf<(Adapter) -> Unit>(
                { it.mIsInitialized = true }, { it.mVideoInitialized = true },
                { it.mCastManager!!.mWfdSessionId = "session" }, { it.mCastManager!!.mVideoPlayer = Any() })) {
                adapter.release(); keepActive(adapter)
                assertThrows(IllegalStateException::class.java) { SdkVideoCleanup.verifyStopped(adapter) }
            }
            // An unknown SDK layout must not manufacture successful cleanup.
            assertThrows(NoSuchFieldException::class.java) { SdkVideoCleanup.verifyStopped(Any()) }
        } finally { thread.quitSafely() }
    }
}
