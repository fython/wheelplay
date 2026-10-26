package com.shilapi.xcertplay.projection.desktop

import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable
import com.shilapi.xcertplay.projection.ProjectionContact

/** Complete contact snapshot; absent IDs release, down=false supplies a final lift position.
 * Coordinates are normalized to display content. Empty frames release all contacts.
 * Wire: layout, count, then (id, x, y, down) primitives; no per-contact Parcelable metadata.
 */
class DesktopTouchFrame internal constructor(
    val layout: Int,
    internal val ids: IntArray,
    internal val xs: FloatArray,
    internal val ys: FloatArray,
    internal val down: BooleanArray,
) : Parcelable {
    init {
        require(ids.size <= DesktopFeatures.MAX_CONTACTS && ids.size == xs.size && ids.size == ys.size && ids.size == down.size)
    }
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(layout); dest.writeInt(ids.size)
        for (i in ids.indices) {
            dest.writeInt(ids[i]); dest.writeFloat(xs[i]); dest.writeFloat(ys[i]); dest.writeInt(if (down[i]) 1 else 0)
        }
    }

    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<DesktopTouchFrame> {
            override fun createFromParcel(source: Parcel): DesktopTouchFrame {
                val layout = source.readInt()
                val count = source.readInt()
                if (count !in 0..DesktopFeatures.MAX_CONTACTS || source.dataAvail() < count * 16)
                    throw BadParcelableException("Invalid desktop contact count or truncated frame")
                val ids = IntArray(count); val xs = FloatArray(count); val ys = FloatArray(count); val down = BooleanArray(count)
                for (i in 0 until count) {
                    ids[i] = source.readInt(); xs[i] = source.readFloat(); ys[i] = source.readFloat()
                    val state = source.readInt()
                    if (state !in 0..1) throw BadParcelableException("Invalid desktop contact state")
                    down[i] = state == 1
                }
                return DesktopTouchFrame(layout = layout, ids = ids, xs = xs, ys = ys, down = down)
            }
            override fun newArray(size: Int) = arrayOfNulls<DesktopTouchFrame>(size)
        }

        internal fun from(contacts: List<ProjectionContact>) = DesktopTouchFrame(
            layout = DesktopProtocol.LAYOUT,
            ids = IntArray(contacts.size) { contacts[it].id },
            xs = FloatArray(contacts.size) { contacts[it].x.toFloat() },
            ys = FloatArray(contacts.size) { contacts[it].y.toFloat() },
            down = BooleanArray(contacts.size) { contacts[it].down },
        )
    }
}
