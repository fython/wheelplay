import test from 'node:test';
import assert from 'node:assert/strict';
import { createBrowserAudio, createMicResampler, decodeAudioPacket } from '../common/src/main/assets/web/browser-audio.js';

function packet(samples, sequence = 1) {
  const bytes = new ArrayBuffer(20 + samples.length * 2), view = new DataView(bytes);
  view.setUint32(0, 0x31415057, true); view.setInt32(4, 7, true);
  view.setUint32(8, 48000, true); view.setUint16(12, 1, true); view.setUint32(16, sequence, true);
  samples.forEach((sample, i) => view.setInt16(20 + 2 * i, sample, true));
  return bytes;
}

test('PCM frames validate format and preserve stream, rate, channels and sequence', () => {
  const decoded = decodeAudioPacket(packet([16384, -16384], 9));
  assert.deepEqual([decoded.stream, decoded.rate, decoded.channels, decoded.sequence], [7, 48000, 1, 9]);
  assert.deepEqual([...decoded.pcm], [16384, -16384]);
  const malformed = packet([1]); new DataView(malformed).setUint16(12, 3, true);
  assert.equal(decodeAudioPacket(malformed), null);
  assert.equal(decodeAudioPacket(new Uint8Array([1]).buffer), null);
});

test('continuous 48-to-16 kHz microphone resampling emits 20 ms bounded PCM frames', () => {
  const frames = [], resample = createMicResampler(48000, 16000, 1, frame => frames.push(new Int16Array(frame)));
  for (let i = 0; i < 15; i++) resample(new Float32Array(64).fill(.5));
  assert.equal(frames.length, 1);
  assert.equal(frames[0].length, 320);
  assert.ok([...frames[0]].every(sample => Math.abs(sample - 16384) <= 1));
});

test('playback takes ownership only after audio context starts, ACKs PCM and releases on disconnect', async () => {
  const controls = [], sources = [], states = [];
  const context = {
    state: 'suspended', currentTime: 1, destination: {},
    async resume() { this.state = 'running'; }, async close() { this.state = 'closed'; },
    createBuffer(channels, count, rate) { return { channels, count, rate, data: new Float32Array(count), getChannelData() { return this.data; } }; },
    createBufferSource() { const node = { connect() {}, disconnect() {}, start(at) { this.at = at; }, stop() { this.stopped = true; } }; sources.push(node); return node; },
  };
  const audio = createBrowserAudio({ send: value => controls.push(value), sendBinary() {}, createContext: () => context,
    onState: value => states.push(value) });
  audio.attach(); assert.equal(controls.length, 0);
  audio.control({ type: 'audio-route', playback: true, microphone: false });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(controls.at(-1).type, 'audio-ready'); assert.equal(controls.at(-1).playback, true);
  const acknowledgements = controls.length;
  audio.control({ type: 'audio-route', playback: true, microphone: false });
  assert.equal(controls.length, acknowledgements + 1);
  assert.equal(controls.at(-1).playback, true);
  assert.equal(audio.receive(packet([16384, -16384])), true);
  assert.equal(controls.at(-1).sequence, 1);
  assert.equal(sources[0].at, 1.06);
  assert.equal(sources[0].buffer.data[0], .5);
  audio.close(); assert.equal(sources[0].stopped, true); assert.equal(context.state, 'closed');
  assert.equal(states.at(-1).playback, false);
});

test('insecure page cannot request a browser microphone; secure capture stops and restores Android', async () => {
  let requested = 0, stopped = 0;
  const controls = [], binaries = [], states = [];
  const track = { enabled: false, stop() { stopped++; } };
  const port = { postMessage(value) { this.config = value; } };
  const node = { port, connect() {}, disconnect() {} };
  const context = {
    sampleRate: 48000, state: 'running', destination: {}, audioWorklet: { async addModule() {} },
    async resume() {}, async close() {}, createMediaStreamSource() { return { connect() {}, disconnect() {} }; },
  };
  let allowed = false;
  const audio = createBrowserAudio({ send: value => controls.push(value), sendBinary: value => binaries.push(value),
    createContext: () => context, createWorklet: () => node, secure: () => allowed,
    getMicrophone: async () => { requested++; return { getTracks: () => [track], getAudioTracks: () => [track] }; },
    onState: value => states.push(value) });
  audio.attach(); audio.control({ type: 'audio-route', playback: false, microphone: true });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requested, 0); assert.match(states.at(-1).error, /HTTPS/);
  allowed = true;
  audio.control({ type: 'audio-route', playback: false, microphone: false });
  audio.control({ type: 'audio-route', playback: false, microphone: true });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requested, 1); assert.equal(audio.microphone, true); assert.equal(track.enabled, false);
  audio.control({ type: 'mic-config', id: 42, rate: 16000, channels: 1 });
  assert.equal(track.enabled, true); assert.equal(port.config.rate, 16000);
  port.onmessage({ data: new Int16Array(320).fill(55).buffer });
  assert.equal(new DataView(binaries[0]).getInt32(4, true), 42);
  audio.control({ type: 'mic-stop', id: 42 }); assert.equal(track.enabled, false);
  audio.control({ type: 'audio-route', playback: false, microphone: false });
  assert.equal(stopped, 1); assert.equal(controls.at(-1).microphone, false);
});
