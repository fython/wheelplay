package com.shilapi.xcertplay.projection.desktop

import android.os.Parcel
import android.os.Parcelable

/** Parcel is not AutoCloseable on the tested Android API; always recycle it explicitly. */
internal inline fun <T> withParcel(block: (Parcel) -> T): T {
    val parcel = Parcel.obtain()
    return try { block(parcel) } finally { parcel.recycle() }
}

internal fun <T : Parcelable> parcelRoundTrip(value: T, creator: Parcelable.Creator<T>): T = withParcel { parcel ->
    parcel.writeTypedObject(value, 0)
    parcel.setDataPosition(0)
    requireNotNull(parcel.readTypedObject(creator))
}

internal fun DesktopServiceInfo.copyWith(change: DesktopServiceInfo.() -> Unit): DesktopServiceInfo =
    parcelRoundTrip(this, DesktopServiceInfo.CREATOR).apply(change)
