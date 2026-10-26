package com.shilapi.xcertplay.projection.desktop

import android.os.Parcel
import android.os.Parcelable

/** Zero is success. Nonzero codes are stable DesktopErrorCode values; unknown codes remain intact. */
data class DesktopStatus(val code: Int, val diagnostic: String? = null) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(code)
        if (code != 0) dest.writeString(diagnostic)
    }
    internal fun throwIfError() {
        if (code != 0) throw DesktopException(code, diagnostic.orEmpty())
    }
    companion object {
        @JvmField val OK = DesktopStatus(0)
        @JvmField val CREATOR = object : Parcelable.Creator<DesktopStatus> {
            override fun createFromParcel(source: Parcel): DesktopStatus {
                val code = source.readInt()
                return if (code == 0) OK else DesktopStatus(code, source.readString())
            }
            override fun newArray(size: Int) = arrayOfNulls<DesktopStatus>(size)
        }
    }
}
