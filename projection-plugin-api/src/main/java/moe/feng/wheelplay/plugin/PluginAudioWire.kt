package moe.feng.wheelplay.plugin

import java.io.DataInputStream
import java.io.DataOutputStream

/** Audio v1: ordered, bounded records on dedicated session pipes. PCM is signed 16-bit LE. */
object PluginAudioWire {
    private const val MAGIC = 0x57504131 // WPA1
    const val MAX_PACKET = 64 * 1024
    const val PCM16 = 1
    const val AAC_ADTS = 2
    data class Format(val codec: Int, val rate: Int, val channels: Int) {
        init {
            require(codec == PCM16 || codec == AAC_ADTS)
            require(rate in setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000))
            require(channels in 1..2)
        }
        val frameBytes get() = channels * 2
        fun validPcm(bytes: ByteArray) = bytes.isNotEmpty() && bytes.size <= MAX_PACKET && bytes.size % frameBytes == 0
    }
    sealed interface Message { val id: Long }
    data class Start(override val id: Long, val stream: Int, val format: Format) : Message
    data class Data(override val id: Long, val bytes: ByteArray) : Message
    data class Stop(override val id: Long) : Message
    data class Microphone(override val id: Long, val format: Format, val call: Boolean) : Message
    data class MicrophoneStop(override val id: Long) : Message
    data class MicrophoneData(override val id: Long, val bytes: ByteArray) : Message

    class Writer(private val out: DataOutputStream) {
        private var sequence = 0L
        init { out.writeInt(MAGIC) }
        fun write(message: Message) {
            require(message.id > 0)
            val opcode = when (message) {
                is Start -> { require(message.stream in 0..31); 1 }
                is Data -> { require(message.bytes.size in 1..MAX_PACKET); 2 }
                is Stop -> 3
                is Microphone -> { require(message.format.codec == PCM16); 4 }
                is MicrophoneStop -> 5
                is MicrophoneData -> { require(message.bytes.size in 1..MAX_PACKET && message.bytes.size % 2 == 0); 6 }
            }
            out.writeLong(sequence++); out.writeByte(opcode); out.writeLong(message.id)
            when (message) {
                is Start -> { out.writeInt(message.stream); format(message.format) }
                is Data -> bytes(message.bytes)
                is Microphone -> { format(message.format); out.writeBoolean(message.call) }
                is MicrophoneData -> bytes(message.bytes)
                else -> Unit
            }
            out.flush()
        }
        private fun format(value: Format) { out.writeInt(value.codec); out.writeInt(value.rate); out.writeInt(value.channels) }
        private fun bytes(value: ByteArray) { out.writeInt(value.size); out.write(value) }
    }
    class Reader(private val input: DataInputStream) {
        private var sequence = 0L
        init { require(input.readInt() == MAGIC) { "Unsupported plugin audio framing" } }
        fun read(): Message {
            require(input.readLong() == sequence++) { "Out-of-order plugin audio" }
            val opcode = input.readUnsignedByte(); val id = input.readLong()
            require(id > 0)
            return when (opcode) {
                1 -> { val stream = input.readInt(); require(stream in 0..31); Start(id, stream, format()) }
                2 -> Data(id, bytes())
                3 -> Stop(id)
                4 -> { val format = format(); require(format.codec == PCM16); Microphone(id, format, input.readBoolean()) }
                5 -> MicrophoneStop(id)
                6 -> MicrophoneData(id, bytes().also { require(it.size % 2 == 0) })
                else -> error("Unknown plugin audio record")
            }
        }
        private fun format() = Format(input.readInt(), input.readInt(), input.readInt())
        private fun bytes(): ByteArray {
            val size = input.readInt(); require(size in 1..MAX_PACKET) { "Invalid plugin audio packet size" }
            return ByteArray(size).also { input.readFully(it) }
        }
    }
}
