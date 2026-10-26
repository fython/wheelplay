package moe.feng.wheelplay.projection.desktop

import com.shilapi.xcertplay.projection.ProjectionSourceIds
import android.view.Surface
import com.shilapi.xcertplay.projection.ProjectionContact
import com.shilapi.xcertplay.projection.ProjectionCapabilities
import com.shilapi.xcertplay.projection.SourceAvailability
import com.shilapi.xcertplay.projection.ProjectionStartup
import java.util.concurrent.CompletableFuture

/** Platform-only port; the shared projection API never depends on Shizuku or Binder. */
internal interface DesktopBackend {
    fun probe(): SourceAvailability
    fun connect(): CompletableFuture<DesktopConnection>
    fun connect(startup: ProjectionStartup): CompletableFuture<DesktopConnection> { startup.checkActive(); return connect() }
}

internal interface DesktopConnection : AutoCloseable {
    val uid: Int
    val capabilities: ProjectionCapabilities
    fun start(surface: Surface, component: String, config: DesktopDisplayConfig): DesktopDisplayInfo
    fun touch(contacts: List<ProjectionContact>)
    fun cancelInput()
    fun back()
    /** Also renews the helper's watchdog; rejects unexpected rotation/size changes. */
    fun healthy(): DesktopSessionState
    fun state(): DesktopSessionState
    /** Stop producer, confirm release, then unbind/terminate the helper. */
    override fun close()
}

internal object DesktopProtocol {
    const val VERSION = 7
    const val LAYOUT = 1
    const val SOURCE_ID = ProjectionSourceIds.ANDROID_DESKTOP
}
