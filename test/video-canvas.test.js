import test from 'node:test';
import assert from 'node:assert/strict';
import { createVideoCanvasRenderer } from '../common/src/main/assets/web/app.js';

function fixture({ frameCallbacks = true, quality = true } = {}) {
  const animation = new Map(), callbacks = new Map(), draws = [], sizes = [], errors = [], classes = new Set();
  let sequence = 0, clock = 0, frames = 1;
  const video = { hidden: true, readyState: 2, videoWidth: 1280, videoHeight: 720, currentTime: 0,
    classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); } },
  };
  if (frameCallbacks) {
    video.requestVideoFrameCallback = callback => { const id = ++sequence; callbacks.set(id, callback); return id; };
    video.cancelVideoFrameCallback = id => callbacks.delete(id);
  }
  if (quality) video.getVideoPlaybackQuality = () => ({ totalVideoFrames: frames });
  const context = { drawImage: (...args) => draws.push(args) };
  const canvas = { width: 0, height: 0, hidden: true, getContext: () => context };
  const renderer = createVideoCanvasRenderer(video, canvas, {
    onFrame: (width, height) => sizes.push([width, height]), onError: error => errors.push(error), now: () => clock,
    requestFrame(callback) { const id = ++sequence; animation.set(id, callback); return id; },
    cancelFrame: id => animation.delete(id),
  });
  return { renderer, video, canvas, context, classes, animation, callbacks, draws, sizes, errors,
    frame(at) {
      clock = at; frames++; video.currentTime = at / 1000;
      const [id, callback] = [...callbacks][0]; callbacks.delete(id);
      callback(at, { mediaTime: video.currentTime });
    },
    refresh(at, advance = true) {
      clock = at; if (advance) video.currentTime = at / 1000;
      const [id, callback] = [...animation][0]; animation.delete(id); callback(at);
    },
  };
}

test('offscreen video stays laid out and decoded frames are drawn at their source size', () => {
  const f = fixture(); f.renderer.prepare();
  assert.equal(f.video.hidden, false); assert.equal(f.classes.has('offscreen-video'), true);
  f.renderer.start();
  assert.equal(f.canvas.hidden, false);
  assert.deepEqual(f.draws[0], [f.video, 0, 0, 1280, 720]);
  f.refresh(16); assert.equal(f.draws.length, 1); // Prefer video callbacks while they are healthy.
  f.frame(33); assert.equal(f.draws.length, 2);
  f.video.videoWidth = 1920; f.video.videoHeight = 1080; f.frame(66);
  assert.deepEqual(f.sizes, [[1280, 720], [1920, 1080]]);
  assert.equal(f.canvas.width, 1920); assert.equal(f.canvas.height, 1080);
});

test('older browsers draw only new frames using the animation callback', () => {
  const f = fixture({ frameCallbacks: false, quality: false }); f.renderer.start();
  f.refresh(16, false); assert.equal(f.draws.length, 1);
  f.refresh(33); assert.equal(f.draws.length, 2);
  f.video.videoWidth = 720; f.video.videoHeight = 1280; f.refresh(34, false);
  assert.deepEqual(f.sizes.at(-1), [720, 1280]); // Resize even when mediaTime has not changed.
});

test('stalled compositor callbacks recover even if its video frame counter is frozen', () => {
  const f = fixture(); f.renderer.start();
  f.refresh(249); assert.equal(f.draws.length, 1);
  f.refresh(250); assert.equal(f.draws.length, 2);
  f.refresh(266); assert.equal(f.draws.length, 3);
  f.frame(300); assert.equal(f.draws.length, 4);
  f.refresh(316); assert.equal(f.draws.length, 4); // Video callback recovered.
});

test('Canvas stays hidden until a decoded frame exists', () => {
  const f = fixture(); f.video.readyState = 1; f.renderer.start();
  assert.equal(f.canvas.hidden, true); assert.equal(f.draws.length, 0);
  f.video.readyState = 2; f.frame(33);
  assert.equal(f.canvas.hidden, false); assert.equal(f.sizes.length, 1);
});

test('stop releases buffers and late callbacks cannot resurrect a replacement renderer', () => {
  const f = fixture(); f.renderer.start();
  const lateVideo = [...f.callbacks.values()][0], lateAnimation = [...f.animation.values()][0];
  f.renderer.stop();
  assert.equal(f.callbacks.size, 0); assert.equal(f.animation.size, 0);
  assert.equal(f.canvas.hidden, true); assert.equal(f.canvas.width, 0); assert.equal(f.canvas.height, 0);
  assert.equal(f.video.hidden, true); assert.equal(f.classes.has('offscreen-video'), false);
  f.renderer.start(); const count = f.draws.length;
  lateVideo(100, { mediaTime: .1 }); lateAnimation(100);
  assert.equal(f.draws.length, count); assert.equal(f.callbacks.size, 1); assert.equal(f.animation.size, 1);
});

test('context failures and later draw errors stop both callback loops and report once', () => {
  const unavailable = fixture(); unavailable.canvas.getContext = () => null; unavailable.renderer.start();
  assert.equal(unavailable.errors.length, 1); assert.equal(unavailable.animation.size, 0);
  assert.equal(unavailable.canvas.hidden, true); assert.equal(unavailable.video.hidden, true);
  const f = fixture(); f.renderer.start();
  f.context.drawImage = () => { throw new Error('video surface inaccessible'); };
  f.frame(33);
  assert.equal(f.errors.length, 1); assert.equal(f.animation.size, 0); assert.equal(f.callbacks.size, 0);
  assert.equal(f.canvas.hidden, true); assert.equal(f.classes.has('offscreen-video'), false);
});
