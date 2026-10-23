package com.shilapi.xcertplay.projection

import android.content.Context
import java.util.concurrent.CompletableFuture

/** No proprietary SDK is bundled. Do not start BLE/P2P or claim a usable CarLink session. */
internal class IccoaSource(private val context: Context) : ProjectionSourceProvider {
    override val descriptor = SourceDescriptor("iccoa-carlink", "ICCOA CarLink")
    override val resources = setOf(ProjectionResource.USB, ProjectionResource.WIRELESS,
        ProjectionResource.DISPLAY, ProjectionResource.MICROPHONE)
    override fun probe(): SourceAvailability = SourceAvailability.Unavailable(
        context.getString(com.shilapi.xcertplay.shared.R.string.iccoa_sdk_unavailable))
    override fun open(): CompletableFuture<ProjectionSession> = CompletableFuture<ProjectionSession>().also {
        it.completeExceptionally(IllegalStateException((probe() as SourceAvailability.Unavailable).reason))
    }
}
