import test from 'node:test';
import assert from 'node:assert/strict';
import { createTrackCanvasRenderer } from '../common/src/main/assets/web/app.js';
const flush = () => new Promise(resolve => setImmediate(resolve));

function fixture({ contextAvailable = true, constructError = false, onError = () => {} } = {}) {
  const draws = [], ready = [], sizes = [], errors = [], readers = [], configurations = [];
  const context = { drawImage: (...args) => draws.push(args) };
  const canvas = { hidden: true, width: 0, height: 0, getContext: () => contextAvailable ? context : null };
  const renderer = createTrackCanvasRenderer(canvas, {
    onReady: size => ready.push(size), onResize: size => sizes.push(size),
    onError: reason => { errors.push(reason); onError(reason); },
    createProcessor(init) {
      if (constructError) throw new Error('not supported');
      configurations.push(init);
      const pending = [];
      const reader = {
        pending, read: () => new Promise((resolve, reject) => pending.push({ resolve, reject })),
        cancel() { this.cancels = (this.cancels || 0) + 1; },
        releaseLock() { this.releases = (this.releases || 0) + 1; },
      };
      readers.push(reader); return { readable: { getReader: () => reader } };
    },
  });
  return { renderer, canvas, context, draws, ready, sizes, errors, readers, configurations,
    async frame(width = 1280, height = 720, reader = readers.at(-1)) {
      const frame = { displayWidth: width, displayHeight: height, closes: 0, close() { this.closes++; } };
      reader.pending.shift().resolve({ value: frame, done: false }); await flush(); return frame;
    },
  };
}

test('processor bounds its queue, waits for valid output, and reports first draw and actual dimensions', async () => {
  const f = fixture(), track = {};
  f.renderer.start(track);
  assert.deepEqual(f.configurations, [{ track, maxBufferSize: 1 }]);
  assert.equal(f.ready.length, 0); assert.equal(f.canvas.hidden, true);
  assert.equal((await f.frame(0, 720)).closes, 1);
  assert.equal(f.draws.length, 0);
  const frame = await f.frame(); assert.equal(frame.closes, 1);
  assert.equal(f.ready.length, 1); assert.equal(f.canvas.hidden, false);
  await f.frame(720, 1280);
  assert.deepEqual(f.sizes, [{ width: 720, height: 1280 }]);
  assert.deepEqual(f.renderer.snapshot(), { width: 720, height: 1280, renderedFrames: 2, presentedFrames: null });
});

test('stop cancels without waiting; a late frame is released without drawing into a new session', async () => {
  const f = fixture(); f.renderer.start({});
  const old = f.readers[0]; f.renderer.stop(); f.renderer.stop();
  assert.equal(old.cancels, 1); assert.equal(f.canvas.width, 0);
  f.renderer.start({}); await f.frame(720, 1280);
  const frame = await f.frame(1920, 1080, old);
  assert.equal(frame.closes, 1); assert.equal(old.releases, 1);
  assert.equal(f.draws.length, 1); assert.equal(f.canvas.width, 720);
  assert.equal(f.errors.length, 0);
});

test('a drawing exception releases the current frame and clears the canvas exactly once', async () => {
  const f = fixture(); f.renderer.start({});
  f.context.drawImage = () => { throw new Error('draw failed'); };
  const frame = await f.frame();
  assert.equal(frame.closes, 1); assert.deepEqual(f.errors, ['renderer-error']);
  assert.equal(f.readers[0].cancels, 1); assert.equal(f.readers[0].releases, 1);
  assert.equal(f.canvas.hidden, true); assert.equal(f.canvas.width, 0);
});

test('reader rejection and unexpected end report an error and release the lock', async () => {
  for (const end of [false, true]) {
    const f = fixture(); f.renderer.start({});
    const reader = f.readers[0], pending = reader.pending.shift();
    if (end) pending.resolve({ done: true }); else pending.reject(new Error('read failed'));
    await flush();
    assert.deepEqual(f.errors, [end ? 'track-ended' : 'renderer-error']);
    assert.equal(reader.releases, 1); assert.equal(f.canvas.hidden, true);
  }
});

test('missing context and processor construction failures do not leave a read loop', () => {
  for (const options of [{ contextAvailable: false }, { constructError: true }]) {
    const f = fixture(options); f.renderer.start({});
    assert.deepEqual(f.errors, ['renderer-error']); assert.equal(f.readers.length, 0);
    assert.equal(f.canvas.width, 0); assert.equal(f.canvas.hidden, true);
  }
});

test('a canceled read rejection cannot report an error into a replacement session', async () => {
  const f = fixture(); f.renderer.start({}); const old = f.readers[0];
  f.renderer.stop(); f.renderer.start({});
  old.pending.shift().reject(new Error('late cancellation')); await flush();
  assert.equal(f.errors.length, 0); assert.equal(old.releases, 1);
  await f.frame(); assert.equal(f.ready.length, 1);
});

test('a read failure preserves the displayed frame for the error callback before releasing it', async () => {
  let captured;
  const f = fixture({ onError() {
    captured = { hidden: f.canvas.hidden, width: f.canvas.width, height: f.canvas.height };
  } });
  f.renderer.start({}); await f.frame();
  f.readers[0].pending.shift().reject(new Error('read failed')); await flush();
  assert.deepEqual(captured, { hidden: false, width: 1280, height: 720 });
  assert.equal(f.canvas.width, 0); assert.equal(f.canvas.hidden, true);
  assert.equal(f.readers[0].cancels, 1); assert.equal(f.readers[0].releases, 1);
});

test('error cleanup does not stop a renderer restarted by its error callback', async () => {
  const f = fixture({ onError() { f.renderer.start({}); } });
  f.renderer.start({}); await f.frame();
  const old = f.readers[0]; old.pending.shift().reject(new Error('read failed')); await flush();
  await f.frame(720, 1280);
  assert.equal(old.cancels, 1); assert.equal(old.releases, 1);
  assert.equal(f.readers[1].cancels, undefined);
  assert.equal(f.canvas.hidden, false); assert.equal(f.canvas.width, 720);
  f.renderer.stop();
});
