package com.shilapi.xcertplay.iap2.message

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded, single-current-artwork receiver for iAP2 file transfer session v1/v2. */
class Iap2ArtworkReceiver(
    private val send: (ByteArray) -> Unit,
    private val complete: (revision: Long, transferId: Int, bytes: ByteArray) -> Boolean,
    private val clock: () -> Long,
    private val onDiagnostic: (String) -> Unit = {},
) {
    private data class Transfer(val id: Int, val revision: Long, val size: Int,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream(), var first: Boolean = true,
        var deadline: Long = 0)
    private var expectedId: Int? = null
    private var revision = 0L
    private var active: Transfer? = null
    private val pendingSetups = LinkedHashMap<Int, Pair<ByteArray, Long>>()

    @Synchronized fun expect(id: Int?, trackRevision: Long) {
        if (id == expectedId && revision == trackRevision) return
        active?.let { reply(it.id, CANCEL) }
        active = null
        // Retire the old advertised transfer, but keep unassociated SETUPs while an
        // incremental metadata update has not yet supplied the next artwork identifier.
        expectedId?.takeIf { it != id }?.let { oldId ->
            pendingSetups.remove(oldId)?.let { reply(oldId, CANCEL) }
        }
        expectedId = id
        revision = trackRevision
        if (id == null) return
        val setup = pendingSetups.remove(id)
        pendingSetups.keys.toList().forEach { reply(it, CANCEL) }
        pendingSetups.clear()
        if (setup != null && setup.second > clock()) receive(setup.first)
    }

    @Synchronized fun receive(bytes: ByteArray) {
        expire()
        if (bytes.size < 2) return
        val id = bytes[0].toInt() and 0xff
        val kind = bytes[1].toInt() and 0xff
        if (kind == CANCEL || kind == FAILURE) {
            diagnostic("artwork peer ${if (kind == CANCEL) "CANCEL" else "FAILURE"} id=$id")
            if (active?.id == id) active = null
            pendingSetups.remove(id)
            return
        }
        if (kind == SETUP) {
            val type = if (bytes.size >= 12) ByteBuffer.wrap(bytes, 10, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff else null
            val size = if (bytes.size >= 10) ByteBuffer.wrap(bytes, 2, 8).order(ByteOrder.BIG_ENDIAN).long else null
            diagnostic("artwork SETUP id=$id packetBytes=${bytes.size} type=${type ?: "v1"} declaredBytes=${size ?: "invalid"}")
            if (bytes.size !in 10..12 || id < 128) { reply(id, CANCEL); return }
            val artwork = bytes.size == 10 || bytes.size == 12 &&
                ByteBuffer.wrap(bytes, 10, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() == 2
            if (size == null || size !in 1..MAX_BYTES.toLong() || !artwork) { reply(id, CANCEL); return }
            if (id != expectedId) {
                // Control and file queues can be consumed in a different order. Do not start yet.
                if (pendingSetups.size >= 4) { reply(id, CANCEL); return }
                pendingSetups[id] = bytes.copyOf() to (clock() + TIMEOUT_MS)
                diagnostic("artwork SETUP queued id=$id bytes=$size; awaiting metadata")
                return
            }
            if (active != null) { reply(id, CANCEL); active = null; return }
            active = Transfer(id, revision, size.toInt(), deadline = clock() + TIMEOUT_MS)
            reply(id, START)
            return
        }
        val transfer = active
        if (transfer == null || transfer.id != id) {
            diagnostic("artwork unexpected DATA id=$id kind=$kind packetBytes=${bytes.size}")
            reply(id, CANCEL); return
        }
        val isFirst = kind == FIRST || kind == FIRST_LAST
        val last = kind == LAST || kind == FIRST_LAST
        if (kind !in listOf(DATA, FIRST, LAST, FIRST_LAST) || isFirst != transfer.first ||
            bytes.size - 2 > transfer.size - transfer.buffer.size()) {
            reply(id, CANCEL); active = null; return
        }
        transfer.first = false
        transfer.buffer.write(bytes, 2, bytes.size - 2)
        if (isFirst || last) diagnostic("artwork DATA id=$id first=$isFirst last=$last received=${transfer.buffer.size()}/${transfer.size}")
        transfer.deadline = clock() + TIMEOUT_MS
        if (last) {
            active = null
            if (transfer.buffer.size() != transfer.size) { reply(id, FAILURE); return }
            val data = transfer.buffer.toByteArray()
            // The coordinator decodes JPEG on its artwork worker, outside the control loop.
            if (data.size < 3 || data[0] != 0xff.toByte() || data[1] != 0xd8.toByte()) {
                reply(id, FAILURE); return
            }
            reply(id, if (complete(transfer.revision, id, data)) SUCCESS else FAILURE)
        }
    }

    @Synchronized fun expire() {
        pendingSetups.filterValues { clock() >= it.second }.keys.toList().forEach {
            pendingSetups.remove(it); reply(it, CANCEL)
        }
        active?.takeIf { clock() >= it.deadline }?.let { active = null; reply(it.id, CANCEL) }
    }
    private fun reply(id: Int, kind: Int) {
        val action = when (kind) { START -> "START"; CANCEL -> "CANCEL"; SUCCESS -> "SUCCESS"; else -> "FAILURE" }
        try {
            send(byteArrayOf(id.toByte(), kind.toByte()))
            diagnostic("artwork $action queued id=$id revision=$revision")
        } catch (failure: Exception) {
            // A rejected reply belongs to this transfer, not to the metadata/control loop or
            // the lifetime of the file receiver. Later transfers must still be processed.
            if (active?.id == id) active = null
            pendingSetups.remove(id)
            diagnostic("artwork $action SEND FAILED id=$id revision=$revision: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }
    private fun diagnostic(message: String) {
        try { onDiagnostic(message) } catch (_: Exception) { /* Logging must not affect transfer. */ }
    }
    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        private const val TIMEOUT_MS = 15_000L
        private const val SETUP = 4; private const val START = 1; private const val CANCEL = 2
        private const val DATA = 0; private const val FIRST = 0x80; private const val LAST = 0x40
        private const val FIRST_LAST = 0xc0; private const val SUCCESS = 5; private const val FAILURE = 6
    }
}
