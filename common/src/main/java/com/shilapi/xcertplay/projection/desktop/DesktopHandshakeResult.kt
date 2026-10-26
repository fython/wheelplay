package com.shilapi.xcertplay.projection.desktop

import android.os.Parcel
import android.os.Parcelable

data class DesktopHandshakeResult(val status: DesktopStatus, val info: DesktopServiceInfo?) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        status.writeToParcel(dest, flags)
        dest.writeTypedObject(info, flags)
    }
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<DesktopHandshakeResult> {
            override fun createFromParcel(source: Parcel) = DesktopHandshakeResult(
                DesktopStatus.CREATOR.createFromParcel(source), source.readTypedObject(DesktopServiceInfo.CREATOR))
            override fun newArray(size: Int) = arrayOfNulls<DesktopHandshakeResult>(size)
        }
    }
}
