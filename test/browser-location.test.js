import test from 'node:test';
import assert from 'node:assert/strict';
import { createBrowserLocation } from '../common/src/main/assets/web/browser-location.js';

const flush = () => new Promise(resolve => setImmediate(resolve));
function fixture({ state = 'granted', secure = true, supported = true, permissions = true } = {}) {
  const sent = [], states = [], watches = [], cleared = [], probes = [], queries = [];
  const permission = { state, onchange: null };
  const geo = {
    getCurrentPosition(ok, error, options) { probes.push({ ok, error, options }); },
    watchPosition(ok, error, options) { watches.push({ ok, error, options }); return watches.length - 1; },
    clearWatch(id) { cleared.push(id); },
  };
  const client = createBrowserLocation({ send: data => sent.push(data),
    onState: (enabled, message) => states.push({ enabled, message }), secure: () => secure,
    now: () => 1700000000000,
    browser: () => ({ geolocation: supported ? geo : null, permissions: permissions ? {
      async query(value) { queries.push(value); return permission; },
    } : null }),
  });
  return { client, sent, states, watches, cleared, probes, queries, permission };
}
const position = (coords = {}, timestamp = 1700000000000) => ({ timestamp,
  coords: { latitude: 25.033, longitude: 121.5654, accuracy: 5, altitude: null, speed: null, heading: null, ...coords },
});

test('default connection neither checks permission nor collects or sends GPS', async () => {
  const f = fixture(); f.client.attach(); await flush(); f.client.close();
  assert.deepEqual([f.sent, f.queries, f.probes, f.watches], [[], [], [], []]);
});

test('a synchronous disconnect while sending start cannot leave GPS watching in the background', async () => {
  let watches = 0;
  const client = createBrowserLocation({ secure: () => true,
    browser: () => ({ permissions: { query: async () => ({ state: 'granted' }) },
      geolocation: { watchPosition() { watches++; } } }),
    send(data) { if (data.type === 'location-start') { client.close(); return false; } return true; },
  });
  client.attach(); await client.setEnabled(true);
  assert.equal(watches, 0);
});

test('granted permission is checked on enable and reconnect; only connected watches send fixes', async () => {
  const f = fixture(); await f.client.setEnabled(true);
  assert.deepEqual(f.queries, [{ name: 'geolocation' }]);
  assert.equal(f.watches.length, 0); assert.equal(f.sent.length, 0);
  f.client.attach(); await flush(); assert.equal(f.watches.length, 1);
  assert.deepEqual(f.watches[0].options, { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 });
  f.watches[0].ok(position());
  assert.deepEqual(f.sent.at(-1), { type: 'location', latitude: 25.033, longitude: 121.5654,
    accuracy: 5, altitude: null, heading: null, speed: null, timestamp: 1700000000000, ageMillis: 0 });
  f.client.close(); assert.deepEqual(f.cleared, [0]);
  const count = f.sent.length; f.watches[0].ok(position()); assert.equal(f.sent.length, count);
  f.client.attach(); await flush(); assert.equal(f.queries.length, 3); assert.equal(f.watches.length, 2);
});

test('prompt and browsers without Permissions API request consent before starting a watch', async () => {
  for (const permissions of [true, false]) {
    const f = fixture({ state: 'prompt', permissions }); f.client.attach();
    const enabling = f.client.setEnabled(true); await flush();
    assert.equal(f.probes.length, 1); assert.equal(f.watches.length, 0);
    f.probes[0].ok(position()); await enabling;
    assert.equal(f.watches.length, 1);
    assert.equal(f.sent.some(m => m.type === 'location'), false, 'permission probe must not be forwarded');
  }
});

test('denied permission, HTTP and missing geolocation leave the option disabled', async () => {
  for (const [options, message] of [[{ state: 'denied' }, 'web_location_denied'],
    [{ secure: false }, 'web_location_https'], [{ supported: false }, 'web_location_unsupported']]) {
    const f = fixture(options); await f.client.setEnabled(true); f.client.attach();
    assert.deepEqual(f.states.at(-1), { enabled: false, message });
    assert.equal(f.watches.length, 0); assert.equal(f.probes.length, 0);
    assert.equal(f.sent.length, 0);
  }
});

test('revocation or watch permission error stops updates, unchecks the option and clears the host', async () => {
  for (const revoke of [true, false]) {
    const f = fixture(); f.client.attach(); await f.client.setEnabled(true);
    if (revoke) { f.permission.state = 'denied'; f.permission.onchange(); }
    else f.watches[0].error({ code: 1 });
    assert.deepEqual(f.cleared, [0]); assert.equal(f.permission.onchange, null);
    assert.deepEqual(f.states.at(-1), { enabled: false, message: 'web_location_denied' });
    assert.deepEqual(f.sent.at(-1), { type: 'location-stop' });
    const count = f.sent.length; f.watches[0].ok(position()); assert.equal(f.sent.length, count);
  }
});

test('disable and disconnect cancel pending permission results', async () => {
  for (const disconnect of [true, false]) {
    const f = fixture({ state: 'prompt' }); f.client.attach();
    const enabling = f.client.setEnabled(true); await flush();
    if (disconnect) f.client.close(); else await f.client.setEnabled(false);
    f.probes[0].ok(position()); await enabling;
    assert.equal(f.watches.length, 0); assert.equal(f.states.at(-1).enabled, false);
  }
});

test('transient GPS failures clear the fix and recover; invalid or stale samples are dropped', async () => {
  const f = fixture(); f.client.attach(); await f.client.setEnabled(true);
  f.watches[0].error({ code: 3 });
  assert.deepEqual(f.sent.at(-1), { type: 'location-clear' }); assert.equal(f.cleared.length, 0);
  const count = f.sent.length;
  f.watches[0].ok(position({ latitude: NaN }));
  f.watches[0].ok(position({ longitude: 181 }));
  f.watches[0].ok(position({ accuracy: -1 }));
  f.watches[0].ok(position({}, 1699999969999));
  assert.equal(f.sent.length, count);
  f.watches[0].ok(position({ heading: NaN, speed: -1 }));
  assert.equal(f.sent.at(-1).heading, null); assert.equal(f.sent.at(-1).speed, null);
  assert.equal(f.states.at(-1).message, 'web_location_active');
});

test('initial timeout or rejection never enables forwarding', async () => {
  for (const code of [1, 2, 3]) {
    const f = fixture({ state: 'prompt' }); const enabling = f.client.setEnabled(true); await flush();
    f.probes[0].error({ code }); await enabling; f.client.attach();
    assert.equal(f.states.at(-1).enabled, false); assert.equal(f.watches.length, 0);
  }
});
