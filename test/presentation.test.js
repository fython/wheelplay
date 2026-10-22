import test from 'node:test';
import assert from 'node:assert/strict';
import { createPresentationController, createJpegPresenter, decodeVideoPacket } from '../common/src/main/assets/web/app.js';

function fixture() {
  let time = 100, next = 0;
  const timers = new Map(), changes = [];
  const controller = createPresentationController({ now: () => time,
    schedule(fn, delay) { const id = ++next; timers.set(id, { fn, at: time + delay }); return id; },
    cancel(id) { timers.delete(id); }, onChange: state => changes.push(state),
  });
  controller.open();
  const status = (extra = {}) => controller.status({ sessionId: 7, videoEpoch: 1, width: 1280, height: 720,
    sourceState: 'ready', inputReady: true, ...extra });
  return { controller, timers, changes, status,
    frame(epoch = 1, width = 1280, height = 720) {
      return controller.present({ sessionId: 7, videoEpoch: epoch }, width, height, 'jpeg');
    },
    advance(ms) {
      time += ms;
      for (;;) {
        const entry = [...timers].find(([, timer]) => timer.at <= time);
        if (!entry) break;
        timers.delete(entry[0]); entry[1].fn();
      }
    },
  };
}

test('source-ready without a presented frame keeps waiting; static pictures never expire', () => {
  const f = fixture(); f.status();
  assert.equal(f.controller.current.visible, false);
  f.frame(); f.advance(60_000);
  assert.equal(f.controller.current.state, 'showing');
  assert.equal(f.controller.current.interactive, true);
  assert.equal(f.timers.size, 0);
});

test('a gap retains the picture, immediately stops touch, and expires without another status', () => {
  const f = fixture(); f.status(); f.frame();
  f.status({ sourceState: 'recovering', inputReady: false });
  assert.equal(f.controller.current.state, 'recovering');
  assert.equal(f.controller.current.interactive, false);
  f.advance(1999); assert.equal(f.changes.at(-1).visible, true);
  f.advance(1); assert.equal(f.changes.at(-1).state, 'waiting');
  assert.equal(f.changes.at(-1).visible, false);
});

test('repeated negative statuses and old frames cannot extend the recovery deadline', () => {
  const f = fixture(); f.status(); f.frame(); f.status({ sourceState: 'recovering' });
  f.advance(1500); f.status({ sourceState: 'recovering' }); f.frame();
  f.advance(500); assert.equal(f.controller.current.state, 'waiting');
  f.status({ sourceState: 'recovering' });
  assert.equal(f.controller.current.state, 'waiting');
});

test('restored availability reuses the same configuration static picture and cancels old timers', () => {
  const f = fixture(); f.status(); f.frame(); f.status({ sourceState: 'recovering' });
  const late = [...f.timers.values()][0].fn;
  f.advance(500); f.status();
  f.advance(2000); late();
  assert.equal(f.controller.current.state, 'showing');
  assert.equal(f.timers.size, 0);
});

test('input failure disables touch without covering a healthy picture', () => {
  const f = fixture(); f.status(); f.frame(); f.controller.inputUnavailable();
  assert.equal(f.controller.current.visible, true);
  assert.equal(f.controller.current.interactive, false);
  f.status(); assert.equal(f.controller.current.interactive, true);
});

test('a same-size rebuild needs its own frame even when the source is ready', () => {
  const f = fixture(); f.status(); f.frame();
  f.status({ videoEpoch: 2 });
  assert.equal(f.controller.current.state, 'recovering');
  assert.equal(f.controller.current.interactive, false);
  assert.equal(f.frame(1), false);
  assert.equal(f.frame(2), true);
  assert.equal(f.controller.current.state, 'showing');
});

test('old status, SDP or frame versions cannot replace a newer configuration', () => {
  const f = fixture(); f.status({ videoEpoch: 2 }); f.frame(2);
  assert.equal(f.controller.status({ sessionId: 7, videoEpoch: 1, sourceState: 'stopped' }), false);
  assert.equal(f.controller.accepts({ sessionId: 8, videoEpoch: 2 }), false);
  assert.equal(f.controller.present({ sessionId: 7, videoEpoch: 1 }, 720, 1280, 'rtc'), false);
  assert.equal(f.controller.current.state, 'showing');
});

test('a frame arriving before status invalidates the previous presentation immediately', () => {
  const f = fixture(); f.status(); f.frame();
  assert.equal(f.controller.accepts({ sessionId: 7, videoEpoch: 2 }), true);
  assert.equal(f.changes.at(-1).interactive, false);
  assert.equal(f.controller.current.state, 'recovering');
});

test('a stopped source and a disconnected socket end the hold immediately', () => {
  for (const close of [false, true]) {
    const f = fixture(); f.status(); f.frame(); f.status({ sourceState: 'recovering' });
    if (close) f.controller.close(); else f.status({ sourceState: 'stopped' });
    assert.equal(f.controller.current.visible, false);
    assert.equal(f.controller.current.interactive, false);
    assert.equal(f.timers.size, 0);
  }
});

test('old session deadlines and decode completions cannot affect a reconnected session', () => {
  const f = fixture(); f.status(); f.frame(); f.status({ sourceState: 'recovering' });
  const late = [...f.timers.values()][0].fn, oldRevision = f.controller.current.revision;
  f.controller.close(); f.controller.open(); f.status({ sessionId: 8, videoEpoch: 4 });
  f.controller.present({ sessionId: 8, videoEpoch: 4 }, 720, 1280, 'rtc');
  late();
  assert.equal(f.controller.present({ sessionId: 7, videoEpoch: 1 }, 1280, 720, 'jpeg', oldRevision), false);
  assert.equal(f.controller.current.state, 'showing');
});

test('legacy status and raw frames remain supported without using naturalWidth during decode', () => {
  const f = fixture(); f.controller.status({ streaming: true, width: 1280, height: 720 });
  f.controller.present({}, 1280, 720, 'jpeg');
  f.controller.status({ streaming: true, width: 1280, height: 720 });
  assert.equal(f.controller.current.interactive, true);
  f.controller.status({ streaming: false, width: 720, height: 1280 });
  assert.equal(f.controller.current.interactive, false);
});

function jpegFixture() {
  const revoked = [], committed = [], completed = [], errors = [];
  let sequence = 0;
  const images = [0, 1].map(id => ({ id, hidden: true, naturalWidth: 1280, naturalHeight: 720,
    removeAttribute() { delete this.src; } }));
  const presenter = createJpegPresenter(images, {
    createUrl: () => `blob:${++sequence}`, revokeUrl: url => revoked.push(url),
    onFrame: (...args) => committed.push(args), onError: () => errors.push(true),
  });
  const load = (valid = () => true) => {
    presenter.load({}, { sequence }, valid, () => completed.push(sequence));
    return images.find(image => image.onload);
  };
  return { images, presenter, load, revoked, committed, completed, errors };
}

test('JPEG decode never changes or revokes the displayed image until successful commit', () => {
  const f = jpegFixture(), first = f.load(); first.onload();
  const second = f.load();
  assert.equal(first.hidden, false); assert.equal(first.src, 'blob:1');
  assert.equal(second.hidden, true); assert.deepEqual(f.revoked, []);
  second.naturalWidth = 720; second.naturalHeight = 1280; second.onload();
  assert.equal(second.hidden, false); assert.equal(first.hidden, true);
  assert.deepEqual(f.revoked, ['blob:1']);
  assert.deepEqual(f.committed.at(-1).slice(1), [720, 1280]);
});

test('a failed JPEG keeps the previous image and acknowledges the failed packet', () => {
  const f = jpegFixture(), first = f.load(); first.onload();
  f.load().onerror();
  assert.equal(first.hidden, false); assert.equal(first.src, 'blob:1');
  assert.deepEqual(f.revoked, ['blob:2']); assert.equal(f.errors.length, 1);
  assert.equal(f.completed.length, 2);
});

test('cancelled and late JPEG loads cannot commit or acknowledge into a new session', () => {
  const f = jpegFixture(), image = f.load(), late = image.onload;
  f.presenter.cancelPending();
  assert.equal(f.completed.length, 1);
  late(); assert.equal(f.committed.length, 0); assert.equal(f.completed.length, 1);
  const current = f.load(() => false); current.onload();
  assert.equal(f.committed.length, 0);
  f.presenter.clear(); assert.equal(f.images.every(image => image.hidden), true);
});

test('V2 video headers preserve configuration identity and do not consume PCM or raw JPEG', () => {
  const buffer = new ArrayBuffer(39), view = new DataView(buffer);
  view.setUint32(0, 0x32565057, true);
  view.setFloat64(4, 9007199254740991, true); view.setFloat64(12, 42, true); view.setFloat64(20, 99, true);
  view.setUint32(28, 720, true); view.setUint32(32, 1280, true);
  new Uint8Array(buffer, 36).set([0xff, 0xd8, 0xff]);
  const packet = decodeVideoPacket(buffer);
  assert.equal(packet.sessionId, 9007199254740991); assert.equal(packet.videoEpoch, 42);
  assert.equal(packet.sequence, 99); assert.equal(packet.width, 720); assert.equal(packet.height, 1280);
  assert.deepEqual([...new Uint8Array(packet.jpeg)], [0xff, 0xd8, 0xff]);
  assert.throws(() => decodeVideoPacket(buffer.slice(0, 36)), /Truncated/);
  view.setFloat64(12, NaN, true); assert.throws(() => decodeVideoPacket(buffer), /Invalid/);
  view.setUint32(0, 0x31415057, true); assert.equal(decodeVideoPacket(buffer), null);
  assert.equal(decodeVideoPacket(new Uint8Array([0xff, 0xd8, 0xff, 0]).buffer), null);
});
