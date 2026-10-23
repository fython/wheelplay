package com.shilapi.xcertplay.projection.androidauto

import android.content.Context
import android.media.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** PCM endpoints are bounded independently of AAP acknowledgements and browser video. */
internal class AapAudio(context: Context, private val sendMic: (ByteArray) -> Boolean,
    private val focusChanged: (Int) -> Unit, private val failed: (Throwable) -> Unit,
    private val microphoneAllowed: () -> Boolean) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val closed = AtomicBoolean()
    private val queue = ArrayBlockingQueue<Pair<Int, ByteArray>>(32)
    private val tracks = mutableMapOf<Int, AudioTrack>()
    @Volatile private var focusState = 1
    @Volatile private var focus: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { value ->
        focusState = when (value) {
            AudioManager.AUDIOFOCUS_GAIN -> 1
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 4
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> 5
            else -> 3
        }
        if (focusState == 3 || focusState == 5) queue.clear()
        focusChanged(focusState)
    }
    private val stoppingChannels = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val player = Thread({
        try {
            while (!closed.get()) {
                while (true) {
                    val stop = stoppingChannels.poll() ?: break
                    tracks.remove(stop)?.release()
                }
                val (channel, bytes) = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (focusState == 3 || focusState == 5) continue
                val track = tracks.getOrPut(channel) {
                    val rate = if (channel == 6) 48000 else 16000
                    val mask = if (channel == 6) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                    val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0)
                    AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(if (channel == 6) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(if (channel == 6) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum, rate / 5 * if (channel == 6) 4 else 2)).build().apply { play() }
                }
                // Never let a slow audio device block the protocol reader. Dropped PCM has no prediction dependency.
                track.setVolume(if (focusState == 4) 0.2f else 1f)
                track.write(bytes, 0, bytes.size, AudioTrack.WRITE_NON_BLOCKING)
            }
        } catch (error: Throwable) { if (!closed.get()) failed(error) }
        finally { tracks.values.forEach { it.release() }; tracks.clear() }
    }, "aa-pcm").apply { start() }
    @Volatile private var recorder: AudioRecord? = null
    private var micThread: Thread? = null
    fun offer(channel: Int, bytes: ByteArray) {
        if (closed.get()) return
        if (!queue.offer(channel to bytes)) { queue.poll(); queue.offer(channel to bytes) }
    }
    fun stopChannel(channel: Int) {
        queue.removeIf { it.first == channel }
        stoppingChannels.offer(channel)
    }
    @Synchronized fun requestFocus(request: Int): Int {
        focus?.let { manager.abandonAudioFocusRequest(it) }
        focus = null
        if (request == 4 || closed.get()) { focusState = 3; queue.clear(); return 3 }
        val gain = when (request) {
            2 -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            3 -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else -> AudioManager.AUDIOFOCUS_GAIN
        }
        val next = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener).build()
        focus = next
        val state = if (manager.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (request == 1) 1 else 2
        } else 3
        focusState = state
        return state
    }
    @android.annotation.SuppressLint("MissingPermission") // Provider gates microphone capability on RECORD_AUDIO.
    @Synchronized fun microphone(open: Boolean) {
        stopMicrophone()
        if (!open || closed.get() || !microphoneAllowed()) return
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
        try { check(record.state == AudioRecord.STATE_INITIALIZED); record.startRecording() }
        catch (t: Throwable) { record.release(); throw t }
        recorder = record
        micThread = Thread({
            val buffer = ByteArray(640)
            try {
                while (recorder === record && !closed.get() && microphoneAllowed()) {
                    val count = record.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    if (!sendMic(buffer.copyOf(count))) break
                }
            } catch (error: Throwable) { if (!closed.get()) failed(error) }
            finally { if (recorder === record) recorder = null; record.release() }
        }, "aa-mic").apply { start() }
    }
    private fun stopMicrophone() {
        val record = recorder; recorder = null
        if (record != null) runCatching { record.stop() }
        micThread?.join(3000)
        check(micThread?.isAlive != true) { "AA microphone did not stop" }
        micThread = null
    }
    @Synchronized override fun close() {
        closed.set(true); stopMicrophone(); player.join(3000)
        check(!player.isAlive) { "AA audio did not stop" }
        queue.clear(); focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null
    }
}
