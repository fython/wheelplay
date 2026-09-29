// Independent of video transport: the same paired channel works with JPEG and WebRTC.
export function decodeAudioPacket(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < 22) return null;
  const header = new DataView(buffer);
  if (header.getUint32(0, true) !== 0x31415057) return null;
  const stream = header.getInt32(4, true), rate = header.getUint32(8, true), channels = header.getUint16(12, true);
  if (rate < 8000 || rate > 96000 || channels < 1 || channels > 2 || (buffer.byteLength - 20) % (channels * 2)) return null;
  return { stream, rate, channels, sequence: header.getUint32(16, true), pcm: new Int16Array(buffer, 20) };
}

// Continuous box-filter resampling preserves phase across render quanta and averages before
// downsampling. CarPlay's negotiated mic rates are <= the browser's hardware capture rate.
export function createMicResampler(inputRate, outputRate, channels, emit) {
  if (inputRate < outputRate || channels < 1 || channels > 2) throw new Error('不支持此麦克风采样格式');
  const step = inputRate / outputRate, frameSamples = Math.round(outputRate / 50) * channels;
  let remaining = step, sum = 0, packet = new Int16Array(frameSamples), filled = 0;
  return input => {
    for (const sample of input) {
      let weight = 1;
      while (weight > 1e-9) {
        const part = Math.min(weight, remaining); sum += sample * part; remaining -= part; weight -= part;
        if (remaining < 1e-9) {
          const value = Math.max(-32768, Math.min(32767, Math.round(sum / step * 32767)));
          for (let channel = 0; channel < channels; channel++) packet[filled++] = value;
          sum = 0; remaining = step;
          if (filled === packet.length) { emit(packet.buffer); packet = new Int16Array(frameSamples); filled = 0; }
        }
      }
    }
  };
}

export function createBrowserAudio({
  send, sendBinary, onState = () => {},
  createContext = () => new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' }),
  getMicrophone = () => navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: false }),
  createWorklet = context => new AudioWorkletNode(context, 'wheelplay-microphone', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] }),
  secure = () => window.isSecureContext && location.protocol === 'https:' && !!navigator.mediaDevices?.getUserMedia,
} = {}) {
  let context = null, playback = false, microphone = false, connected = false;
  let playbackGeneration = 0, microphoneGeneration = 0;
  let desiredPlayback = false, desiredMicrophone = false, gestureRetry = false;
  let stream = null, source = null, worklet = null, micId = null, micSequence = 0;
  const streams = new Map(), nodes = new Set();
  function state(error = '') { onState({ playback, microphone, connected, error }); }
  function readyState() { if (connected) send({ type: 'audio-ready', playback, microphone }); }
  async function ready() {
    if (!context) context = createContext();
    await context.resume();
    if (context.state !== 'running') throw new Error('请点按画面允许播放音频');
    return context;
  }
  function stopPlayback(id = null) {
    for (const node of [...nodes]) if (id === null || node.stream === id) {
      try { node.stop(); } catch (_) {} nodes.delete(node);
    }
    if (id === null) streams.clear(); else streams.delete(id);
  }
  function stopMicrophone() {
    micId = null;
    if (worklet) { worklet.port.postMessage(null); worklet.port.onmessage = null; worklet.disconnect(); }
    source?.disconnect(); stream?.getTracks().forEach(track => track.stop());
    worklet = source = stream = null;
  }
  async function setPlayback(enabled) {
    const token = ++playbackGeneration;
    try {
      if (enabled) await ready();
      if (playbackGeneration !== token) return;
      playback = enabled;
      if (!enabled) stopPlayback();
      readyState(); state();
    } catch (error) { if (playbackGeneration === token) { playback = false; readyState(); state(error.message); } }
  }
  async function setMicrophone(enabled) {
    const token = ++microphoneGeneration;
    stopMicrophone(); microphone = false; readyState();
    if (!enabled) { state(); return; }
    try {
      if (!secure()) throw new Error('浏览器麦克风需要可信 HTTPS，请先打开 HTTPS 入口并配置证书。');
      const audio = await ready();
      if (!audio.audioWorklet) throw new Error('此浏览器不支持麦克风音频处理，请使用 Android 麦克风。');
      await audio.audioWorklet.addModule('/audio-worklet.js');
      if (microphoneGeneration !== token) return;
      const captured = await getMicrophone();
      if (microphoneGeneration !== token) { captured.getTracks().forEach(track => track.stop()); return; }
      stream = captured; captured.getAudioTracks().forEach(track => { track.enabled = false; });
      worklet = createWorklet(audio); source = audio.createMediaStreamSource(captured);
      source.connect(worklet); worklet.connect(audio.destination);
      worklet.port.onmessage = event => {
        if (!connected || !microphone || micId === null || microphoneGeneration !== token) return;
        const packet = new Uint8Array(12 + event.data.byteLength), header = new DataView(packet.buffer);
        header.setUint32(0, 0x314d5057, true); header.setInt32(4, micId, true); header.setUint32(8, micSequence++, true);
        packet.set(new Uint8Array(event.data), 12); sendBinary(packet.buffer);
      };
      captured.getAudioTracks().forEach(track => { track.onended = () => {
        if (microphoneGeneration === token) { microphone = false; stopMicrophone(); readyState(); state('麦克风已停止，音频输入已恢复到 Android。'); }
      }; });
      microphone = true; readyState(); state();
    } catch (error) {
      if (microphoneGeneration !== token) return;
      stopMicrophone(); microphone = false; readyState();
      state(error.name === 'NotAllowedError' ? '麦克风权限被拒绝，请允许此站点访问麦克风后重试。' : error.message);
    }
  }
  function receive(buffer) {
    const frame = decodeAudioPacket(buffer);
    if (!frame) return false;
    send({ type: 'audio-ack', sequence: frame.sequence });
    if (!playback || !connected || context?.state !== 'running') return true;
    const now = context.currentTime;
    let next = streams.get(frame.stream) ?? now + .06;
    if (next < now || next - now > .25) { stopPlayback(frame.stream); next = now + .06; }
    if (nodes.size >= 64) { stopPlayback(); next = now + .06; }
    const count = frame.pcm.length / frame.channels;
    const audioBuffer = context.createBuffer(frame.channels, count, frame.rate);
    for (let channel = 0; channel < frame.channels; channel++) {
      const output = audioBuffer.getChannelData(channel);
      for (let i = 0; i < count; i++) output[i] = frame.pcm[i * frame.channels + channel] / 32768;
    }
    const node = context.createBufferSource(); node.buffer = audioBuffer; node.stream = frame.stream;
    node.connect(context.destination); nodes.add(node);
    node.onended = () => { nodes.delete(node); node.disconnect(); };
    node.start(next); streams.set(frame.stream, next + count / frame.rate);
    return true;
  }
  function control(message) {
    if (message.type === 'audio-route') {
      const nextPlayback = message.playback === true, nextMicrophone = message.microphone === true;
      if (nextPlayback && !playback || nextMicrophone && !microphone) state('如果浏览器阻止自动播放，请点按画面继续。');
      if (desiredPlayback !== nextPlayback) { desiredPlayback = nextPlayback; if (connected) setPlayback(nextPlayback); }
      if (desiredMicrophone !== nextMicrophone) { desiredMicrophone = nextMicrophone; if (connected) setMicrophone(nextMicrophone); }
      gestureRetry = false;
      readyState();
      return true;
    }
    if (message.type === 'audio-stop') { stopPlayback(message.stream); return true; }
    if (message.type === 'audio-reset') { stopPlayback(); micId = null; worklet?.port.postMessage(null); stream?.getAudioTracks().forEach(t => { t.enabled = false; }); return true; }
    if (message.type === 'mic-stop') {
      if (message.id === micId) { micId = null; worklet?.port.postMessage(null); stream?.getAudioTracks().forEach(t => { t.enabled = false; }); }
      return true;
    }
    if (message.type !== 'mic-config') return false;
    if (!microphone || !worklet || !connected) return true;
    if (![1, 2].includes(message.channels) || message.rate < 8000 || message.rate > context.sampleRate) {
      setMicrophone(false); state('此麦克风采样格式不可用，已恢复 Android 输入。'); return true;
    }
    micId = message.id; micSequence = 0;
    worklet.port.postMessage({ rate: message.rate, channels: message.channels });
    stream.getAudioTracks().forEach(track => { track.enabled = true; });
    return true;
  }
  function close() {
    playbackGeneration++; microphoneGeneration++; connected = false; microphone = playback = false;
    desiredPlayback = desiredMicrophone = gestureRetry = false;
    stopMicrophone(); stopPlayback();
    const old = context; context = null; old?.close().catch(() => {}); state();
  }
  return { receive, control, close,
    attach() { connected = true; state(); },
    unlock() {
      if (!connected || gestureRetry) return;
      gestureRetry = true;
      if (desiredPlayback && !playback) setPlayback(true);
      if (desiredMicrophone && !microphone) setMicrophone(true);
    },
    get playback() { return playback; }, get microphone() { return microphone; },
  };
}
