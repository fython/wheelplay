package com.shilapi.xcertplay.projection

import android.content.Context
import com.shilapi.xcertplay.shared.R

/** Diagnostics stay in logs/state; UI messages depend only on the stable common category. */
internal fun ProjectionFailure.message(context: Context): String = context.getString(when (category) {
    ProjectionErrorCategory.PERMISSION -> R.string.projection_error_permission
    ProjectionErrorCategory.CONNECTION -> R.string.projection_error_connection
    ProjectionErrorCategory.PROTOCOL -> R.string.projection_error_protocol
    ProjectionErrorCategory.CONFIGURATION -> R.string.projection_error_configuration
    ProjectionErrorCategory.INPUT -> R.string.projection_error_input
    ProjectionErrorCategory.TIMEOUT -> R.string.projection_error_timeout
    ProjectionErrorCategory.CANCELLED -> R.string.projection_error_cancelled
    ProjectionErrorCategory.CLEANUP -> R.string.projection_error_cleanup
    ProjectionErrorCategory.UNAVAILABLE -> R.string.projection_error_unavailable
    ProjectionErrorCategory.INTERNAL -> R.string.projection_error_internal
})

internal fun ProjectionSnapshot.message(context: Context): String = failure?.message(context) ?: context.getString(when (phase) {
    ProjectionPhase.CONNECTING, ProjectionPhase.NEGOTIATING -> R.string.projection_state_connecting
    ProjectionPhase.RUNNING -> R.string.projection_state_running
    ProjectionPhase.STOPPING -> R.string.projection_state_stopping
    ProjectionPhase.STOPPED, ProjectionPhase.FAILED -> R.string.projection_waiting
})
