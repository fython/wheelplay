package com.shilapi.xcertplay.projection

/** Application-scoped coordinator; the foreground service owns its shutdown. */
internal val projectionSessions = ProjectionRuntime()

@Synchronized internal fun registerProjectionSources(context: android.content.Context) {
    if (projectionSessions.sources().isNotEmpty()) return
    val app = context.applicationContext
    VideoIngressKind.entries.forEach { kind ->
        projectionSessions.register(SyntheticSource(kind,
            { ingress, width, height -> BrowserProjectionVideo(app, width, height, ingress) },
            { app.assets.open("projection/demo.h264clip") }))
    }
}
