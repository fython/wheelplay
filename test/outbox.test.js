import test from 'node:test';
import assert from 'node:assert/strict';
import { createControlOutbox } from '../common/src/main/assets/web/app.js';

function fixture() {
  let time = 0, failures = 0, id = 0;
  const timers = new Map(), sent = [];
  const socket = { readyState: 1, bufferedAmount: 8192, send: value => sent.push(JSON.parse(value)) };
  const outbox = createControlOutbox(socket, {
    now: () => time,
    schedule: (fn, delay) => { const key = ++id; timers.set(key, { fn, at: time + delay }); return key; },
    cancel: key => timers.delete(key), onFailure: () => failures++,
  });
  return { socket, outbox, sent, timers, get failures() { return failures; }, advance(to) {
    while (true) {
      const entry = [...timers].sort((a, b) => a[1].at - b[1].at)[0];
      if (!entry || entry[1].at > to) break;
      const [key, timer] = entry; timers.delete(key); time = timer.at; timer.fn();
    }
    time = to;
  } };
}
const touch = (x, down = true) => ({ type: 'touch', contacts: [{ id: 0, x, y: 0.5, down }] });

test('congested ACK is sent once after recovery, ahead of touches and heartbeat', () => {
  const f = fixture();
  f.outbox.send({ type: 'ping' });
  f.outbox.send(touch(0));
  f.outbox.send({ type: 'ack' });
  f.outbox.send({ type: 'ping' });
  f.advance(100); assert.equal(f.sent.length, 0); assert.equal(f.timers.size, 1);
  f.socket.bufferedAmount = 0; f.advance(110);
  assert.deepEqual(f.sent.map(m => m.type), ['ack', 'touch', 'ping']);
  f.advance(2000); assert.equal(f.sent.length, 3); assert.equal(f.timers.size, 0);
});

test('moves merge only within a gesture and down/up/release edges remain ordered', () => {
  const f = fixture();
  f.outbox.send(touch(0));
  for (let i = 1; i <= 100; i++) f.outbox.send(touch(i / 100), 'move');
  f.outbox.send(touch(1, false));
  f.outbox.send(touch(0.2));
  f.outbox.send(touch(0.3), 'move');
  f.outbox.send({ type: 'touch', contacts: [] });
  f.socket.bufferedAmount = 0; f.advance(10);
  assert.deepEqual(f.sent, [touch(0), touch(1), touch(1, false), touch(0.2), touch(0.3), { type: 'touch', contacts: [] }]);
});

test('continued congestion ends the connection instead of retaining stale controls forever', () => {
  const f = fixture(); f.outbox.send(touch(0)); f.outbox.send({ type: 'ack' });
  f.advance(1490); assert.equal(f.failures, 0);
  f.advance(1500); assert.equal(f.failures, 1); assert.equal(f.timers.size, 0);
  f.socket.bufferedAmount = 0; f.advance(3000);
  assert.equal(f.outbox.send(touch(0.1)), false); assert.deepEqual(f.sent, []);
});

test('touch edge queue is bounded and closes on overflow', () => {
  const f = fixture();
  for (let i = 0; i < 65; i++) f.outbox.send(touch(0, i % 2 === 0));
  assert.equal(f.failures, 1); assert.equal(f.timers.size, 0);
  assert.equal(f.outbox.send({ type: 'ack' }), false);
});

test('closing discards pending controls so they cannot leak into a replacement connection', () => {
  const old = fixture(); old.outbox.send({ type: 'ack' }); old.outbox.send(touch(0)); old.outbox.close();
  const next = fixture(); next.socket.bufferedAmount = 0; next.outbox.send({ type: 'ping' });
  old.socket.bufferedAmount = 0; old.advance(100);
  assert.deepEqual(old.sent, []); assert.deepEqual(next.sent, [{ type: 'ping' }]);
  assert.equal(old.timers.size, 0);
});

test('a send exception ends the outbox without replaying possibly accepted messages', () => {
  const f = fixture(); f.socket.bufferedAmount = 0;
  f.socket.send = () => { throw new Error('closed'); };
  assert.equal(f.outbox.send({ type: 'ack' }), false);
  assert.equal(f.failures, 1); assert.equal(f.timers.size, 0);
});

test('flush stops when actual WebSocket buffering crosses the watermark', () => {
  const f = fixture(); f.outbox.send(touch(0)); f.outbox.send(touch(0.5, false));
  const send = f.socket.send;
  f.socket.send = value => { send(value); f.socket.bufferedAmount = 8192; };
  f.socket.bufferedAmount = 0; f.advance(10);
  assert.deepEqual(f.sent, [touch(0)]);
  f.socket.bufferedAmount = 0; f.advance(20);
  assert.deepEqual(f.sent, [touch(0), touch(0.5, false)]);
  assert.equal(f.timers.size, 0);
});
