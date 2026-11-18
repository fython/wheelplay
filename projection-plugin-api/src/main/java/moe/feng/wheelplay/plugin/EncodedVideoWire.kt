package moe.feng.wheelplay.plugin

import java.io.DataInputStream
import java.io.DataOutputStream

/** Extension v1: big-endian, one pipe per session, fixed format then complete AVC Annex-B AUs.
 * No keyframe-request capability is claimed. Format changes require a new session.
 */
object EncodedVideoWire {
    private const val MAGIC = 0x57504531 // WPE1
    const val MAX_ACCESS_UNIT = 2 * 1024 * 1024
    fun writeFormat(out: DataOutputStream, width: Int, height: Int) {
        PluginVideoInfo(width, height)
        out.writeInt(MAGIC); out.writeInt(width); out.writeInt(height)
    }
    fun readFormat(input: DataInputStream): Pair<Int, Int> {
        require(input.readInt() == MAGIC) { "Unsupported encoded video framing" }
        val width = input.readInt(); val height = input.readInt()
        PluginVideoInfo(width, height)
        return width to height
    }
    data class Frame(val sequence: Long, val ptsUs: Long, val bytes: ByteArray)
    fun writeFrame(out: DataOutputStream, frame: Frame) {
        require(frame.sequence >= 0 && frame.ptsUs >= 0 && frame.bytes.size in 1..MAX_ACCESS_UNIT)
        out.writeLong(frame.sequence); out.writeLong(frame.ptsUs)
        out.writeInt(frame.bytes.size); out.write(frame.bytes)
    }
    fun readFrame(input: DataInputStream): Frame {
        val sequence = input.readLong(); val pts = input.readLong(); val size = input.readInt()
        require(sequence >= 0 && pts >= 0 && size in 1..MAX_ACCESS_UNIT) { "Invalid encoded video record" }
        return Frame(sequence, pts, ByteArray(size).also { input.readFully(it) })
    }
}
