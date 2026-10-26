package com.shilapi.xcertplay.projection.desktop

import android.os.Parcel
import android.os.Parcelable

/** A successful result must identify an owned, non-default display (> 0). */
data class DesktopStartResult(val status: DesktopStatus, val displayId: Int) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        status.writeToParcel(dest, flags); dest.writeInt(displayId)
    }
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<DesktopStartResult> {
            override fun createFromParcel(source: Parcel) = DesktopStartResult(DesktopStatus.CREATOR.createFromParcel(source), source.readInt())
            override fun newArray(size: Int) = arrayOfNulls<DesktopStartResult>(size)
        }
    }
}
