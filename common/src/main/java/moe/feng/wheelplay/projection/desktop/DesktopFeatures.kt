package moe.feng.wheelplay.projection.desktop

import com.shilapi.xcertplay.projection.ProjectionCapabilities
import com.shilapi.xcertplay.projection.VideoIngressKind

/** Stable wire bits, never enum ordinals. Audio route support is separate from local playback/capture. */
internal object DesktopFeatures {
    const val VIDEO_SURFACE = 1L shl 0
    const val VIDEO_ENCODED = 1L shl 1
    const val VIDEO_FRAMEBUFFER = 1L shl 2
    const val TOUCH = 1L shl 3
    const val POINTER = 1L shl 4
    const val KEYBOARD = 1L shl 5
    const val BACK = 1L shl 6
    const val NIGHT_MODE = 1L shl 7
    const val AUDIO_PLAYBACK = 1L shl 8
    const val MICROPHONE = 1L shl 9
    const val BROWSER_AUDIO = 1L shl 10
    const val BROWSER_MICROPHONE = 1L shl 11
    const val CONFIGURE_DISPLAY = 1L shl 12
    const val RESIZE_DISPLAY = 1L shl 13
    const val HOME = 1L shl 14

    // Advertise only implemented operations. Reserved bits do not promise future support.
    const val HOST_SUPPORTED = VIDEO_SURFACE or TOUCH or BACK or CONFIGURE_DISPLAY
    const val SERVICE_SUPPORTED = VIDEO_SURFACE or TOUCH or BACK or CONFIGURE_DISPLAY
    const val REQUIRED = VIDEO_SURFACE or CONFIGURE_DISPLAY
    const val MAX_CONTACTS = 10

    fun projection(features: Long, maxContacts: Int) = ProjectionCapabilities(
        video = buildSet {
            if (features has VIDEO_SURFACE) add(VideoIngressKind.SURFACE)
            if (features has VIDEO_ENCODED) add(VideoIngressKind.ENCODED)
            if (features has VIDEO_FRAMEBUFFER) add(VideoIngressKind.FRAMEBUFFER)
        },
        maxTouchContacts = if (features has TOUCH) maxContacts else 0,
        nightMode = features has NIGHT_MODE,
        audioPlayback = features has AUDIO_PLAYBACK,
        microphone = features has MICROPHONE,
        browserAudio = features has AUDIO_PLAYBACK && features has BROWSER_AUDIO,
        browserMicrophone = features has MICROPHONE && features has BROWSER_MICROPHONE,
        pointerInput = features has POINTER,
        keyboardInput = features has KEYBOARD,
        backButton = features has BACK,
        cancellation = com.shilapi.xcertplay.projection.ProjectionCancellation.NATIVE,
    )

    infix fun Long.has(feature: Long) = this and feature == feature
}
