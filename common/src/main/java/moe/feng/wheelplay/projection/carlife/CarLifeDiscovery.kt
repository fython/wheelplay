package moe.feng.wheelplay.projection.carlife

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Listens for the source's announcement without opening or claiming its TCP channels. */
internal class CarLifeDiscovery(private val found: (String) -> Unit, private val finished: (String) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    @Volatile private var socket: DatagramSocket? = null
    fun start() {
        Thread({
            try {
                val local = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.toSet()
                DatagramSocket(null).use { receiver ->
                    socket = receiver
                    if (closed.get()) return@Thread
                    receiver.reuseAddress = true
                    receiver.bind(InetSocketAddress(7999))
                    receiver.soTimeout = 1000
                    val seen = mutableSetOf<String>()
                    val deadline = System.nanoTime() + 20_000_000_000L
                    while (!closed.get() && System.nanoTime() < deadline) {
                        val packet = DatagramPacket(ByteArray(128), 128)
                        try { receiver.receive(packet) } catch (_: SocketTimeoutException) { continue }
                        val host = packet.address.hostAddress ?: continue
                        if (host !in local && CarLifeConnection.validAddress(host) && isAnnouncement(packet.data, packet.length) && seen.size < 16 && seen.add(host)) found(host)
                    }
                    if (!closed.get()) finished(if (seen.isEmpty()) "未发现手机，请在源手机开始 CarLife 无线连接后重试，或手动输入地址。" else "搜索完成，请选择手机。")
                }
            } catch (_: Exception) {
                if (!closed.get()) finished("无法接收发现广播，请重试或手动输入地址。")
            } finally { socket = null }
        }, "carlife-discovery").apply { isDaemon = true }.start()
    }
    override fun close() { closed.set(true); socket?.close() }
    companion object {
        fun isAnnouncement(bytes: ByteArray, length: Int): Boolean =
            length == 11 && bytes.size >= length && bytes.copyOfRange(0, length).contentEquals("CarlifeHost".toByteArray(Charsets.US_ASCII))
    }
}
