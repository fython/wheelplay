import test from 'node:test';
import assert from 'node:assert/strict';
import { createJpegPresenter, createControlOutbox, decodeVideoPacket, probeWebp } from '../common/src/main/assets/web/app.js';

test('WebP probe checks decoded dimensions and cleans up on success, failure and timeout', async () => {
  for (const result of ['supported', 'wrong-size', 'error', 'timeout']) {
    const image = { naturalWidth: result === 'wrong-size' ? 0 : 2, naturalHeight: 2, removeAttribute() { delete this.src; } };
    let deadline, cancelled, fixture;
    const revoked = [];
    const promise = probeWebp({ createImage: () => image,
      createUrl: data => { fixture = data; return 'blob:webp-probe'; }, revokeUrl: url => revoked.push(url),
      schedule: fn => { deadline = fn; return 7; }, cancel: id => { cancelled = id; } });
    assert.equal(image.src, 'blob:webp-probe'); assert.equal(fixture.type, 'image/webp');
    assert.equal(fixture.size, 68);
    const late = image.onload;
    if (result === 'timeout') deadline(); else if (result === 'error') image.onerror(); else image.onload();
    assert.equal(await promise, result === 'supported');
    late(); assert.equal(cancelled, 7); assert.equal(image.onload, null); assert.equal(image.src, undefined);
    assert.deepEqual(revoked, ['blob:webp-probe']);
  }
  assert.equal(await probeWebp({ createImage: () => { throw new Error('unavailable'); } }), false);
});

function fixture() {
  const images = [0, 1, 2].map(id => ({ id, hidden: true, naturalWidth: 320, naturalHeight: 180,
    removeAttribute() { delete this.src; } }));
  const committed = [], acked = [], revoked = [], errors = [];
  let url = 0;
  const presenter = createJpegPresenter(images, { createUrl: () => `blob:${++url}`,
    revokeUrl: value => revoked.push(value), onFrame: meta => committed.push(meta.sequence), onError: meta => errors.push(meta.sequence) });
  const load = sequence => {
    presenter.load({}, { sequence }, () => true, () => acked.push(sequence));
    return images.find(image => image.src === `blob:${url}`);
  };
  return { images, presenter, committed, acked, revoked, errors, load };
}

test('two decodes overlap while the front stays visible; newer completion retires older callbacks exactly once', () => {
  const f = fixture(), front = f.load(1); front.onload();
  const second = f.load(2), late = second.onload, third = f.load(3);
  assert.equal(front.hidden, false); assert.ok(second.onload); assert.ok(third.onload);
  third.onload(); late();
  assert.deepEqual(f.committed, [1, 3]); assert.deepEqual(f.acked.sort(), [1, 2, 3]);
  assert.equal(third.hidden, false); assert.equal(second.hidden, true); assert.equal(front.hidden, true);
  assert.equal(f.revoked.length, 2);
});

test('back-to-back frames make progress with bounded work rather than starving an ongoing decode', () => {
  const f = fixture(), first = f.load(1), second = f.load(2);
  first.onload(); assert.deepEqual(f.committed, [1]);
  const third = f.load(3); second.onload(); third.onload();
  assert.deepEqual(f.committed, [1, 2, 3]);
  const fourth = f.load(4), late = fourth.onload;
  f.load(5); f.load(6); late();
  assert.equal(f.images.filter(image => image.onload).length, 2);
  assert.equal(f.acked.filter(id => id === 4).length, 1);
  f.presenter.clear();
  assert.deepEqual(f.acked.sort(), [1, 2, 3, 4, 5, 6]);
  assert.equal(f.images.every(image => image.hidden), true);
});

test('a failed newer decode preserves the front and permits an older successful decode; cancellation isolates sessions', () => {
  const f = fixture(), front = f.load(1); front.onload();
  const second = f.load(2), third = f.load(3); third.onerror();
  assert.equal(front.hidden, false); second.onload(); assert.deepEqual(f.committed, [1, 2]);
  const next = f.load(4), late = next.onload; f.load(5); f.presenter.cancelPending(); late();
  assert.deepEqual(f.committed, [1, 2]); assert.deepEqual(f.errors, [3]);
  assert.deepEqual(f.acked.sort(), [1, 2, 3, 4, 5]);
});

test('v3 identifies JPEG and WebP explicitly and rejects truncated or unknown formats', () => {
  for (const format of [1, 2]) {
    const bytes = new ArrayBuffer(43), header = new DataView(bytes);
    header.setUint32(0, 0x33565057, true); header.setFloat64(4, 7, true);
    header.setFloat64(12, 42, true); header.setFloat64(20, 99, true);
    header.setUint32(28, 320, true); header.setUint32(32, 180, true); header.setUint32(36, format, true);
    const packet = decodeVideoPacket(bytes);
    assert.equal(packet.protocol, 3); assert.equal(packet.mime, format === 1 ? 'image/jpeg' : 'image/webp');
    assert.equal(packet.jpeg.byteLength, 3); assert.equal(packet.sequence, 99);
    assert.throws(() => decodeVideoPacket(bytes.slice(0, 40)), /Truncated/);
    header.setUint32(36, 3, true); assert.throws(() => decodeVideoPacket(bytes), /Invalid image format/);
  }
});

test('congested outbox preserves each image ACK and bounds duplicates without losing format fallback', () => {
  const sent = [], tasks = new Map(); let id = 0;
  const socket = { readyState: 1, bufferedAmount: 8192, send: text => sent.push(JSON.parse(text)) };
  const outbox = createControlOutbox(socket, { schedule: fn => { tasks.set(++id, fn); return id; }, cancel: key => tasks.delete(key) });
  const ack = sequence => ({ type: 'ack', sessionId: 7, videoEpoch: 1, sequence });
  outbox.send(ack(2)); outbox.send(ack(1)); outbox.send(ack(2));
  outbox.send({ type: 'image-format', format: 'jpeg' }); socket.bufferedAmount = 0;
  for (const [key, fn] of [...tasks]) { tasks.delete(key); fn(); }
  assert.deepEqual(sent, [ack(2), ack(1), { type: 'image-format', format: 'jpeg' }]);
  outbox.close(); assert.equal(tasks.size, 0);
});
