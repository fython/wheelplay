package com.shilapi.xcertplay.projection.desktop

import com.shilapi.xcertplay.projection.ProjectionSourceIds
import android.view.Surface
import com.shilapi.xcertplay.projection.ProjectionContact
import com.shilapi.xcertplay.projection.SourceAvailability
import java.util.concurrent.CompletableFuture

/** Platform-only port; the shared projection API never depends on Shizuku or Binder. */
internal interface DesktopBackend {
    fun probe(): SourceAvailability
    fun connect(): CompletableFuture<DesktopConnection>
}

internal interface DesktopConnection : AutoCloseable {
    val uid: Int
    fun start(surface: Surface, component: String): Int
    fun touch(contacts: List<ProjectionContact>)
    fun back()
    /** Also renews the helper's watchdog; rejects unexpected rotation/size changes. */
    fun healthy(): Boolean
    /** Stop producer, confirm release, then unbind/terminate the helper. */
    override fun close()
}

internal object DesktopProtocol {
    const val VERSION = 1
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val DPI = 160
    const val LAYOUT = 1
    const val SOURCE_ID = ProjectionSourceIds.ANDROID_DESKTOP
}
