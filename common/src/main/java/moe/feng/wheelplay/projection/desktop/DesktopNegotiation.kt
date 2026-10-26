package moe.feng.wheelplay.projection.desktop

import moe.feng.wheelplay.projection.desktop.DesktopFeatures.has

/** Wire fields/serialization live in AIDL. Build once, then treat these session snapshots as read-only. */
internal object DesktopNegotiation {
    fun negotiate(version: Int, requested: Long, helperUid: Int, required: Long = DesktopFeatures.REQUIRED,
        available: Long = DesktopFeatures.SERVICE_SUPPORTED): DesktopServiceInfo {
        desktopCheck(version == DesktopProtocol.VERSION, DesktopErrorCode.PROTOCOL_MISMATCH) { "Host protocol $version; helper ${DesktopProtocol.VERSION}" }
        desktopCheck(required and requested == required, DesktopErrorCode.INVALID_ARGUMENT) { "Required features must be requested" }
        val enabled = requested and DesktopFeatures.SERVICE_SUPPORTED and available
        desktopCheck(enabled has (required or DesktopFeatures.REQUIRED), DesktopErrorCode.UNSUPPORTED_CAPABILITY) {
            "Surface video and display configuration are required"
        }
        return DesktopServiceInfo().apply {
            protocolVersion = DesktopProtocol.VERSION
            uid = helperUid
            supportedFeatures = DesktopFeatures.SERVICE_SUPPORTED
            enabledFeatures = enabled
            maxTouchContacts = if (enabled has DesktopFeatures.TOUCH) DesktopFeatures.MAX_CONTACTS else 0
            minSize = DesktopDisplayConfig.MIN_SIZE
            maxSize = DesktopDisplayConfig.MAX_SIZE
            maxPixels = DesktopDisplayConfig.MAX_PIXELS
            minDpi = DesktopDisplayConfig.MIN_DPI
            maxDpi = DesktopDisplayConfig.MAX_DPI
        }
    }
}

internal fun DesktopServiceInfo.accepts(config: DesktopDisplayConfig) =
    config.width in minSize..maxSize && config.height in minSize..maxSize &&
        config.width.toLong() * config.height <= maxPixels && config.dpi in minDpi..maxDpi

internal fun DesktopServiceInfo.validate(requested: Long = DesktopFeatures.HOST_SUPPORTED, required: Long = DesktopFeatures.REQUIRED) {
    desktopCheck(protocolVersion == DesktopProtocol.VERSION, DesktopErrorCode.PROTOCOL_MISMATCH) { "Unexpected helper protocol $protocolVersion" }
    desktopCheck(uid == 0 || uid == 2000, DesktopErrorCode.PERMISSION_DENIED) { "Unexpected helper UID $uid" }
    desktopCheck(enabledFeatures and (supportedFeatures and requested) == enabledFeatures &&
        enabledFeatures has (required or DesktopFeatures.REQUIRED) &&
        maxTouchContacts in 0..DesktopFeatures.MAX_CONTACTS &&
        (enabledFeatures has DesktopFeatures.TOUCH) == (maxTouchContacts > 0) &&
        minSize > 0 && maxSize >= minSize && maxPixels >= minSize.toLong() * minSize &&
        minDpi > 0 && maxDpi >= minDpi, DesktopErrorCode.PROTOCOL_MISMATCH) { "Invalid helper capabilities or limits" }
}

internal fun DesktopStatus?.checkSuccess() {
    val status = this ?: throw DesktopException(DesktopErrorCode.PROTOCOL_MISMATCH, "Control response omitted status")
    status.throwIfError()
}

internal fun DesktopDisplayInfo.validate(config: DesktopDisplayConfig) {
    desktopCheck(displayId > 0 && width == config.width && height == config.height && dpi == config.dpi &&
        rotation == 0 && layoutRevision == DesktopProtocol.LAYOUT, DesktopErrorCode.LAYOUT_CHANGED) {
        "Unexpected actual desktop layout: $this"
    }
}

internal fun DesktopStartConfig.enabledWithin(negotiated: Long): Long {
    desktopCheck(requiredFeatures and requestedFeatures == requiredFeatures, DesktopErrorCode.INVALID_ARGUMENT) { "Required session features must be requested" }
    val enabled = requestedFeatures and negotiated
    desktopCheck(enabled has (requiredFeatures or DesktopFeatures.REQUIRED), DesktopErrorCode.UNSUPPORTED_CAPABILITY) { "Required session features unavailable" }
    return enabled
}
