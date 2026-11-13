package moe.feng.wheelplay.plugin.carlink

import android.os.Handler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Shutdown evidence for the pinned SDK, inspected on its worker after deInit.
 * stopWfdServer clears the session after native stop returns and unregisters its listener;
 * a local stop therefore need not deliver the remote-disconnect state-5 callback.
 * The host must still await dedicated-process death after the clean acknowledgement.
 */
internal object SdkVideoCleanup {
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    fun awaitStopped(adapter: Any) {
        val handler = field(adapter, "mWorkerHandler") as Handler
        val result = CompletableFuture<Unit>()
        check(handler.post {
            try { verifyStopped(adapter); result.complete(Unit) }
            catch (failure: Throwable) { result.completeExceptionally(failure) }
        }) { "SDK worker rejected shutdown barrier" }
        result.get(2, TimeUnit.SECONDS)
    }

    internal fun verifyStopped(adapter: Any) {
        check(field(adapter, "mIsInitialized") == false) { "SDK deInit has not completed" }
        check(field(adapter, "mVideoInitialized") == false) { "SDK video shutdown has not completed" }
        field(adapter, "mCastManager")?.let { cast ->
            check(field(cast, "mWfdSessionId") == null) { "SDK video session remains active" }
            check(field(cast, "mVideoPlayer") == null) { "SDK video decoder remains active" }
        }
    }
}
