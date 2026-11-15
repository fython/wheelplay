import test from 'node:test';
import assert from 'node:assert/strict';
import { createCanvasFramePainter } from '../common/src/main/assets/web/app.js';

test('default painter only requests Canvas 2D even when WebGL is available', () => {
  const requests = [], source = {}, draws = [];
  const canvas = { getContext(type) {
    requests.push(type); assert.equal(type, '2d');
    return { drawImage: (...args) => draws.push(args) };
  } };
  const painter = createCanvasFramePainter(canvas);
  painter.draw(source, 1280, 720); painter.dispose();
  assert.equal(painter.backend, '2d'); assert.deepEqual(requests, ['2d']);
  assert.deepEqual(draws, [[source, 0, 0, 1280, 720]]);
});

function fixture({ compile = true, link = true } = {}) {
  const calls = [], listeners = new Map();
  let lost = false, error = 0, id = 0;
  const gl = Object.fromEntries(['VERTEX_SHADER', 'FRAGMENT_SHADER', 'COMPILE_STATUS', 'LINK_STATUS', 'ARRAY_BUFFER',
    'STATIC_DRAW', 'FLOAT', 'TEXTURE0', 'TEXTURE_2D', 'TEXTURE_MIN_FILTER', 'TEXTURE_MAG_FILTER', 'LINEAR',
    'TEXTURE_WRAP_S', 'TEXTURE_WRAP_T', 'CLAMP_TO_EDGE', 'MAX_TEXTURE_SIZE', 'RGBA', 'UNSIGNED_BYTE', 'TRIANGLE_STRIP', 'HIGH_FLOAT']
    .map((key, index) => [key, index + 1]));
  gl.NO_ERROR = 0;
  for (const name of ['shaderSource', 'compileShader', 'attachShader', 'linkProgram', 'useProgram', 'bindBuffer',
    'bufferData', 'enableVertexAttribArray', 'vertexAttribPointer', 'activeTexture', 'bindTexture', 'texParameteri',
    'uniform1i', 'viewport', 'texImage2D', 'drawArrays', 'deleteTexture', 'deleteBuffer', 'deleteProgram', 'deleteShader']) {
    gl[name] = (...args) => calls.push([name, ...args]);
  }
  for (const name of ['createProgram', 'createShader', 'createBuffer', 'createTexture']) {
    gl[name] = () => { const object = { id: ++id }; calls.push([name, object]); return object; };
  }
  Object.assign(gl, {
    isContextLost: () => lost, getShaderParameter: () => compile, getProgramParameter: () => link,
    getAttribLocation: () => 0, getUniformLocation: () => ({}), getParameter: () => 4096,
    getShaderPrecisionFormat: () => ({ precision: 23 }),
    getError() { calls.push(['getError']); return error; },
  });
  const canvas = {
    getContext(type, options) { calls.push(['getContext', type, options]); return type === 'webgl' ? gl : null; },
    addEventListener(type, fn) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(fn); },
    removeEventListener(type, fn) { listeners.get(type)?.delete(fn); },
  };
  return { gl, canvas, calls, listeners, setLost: value => { lost = value; }, setError: value => { error = value; },
    lose() {
      lost = true;
      const event = { prevented: false, preventDefault() { this.prevented = true; } };
      for (const fn of [...(listeners.get('webglcontextlost') || [])]) fn(event);
      return event;
    },
    count: name => calls.filter(call => call[0] === name).length,
  };
}

test('WebGL uploads sources directly and reuses GPU objects across frames and rotations', () => {
  const f = fixture(), painter = createCanvasFramePainter(f.canvas, { webgl: true }), first = {}, second = {};
  assert.equal(painter.backend, 'webgl');
  const options = f.calls.find(call => call[0] === 'getContext')[2];
  assert.equal(options.antialias, false); assert.equal(options.depth, false); assert.equal(options.preserveDrawingBuffer, true);
  painter.draw(first, 1280, 720); painter.draw(second, 1280, 720); painter.draw(second, 720, 1280);
  assert.equal(f.count('createTexture'), 1); assert.equal(f.count('createBuffer'), 1); assert.equal(f.count('createProgram'), 1);
  assert.deepEqual(f.calls.filter(call => call[0] === 'texImage2D').map(call => call.at(-1)), [first, second, second]);
  assert.equal(f.count('drawArrays'), 3); assert.equal(f.count('getError'), 2);
  assert.deepEqual(f.calls.filter(call => call[0] === 'viewport').at(-1), ['viewport', 0, 0, 720, 1280]);
  painter.dispose(); painter.dispose();
  assert.equal(f.count('deleteTexture'), 1); assert.equal(f.count('deleteBuffer'), 1);
  assert.equal(f.count('deleteProgram'), 1); assert.equal(f.count('deleteShader'), 2);
  assert.throws(() => painter.draw(first, 1280, 720), /context lost/);
  const restarted = createCanvasFramePainter(f.canvas, { webgl: true }); restarted.draw(first, 1280, 720); restarted.dispose();
  assert.equal(f.count('drawArrays'), 4);
});

test('shader and link failures release partially allocated GPU resources', () => {
  for (const options of [{ compile: false }, { link: false }]) {
    const f = fixture(options);
    assert.throws(() => createCanvasFramePainter(f.canvas, { webgl: true }), /WebGL/);
    assert.equal(f.count('deleteProgram'), 1);
    assert.equal(f.count('deleteShader'), options.compile === false ? 1 : 2);
  }
});

test('loss reports once, disposes safely and keeps restoration enabled after stop', () => {
  const f = fixture(), errors = [];
  const painter = createCanvasFramePainter(f.canvas, { webgl: true, onError: error => { errors.push(error); painter.dispose(); } });
  assert.equal(f.lose().prevented, true); assert.equal(errors.length, 1);
  f.lose(); assert.equal(errors.length, 1);
  f.setLost(false);
  const next = createCanvasFramePainter(f.canvas, { webgl: true }); next.draw({}, 1280, 720);
  // draw can detect loss before its asynchronous event arrives.
  f.setLost(true); assert.throws(() => next.draw({}, 1280, 720), /context lost/); next.dispose();
  assert.equal(f.lose().prevented, true);
});

test('unsupported texture sizes and GL upload errors cannot count as successful frames', () => {
  const f = fixture(), painter = createCanvasFramePainter(f.canvas, { webgl: true });
  assert.throws(() => painter.draw({}, 8192, 720), { name: 'NotSupportedError' });
  assert.equal(f.count('texImage2D'), 0);
  f.setError(1282); assert.throws(() => painter.draw({}, 1280, 720), /upload failed/);
  painter.dispose();
});

test('WebGL absence uses opaque Canvas 2D without pixel readback', () => {
  const calls = [], source = {};
  const canvas = { getContext(type, options) {
    calls.push([type, options]);
    return type === '2d' ? { drawImage: (...args) => calls.push(args) } : null;
  } };
  const painter = createCanvasFramePainter(canvas, { webgl: true }); painter.draw(source, 720, 1280); painter.dispose();
  assert.equal(painter.backend, '2d'); assert.deepEqual(calls.at(-1), [source, 0, 0, 720, 1280]);
  assert.deepEqual(calls[1], ['2d', { alpha: false }]);
});
