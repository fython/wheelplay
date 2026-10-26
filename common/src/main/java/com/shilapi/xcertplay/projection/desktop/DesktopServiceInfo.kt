package com.shilapi.xcertplay.projection.desktop

import android.os.Parcel
import android.os.Parcelable

/** Protocol v4 handshake response. Limits describe accepted requests, not device compatibility guarantees. */
data class DesktopServiceInfo(
    val protocolVersion: Int,
    val uid: Int,
    val supportedFeatures: Long,
    val enabledFeatures: Long,
    val maxTouchContacts: Int,
    val minSize: Int,
    val maxSize: Int,
    val maxPixels: Long,
    val minDpi: Int,
    val maxDpi: Int,
) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(protocolVersion); dest.writeInt(uid)
        dest.writeLong(supportedFeatures); dest.writeLong(enabledFeatures)
        dest.writeInt(maxTouchContacts); dest.writeInt(minSize); dest.writeInt(maxSize)
        dest.writeLong(maxPixels); dest.writeInt(minDpi); dest.writeInt(maxDpi)
    }

    internal fun accepts(config: DesktopDisplayConfig) = config.width in minSize..maxSize &&
        config.height in minSize..maxSize && config.width.toLong() * config.height <= maxPixels && config.dpi in minDpi..maxDpi

    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<DesktopServiceInfo> {
            override fun createFromParcel(source: Parcel) = DesktopServiceInfo(source.readInt(), source.readInt(),
                source.readLong(), source.readLong(), source.readInt(), source.readInt(), source.readInt(),
                source.readLong(), source.readInt(), source.readInt())
            override fun newArray(size: Int) = arrayOfNulls<DesktopServiceInfo>(size)
        }

        internal fun negotiate(version: Int, requested: Long, uid: Int): DesktopServiceInfo {
            desktopCheck(version == DesktopProtocol.VERSION, DesktopErrorCode.PROTOCOL_MISMATCH) { "Host protocol $version; helper ${DesktopProtocol.VERSION}" }
            val enabled = requested and DesktopFeatures.SERVICE_SUPPORTED
            desktopCheck(enabled and DesktopFeatures.REQUIRED == DesktopFeatures.REQUIRED, DesktopErrorCode.UNSUPPORTED_CAPABILITY) {
                "Surface video and display configuration are required"
            }
            return DesktopServiceInfo(DesktopProtocol.VERSION, uid, DesktopFeatures.SERVICE_SUPPORTED, enabled,
                if (enabled and DesktopFeatures.TOUCH != 0L) DesktopFeatures.MAX_CONTACTS else 0,
                DesktopDisplayConfig.MIN_SIZE, DesktopDisplayConfig.MAX_SIZE, DesktopDisplayConfig.MAX_PIXELS,
                DesktopDisplayConfig.MIN_DPI, DesktopDisplayConfig.MAX_DPI)
        }

        internal fun validate(info: DesktopServiceInfo, requested: Long = DesktopFeatures.HOST_SUPPORTED) {
            desktopCheck(info.protocolVersion == DesktopProtocol.VERSION, DesktopErrorCode.PROTOCOL_MISMATCH) { "Unexpected helper protocol ${info.protocolVersion}" }
            desktopCheck(info.uid == 0 || info.uid == 2000, DesktopErrorCode.PERMISSION_DENIED) { "Unexpected helper UID ${info.uid}" }
            desktopCheck(info.enabledFeatures == info.supportedFeatures and requested &&
                info.enabledFeatures and DesktopFeatures.REQUIRED == DesktopFeatures.REQUIRED &&
                info.maxTouchContacts in 0..DesktopFeatures.MAX_CONTACTS &&
                (info.enabledFeatures and DesktopFeatures.TOUCH != 0L) == (info.maxTouchContacts > 0) &&
                info.minSize > 0 && info.maxSize >= info.minSize && info.maxPixels >= info.minSize.toLong() * info.minSize &&
                info.minDpi > 0 && info.maxDpi >= info.minDpi, DesktopErrorCode.PROTOCOL_MISMATCH) { "Invalid helper capabilities or limits" }
        }
    }
}
