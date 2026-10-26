import { t } from './text.js';
import { createDesktopInput } from './desktop-input.js';
// A surface inversely scaled inside the visual viewport cancels browser zoom.
// Its bounding rectangle remains in the same CSS coordinates as pointer events.
export function unzoomedDisplayRect(rect, visual) {
  if (!visual || !Number.isFinite(visual.scale) || visual.scale <= 0 || visual.width <= 0 || visual.height <= 0) return null;
  const left = visual.offsetLeft || 0, offsetTop = visual.offsetTop || 0;
  const top = Math.max(offsetTop, rect.top || 0);
  return { left, top, width: visual.width, height: Math.max(0, offsetTop + visual.height - top), scale: visual.scale };
}

// Match WebSession's viewport bounds. A small visible area can still display a
// larger negotiated canvas; scale both axes together instead of stretching it.
export function browserViewportSize(width, height) {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null;
  const ratio = width / height;
  if (ratio < 0.5 || ratio > 4) return null;
  const scale = Math.min(8192 / Math.max(width, height), Math.max(1, 320 / Math.min(width, height)));
  const even = value => Math.round(value / 2) * 2;
  const h = even(height * scale);
  // Even-pixel rounding must not push an otherwise valid ratio outside bounds.
  const w = Math.max(Math.ceil(h / 4) * 2, Math.min(h * 4, even(width * scale)));
  if (!Number.isFinite(w) || !Number.isFinite(h) || w < 320 || h < 320 || w > 8192 || h > 8192) return null;
  return { width: w, height: h };
}

// Coordinates are normalized in the visible video rectangle, excluding letterboxing.
export function mapPoint(clientX, clientY, rect, width, height, clamp = false) {
  if (width <= 0 || height <= 0 || rect.width <= 0 || rect.height <= 0) return null;
  const scale = Math.min(rect.width / width, rect.height / height);
  const w = width * scale, h = height * scale;
  const x = (clientX - rect.left - (rect.width - w) / 2) / w;
  const y = (clientY - rect.top - (rect.height - h) / 2) / h;
  if (!clamp && (x < 0 || x > 1 || y < 0 || y > 1)) return null;
  return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
}

export function pairingCodeFromUrl(search) {
  const match = String(search || '').match(/(?:^|[?&])code=([^&]*)/);
  let code = '';
  try { code = match ? decodeURIComponent(match[1].replace(/\+/g, ' ')) : ''; } catch (_) { return null; }
  return /^[0-9]{6}$/.test(code) ? code : null;
}

// Source availability, a committed picture and input readiness are independent.
// Silence is normal for a static screen; only an explicit gap starts this deadline.
export function createPresentationController({
  onChange = () => {}, schedule = setTimeout, cancel = clearTimeout,
  now = () => performance.now(), holdMs = 2000,
} = {}) {
  let connected = false, source = 'waiting', inputReady = true, frame = null;
  let sessionId = null, videoEpoch = null, revision = 0, invalid = false;
  let deadline = null, timer = null, timerGeneration = 0;
  const same = picture => picture && picture.revision === revision && !invalid;
  function clearDeadline() { cancel(timer); timer = null; deadline = null; timerGeneration++; }
  function snapshot() {
    const showing = connected && same(frame) && source === 'ready';
    const recovering = connected && !!frame && !showing && source !== 'stopped';
    const held = recovering && deadline !== null && now() < deadline;
    return { state: !connected ? 'disconnected' : showing ? 'showing' : held ? 'recovering' : 'waiting',
      visible: showing || held, interactive: showing && inputReady,
      frame, sessionId, videoEpoch, revision };
  }
  function publish() {
    const value = snapshot();
    if (value.state === 'showing' || !connected || source === 'stopped' || !frame) clearDeadline();
    else if (deadline === null) {
      deadline = now() + holdMs;
      const token = ++timerGeneration;
      const expire = () => {
        if (token !== timerGeneration) return;
        const remaining = deadline - now();
        if (remaining > 0) { timer = schedule(expire, remaining); return; }
        timer = null; onChange(snapshot());
      };
      timer = schedule(expire, holdMs);
    }
    onChange(snapshot());
  }
  function accept(meta = {}) {
    if (!connected) return false;
    if (meta.videoEpoch == null) return videoEpoch === null;
    if (!Number.isSafeInteger(meta.videoEpoch) || !Number.isSafeInteger(meta.sessionId) ||
        meta.videoEpoch < 0 || meta.sessionId < 0) return false;
    if (videoEpoch !== null && (meta.videoEpoch < videoEpoch ||
      meta.videoEpoch === videoEpoch && meta.sessionId !== sessionId)) return false;
    if (meta.videoEpoch !== videoEpoch || meta.sessionId !== sessionId) {
      sessionId = meta.sessionId; videoEpoch = meta.videoEpoch; revision++;
      source = 'waiting'; inputReady = false; invalid = false;
    }
    return true;
  }
  return {
    open() { clearDeadline(); connected = true; source = 'waiting'; inputReady = true;
      frame = null; sessionId = videoEpoch = null; revision++; invalid = false; publish(); },
    close() { connected = false; frame = null; revision++; publish(); },
    status(meta) {
      if (!accept(meta)) return false;
      // Compatibility with servers predating versioned media packets.
      if (videoEpoch === null && frame && frame.transport !== 'rtc' && (meta.width !== frame.width || meta.height !== frame.height)) {
        revision++; invalid = false;
      }
      source = meta.sourceState || (meta.streaming ? 'ready' : 'recovering');
      inputReady = meta.inputReady ?? source === 'ready';
      publish(); return true;
    },
    present(meta, width, height, transport, expectedRevision = null) {
      if (!accept(meta) || source === 'stopped' || expectedRevision !== null && expectedRevision !== revision) return false;
      frame = { width, height, transport, revision, mime: meta.mime }; invalid = false;
      if (videoEpoch === null) { source = 'ready'; inputReady = true; }
      publish(); return true;
    },
    accepts(meta) {
      const previous = revision, accepted = accept(meta);
      if (accepted && previous !== revision) publish();
      return accepted;
    },
    invalidate(transport) { if (frame?.transport === transport) { invalid = true; publish(); } },
    forget() { frame = null; publish(); },
    inputUnavailable() { inputReady = false; publish(); },
    refresh: publish,
    get current() { return snapshot(); },
  };
}

// V3 appends the negotiated image format to the v2 header. PCM has a different magic.
export function decodeVideoPacket(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < 4) return null;
  const view = new DataView(buffer);
  const magic = view.getUint32(0, true), protocol = magic === 0x33565057 ? 3 : magic === 0x32565057 ? 2 : null;
  if (!protocol) return null;
  const headerBytes = protocol === 3 ? 40 : 36;
  if (buffer.byteLength <= headerBytes) throw new Error('Truncated video packet');
  const packet = { sessionId: view.getFloat64(4, true), videoEpoch: view.getFloat64(12, true),
    sequence: view.getFloat64(20, true), width: view.getUint32(28, true), height: view.getUint32(32, true) };
  if (![packet.sessionId, packet.videoEpoch, packet.sequence].every(value => Number.isSafeInteger(value) && value >= 0) ||
      packet.width < 1 || packet.height < 1) throw new Error('Invalid video packet');
  const format = protocol === 3 ? view.getUint32(36, true) : 1;
  if (![1, 2].includes(format)) throw new Error('Invalid image format');
  return { ...packet, protocol, mime: format === 2 ? 'image/webp' : 'image/jpeg', jpeg: buffer.slice(headerBytes) };
}

export function probeWebp({ createImage = () => new Image(), schedule = setTimeout, cancel = clearTimeout,
  createUrl = data => URL.createObjectURL(data), revokeUrl = url => URL.revokeObjectURL(url),
} = {}) {
  return new Promise(resolve => {
    let image, url = null, timer = null, finished = false;
    const finish = supported => {
      if (finished) return; finished = true; cancel(timer);
      if (image) { image.onload = image.onerror = null; image.removeAttribute('src'); }
      if (url !== null) revokeUrl(url);
      resolve(supported);
    };
    try {
      image = createImage(); timer = schedule(() => finish(false), 1500);
      image.onload = () => finish(image.naturalWidth === 2 && image.naturalHeight === 2);
      image.onerror = () => finish(false);
      // The Android page allows blob: images but deliberately blocks data: URLs.
      // This is the same 2x2 WebP fixture used by the capability probe.
      url = createUrl(new Blob([new Uint8Array([
        82, 73, 70, 70, 60, 0, 0, 0, 87, 69, 66, 80, 86, 80, 56, 32, 48, 0, 0, 0,
        208, 1, 0, 157, 1, 42, 2, 0, 2, 0, 2, 0, 52, 37, 160, 2, 116, 186, 1, 248,
        0, 3, 176, 0, 254, 240, 196, 11, 255, 32, 185, 97, 117, 200, 215, 255, 32,
        63, 228, 7, 252, 128, 255, 248, 242, 0, 0, 0,
      ])], { type: 'image/webp' }));
      image.src = url;
    } catch (_) { finish(false); }
  });
}

// At most two decodes plus the displayed frame. Newer commits retire older work.
export function createJpegPresenter(images, { onFrame, onError = () => {},
  createUrl = data => URL.createObjectURL(data), revokeUrl = url => URL.revokeObjectURL(url),
} = {}) {
  let front = null, generation = 0, nextOrder = 0, committedOrder = 0;
  const urls = new Map(), pending = new Map();
  function discard(image) {
    image.onload = image.onerror = null; image.removeAttribute('src'); image.hidden = true;
    if (urls.has(image)) revokeUrl(urls.get(image)); urls.delete(image);
  }
  function retire(image) {
    const work = pending.get(image); if (!work) return;
    pending.delete(image); discard(image); work.done();
  }
  function cancelPending() {
    generation++;
    for (const image of [...pending.keys()]) retire(image);
  }
  return {
    load(data, meta, valid, done) {
      let image = images.find(item => item !== front && !pending.has(item));
      if (!image || pending.size >= Math.min(2, images.length - 1)) {
        image = pending.keys().next().value; retire(image);
      }
      const work = { done, order: ++nextOrder }, token = generation;
      pending.set(image, work); image.hidden = true;
      const url = createUrl(data); urls.set(image, url);
      image.onload = () => {
        if (token !== generation || pending.get(image) !== work) return;
        pending.delete(image); image.onload = image.onerror = null;
        if (work.order <= committedOrder || !valid() || !image.naturalWidth || !image.naturalHeight) { discard(image); done(); return; }
        const previous = front;
        const accepted = onFrame(meta, image.naturalWidth, image.naturalHeight);
        if (token !== generation) { done(); return; }
        if (accepted === false) { discard(image); done(); return; }
        committedOrder = work.order;
        front = image; image.hidden = false;
        if (previous) discard(previous);
        for (const [other, older] of [...pending]) if (older.order < work.order) retire(other);
        done();
      };
      image.onerror = () => {
        if (token !== generation || pending.get(image) !== work) return;
        pending.delete(image); discard(image); done(); onError(meta);
      };
      image.src = url;
    },
    cancelPending,
    hide() { if (front) front.hidden = true; },
    show() { if (front) front.hidden = false; },
    clear() { cancelPending(); images.forEach(discard); front = null; },
    get current() { return front; },
  };
}

// Keep essential controls until WebSocket accepts them. A successful send is never retried.
export function createControlOutbox(socket, {
  schedule = setTimeout, cancel = clearTimeout, now = () => Date.now(), onFailure = () => socket.close(),
} = {}) {
  const touches = [], imageAcks = new Map();
  const signals = [];
  let ack = null, audioAck = null, audioReady = null, ping = null, feedback = null, viewport = null, locationFix = null, timer = null, blockedSince = null, closed = false;
  const close = () => {
    closed = true; cancel(timer); timer = null;
    ack = null; audioAck = null; audioReady = null; ping = null; feedback = null; viewport = null; locationFix = null; touches.length = 0; signals.length = 0;
    imageAcks.clear();
  };
  const fail = () => { close(); onFailure(); };
  const flush = () => {
    if (closed) return;
    if (socket.readyState !== 1) { close(); return; }
    try {
      while ((ack || imageAcks.size || audioAck || audioReady || touches.length || signals.length || viewport || locationFix || feedback || ping) && socket.bufferedAmount < 8192) {
        if (imageAcks.size) {
          const [key, json] = imageAcks.entries().next().value; socket.send(json); imageAcks.delete(key);
        } else if (ack) { socket.send(ack); ack = null; }
        else if (audioAck) { socket.send(audioAck); audioAck = null; }
        else if (audioReady) { socket.send(audioReady); audioReady = null; }
        else if (touches.length) { socket.send(touches[0].json); touches.shift(); }
        else if (signals.length) { socket.send(signals[0]); signals.shift(); }
        else if (viewport) { socket.send(viewport); viewport = null; }
        else if (locationFix) { socket.send(locationFix); locationFix = null; }
        else if (feedback) { socket.send(feedback); feedback = null; }
        else { socket.send(ping); ping = null; }
        blockedSince = null;
      }
    } catch (_) { fail(); return; }
    if (ack || imageAcks.size || audioAck || audioReady || touches.length || signals.length || viewport || locationFix || feedback || ping) {
      if (blockedSince === null) blockedSince = now();
      if (now() - blockedSince >= 1500) { fail(); return; }
      if (timer === null) timer = schedule(() => { timer = null; flush(); }, 10);
    } else {
      cancel(timer); timer = null; blockedSince = null;
    }
  };
  return {
    send(data, kind = 'edge') {
      if (closed || socket.readyState !== 1) return false;
      const json = JSON.stringify(data);
      if (data.type === 'ack' && Number.isSafeInteger(data.sequence)) {
        const key = `${data.sessionId}/${data.videoEpoch}/${data.sequence}`;
        if (!imageAcks.has(key) && imageAcks.size >= 8) { fail(); return false; }
        imageAcks.set(key, json);
      } else if (data.type === 'ack') ack = json;
      else if (data.type === 'audio-ack') audioAck = json;
      else if (data.type === 'audio-ready') audioReady = json;
      else if (data.type === 'ping') ping = json;
      else if (data.type === 'rtc-feedback') feedback = json;
      else if (data.type === 'viewport') viewport = json;
      else if (data.type === 'location') locationFix = json;
      else if (data.type === 'touch' || data.type === 'input') {
        const tail = touches[touches.length - 1];
        // Only consecutive moves may be replaced; never cross a down/up/release boundary.
        if (kind === 'move' && tail && tail.kind === 'move' && tail.type === data.type && tail.eventKind === data.kind) tail.json = json;
        else if (touches.length < 64) touches.push({ kind, json, type: data.type, eventKind: data.kind });
        else { fail(); return false; }
      } else if (['rtc-start', 'rtc-answer', 'rtc-ready', 'rtc-fallback', 'phone-connect', 'image-format', 'location-start', 'location-stop', 'location-clear', 'media-command'].includes(data.type)) {
        if (signals.length >= 8 || json.length > 32768) { fail(); return false; }
        if (data.type.startsWith('location-')) locationFix = null;
        signals.push(json);
      } else return false;
      flush();
      return !closed;
    },
    close,
  };
}

// Keep the decoder in the DOM, outside the viewport. display:none/hidden can
// suppress video frame callbacks on embedded browsers. drawImage copies decoded
// frames directly; it does not read pixels back through getImageData.
export function createVideoCanvasRenderer(video, canvas, {
  onFrame = () => {}, onDraw = () => {}, onError = () => {},
  requestFrame = callback => requestAnimationFrame(callback), cancelFrame = id => cancelAnimationFrame(id),
  now = () => performance.now(),
} = {}) {
  let context = null, running = false, generation = 0, videoCallback = null, animationCallback = null;
  let lastVideoCallbackAt = 0, lastFrameKey = null;
  function prepare() {
    video.classList.toggle('offscreen-video', true);
    video.hidden = false;
  }
  function stop() {
    running = false; generation++;
    if (videoCallback !== null && video.cancelVideoFrameCallback) video.cancelVideoFrameCallback(videoCallback);
    if (animationCallback !== null) cancelFrame(animationCallback);
    videoCallback = animationCallback = null; context = null; lastFrameKey = null;
    video.hidden = true; video.classList.toggle('offscreen-video', false);
    canvas.hidden = true; canvas.width = canvas.height = 0;
  }
  function fail(error) { stop(); onError(error); }
  function draw(metadata = null, stalled = false) {
    if (!running || video.readyState < 2 || !video.videoWidth || !video.videoHeight) return;
    try {
      const width = video.videoWidth, height = video.videoHeight;
      const resized = canvas.width !== width || canvas.height !== height;
      // Prefer the frame counter over currentTime, which can advance between
      // decoded frames. Some WebRTC implementations leave this counter at zero.
      const frames = video.getVideoPlaybackQuality?.().totalVideoFrames;
      const time = metadata?.mediaTime ?? video.currentTime;
      const key = !stalled && frames > 0 ? `frame:${frames}` : Number.isFinite(time) ? `time:${time}` : null;
      if (!resized && key !== null && key === lastFrameKey) return;
      if (resized) { canvas.width = width; canvas.height = height; }
      context.drawImage(video, 0, 0, width, height);
      onDraw();
      lastFrameKey = key;
      const first = canvas.hidden;
      canvas.hidden = false;
      if (first || resized) onFrame(width, height);
    } catch (error) { fail(error); }
  }
  function start() {
    stop(); prepare();
    try {
      context = canvas.getContext('2d', { alpha: false });
      if (!context) throw Object.assign(new Error('Canvas 2D unavailable'), { name: 'NotSupportedError' });
      running = true; lastVideoCallbackAt = now();
      const token = generation, current = () => running && token === generation;
      const observe = (_, metadata) => {
        if (!current()) return;
        videoCallback = null; lastVideoCallbackAt = now(); draw(metadata);
        if (current()) videoCallback = video.requestVideoFrameCallback(observe);
      };
      const refresh = () => {
        if (!current()) return;
        animationCallback = null;
        // Also recover if offscreen playback decodes but stops being composited.
        if (!video.requestVideoFrameCallback) draw();
        else if (now() - lastVideoCallbackAt >= 250) draw(null, true);
        if (current()) animationCallback = requestFrame(refresh);
      };
      draw();
      if (!current()) return;
      if (video.requestVideoFrameCallback) videoCallback = video.requestVideoFrameCallback(observe);
      animationCallback = requestFrame(refresh);
    } catch (error) { fail(error); }
  }
  return { prepare, start, stop };
}

function isRtcErrorRetryable(error) {
  return !['NotAllowedError', 'NotSupportedError'].includes(error?.name);
}

export function videoRendererPreference(value, legacy) {
  return ['native', 'video-canvas', 'track-canvas'].includes(value) ? value : legacy === 'true' ? 'video-canvas' : 'native';
}

// A reader owns each VideoFrame until it is drawn or discarded, including after stop.
export function createTrackCanvasRenderer(canvas, {
  onReady = () => {}, onResize = () => {}, onError = () => {},
  createProcessor = init => new MediaStreamTrackProcessor(init),
} = {}) {
  let generation = 0, reader = null, running = false, width = 0, height = 0, renderedFrames = 0;
  function stop() {
    generation++; running = false;
    const old = reader; reader = null;
    if (old) { try { Promise.resolve(old.cancel()).catch(() => {}); } catch (_) {} }
    canvas.hidden = true; canvas.width = canvas.height = 0;
  }
  async function consume(activeReader, context, token) {
    const current = () => running && generation === token;
    try {
      while (current()) {
        const { value: frame, done } = await activeReader.read();
        try {
          if (!current()) break;
          if (done) throw new Error('track-ended');
          if (!frame || !(frame.displayWidth > 0) || !(frame.displayHeight > 0)) continue;
          const resized = width !== frame.displayWidth || height !== frame.displayHeight;
          if (resized) { canvas.width = frame.displayWidth; canvas.height = frame.displayHeight; }
          context.drawImage(frame, 0, 0, frame.displayWidth, frame.displayHeight);
          width = frame.displayWidth; height = frame.displayHeight; renderedFrames++;
          canvas.hidden = false;
          if (renderedFrames === 1) onReady({ width, height });
          else if (resized) onResize({ width, height });
        } finally { frame?.close(); }
      }
    } catch (error) {
      if (current()) {
        // Let the receiver freeze the last picture before stop clears the canvas.
        try { onError(error.message === 'track-ended' ? 'track-ended' : 'renderer-error', isRtcErrorRetryable(error)); }
        finally { if (generation === token) stop(); }
      }
    } finally {
      try { activeReader.releaseLock(); } catch (_) {}
      if (reader === activeReader) reader = null;
    }
  }
  return {
    start(track) {
      stop(); width = height = renderedFrames = 0;
      try {
        const context = canvas.getContext('2d', { alpha: false });
        if (!context) throw Object.assign(new Error('Canvas 2D unavailable'), { name: 'NotSupportedError' });
        reader = createProcessor({ track, maxBufferSize: 1 }).readable.getReader();
        running = true;
        void consume(reader, context, generation);
      } catch (error) { stop(); onError('renderer-error', isRtcErrorRetryable(error)); }
    },
    stop,
    snapshot: () => ({ width, height, renderedFrames, presentedFrames: null }),
  };
}

// Video elements exist only for the two legacy renderers, and belong to one offer.
export function createVideoRenderer(mode, canvas, {
  createVideo, onReady = () => {}, onResize = () => {}, onError = () => {},
} = {}) {
  let video = null, copy = null, frameCallback = null, generation = 0, ready = false, started = false;
  let width = 0, height = 0, presentedFrames = null, renderedFrames = mode === 'video-canvas' ? 0 : null;
  function report(w, h) {
    if (!video || !w || !h) return;
    const resized = width !== w || height !== h;
    width = w; height = h;
    if (!ready) { ready = true; if (!copy) video.hidden = false; onReady({ width, height }); }
    else if (resized) onResize({ width, height });
  }
  function stop() {
    generation++;
    if (copy) copy.stop(); copy = null;
    if (video) {
      if (frameCallback !== null) video.cancelVideoFrameCallback?.(frameCallback);
      video.onloadeddata = video.onerror = video.onresize = null;
      video.srcObject = null; video.remove(); video = null;
    }
    frameCallback = null;
  }
  return {
    start(track, streams = []) {
      stop(); ready = started = false; width = height = 0; presentedFrames = null;
      renderedFrames = mode === 'video-canvas' ? 0 : null;
      try {
        video = createVideo();
        const token = generation, current = () => video !== null && token === generation;
        if (mode === 'video-canvas') {
          copy = createVideoCanvasRenderer(video, canvas, {
            onDraw: () => { renderedFrames++; }, onFrame: report,
            onError: error => { if (current()) onError('renderer-error', isRtcErrorRetryable(error)); },
          });
          copy.prepare();
        }
        const displayed = () => {
          if (!current() || video.readyState < 2 || !video.videoWidth || !video.videoHeight) return;
          if (copy) { if (!started) { started = true; copy.start(); } }
          else report(video.videoWidth, video.videoHeight);
        };
        video.onloadeddata = displayed;
        video.onresize = () => { if (current() && !copy) displayed(); };
        video.onerror = () => { if (current()) onError('renderer-error'); };
        if (video.requestVideoFrameCallback) {
          let firstPresented = null;
          presentedFrames = 0;
          const observe = (_, metadata) => {
            if (!current()) return;
            if (firstPresented === null) firstPresented = metadata.presentedFrames;
            presentedFrames = metadata.presentedFrames - firstPresented;
            if (!copy) displayed();
            if (current()) frameCallback = video.requestVideoFrameCallback(observe);
          };
          frameCallback = video.requestVideoFrameCallback(observe);
        }
        video.srcObject = streams[0] || new MediaStream([track]);
        Promise.resolve(video.play()).then(displayed).catch(error => { if (current()) onError('renderer-error', isRtcErrorRetryable(error)); });
      } catch (error) { onError('renderer-error', isRtcErrorRetryable(error)); }
    },
    stop,
    snapshot: () => ({ width, height, presentedFrames, renderedFrames }),
  };
}

// Video WebRTC remains receive-only. Browser audio uses the paired WebSocket.
export function createRtcReceiver(createRenderer, {
  send, onReady = () => {}, onStop = () => {}, onStats = () => {},
  onResize = () => {}, onReset = () => {},
  createPeer = () => new RTCPeerConnection({ iceServers: [] }),
  supportsCodec = codec => {
    if (typeof RTCRtpReceiver === 'undefined' || !RTCRtpReceiver.getCapabilities) return true;
    const caps = RTCRtpReceiver.getCapabilities('video');
    return !caps || caps.codecs.some(c => c.mimeType.toLowerCase() === `video/${codec.toLowerCase()}`);
  },
  schedule = setTimeout, cancel = clearTimeout, now = () => Date.now(),
} = {}) {
  let renderer = null, track = null, previousStats = null, renderStalledSince = null, lastRendered = 0;
  let codec = 'H264';
  let peer = null, id = null, generation = 0, timeout = null, statsTimer = null, ready = false;
  let lastDecoded = 0, sent = 0, lastSentAt = 0, sentAtLastProgress = 0;
  let stalledSince = null, statsFailedSince = null, disconnectTimer = null, recoveryGraceMs = 10000;
  function close() {
    generation++; cancel(timeout); cancel(statsTimer); cancel(disconnectTimer);
    timeout = null; statsTimer = null; disconnectTimer = null;
    stalledSince = null; statsFailedSince = null;
    renderStalledSince = null;
    const old = peer; peer = null; ready = false; id = null;
    onReset();
    if (renderer) renderer.stop(); renderer = null;
    if (track) { track.onended = null; track.stop(); track = null; }
    previousStats = null; lastRendered = 0;
    if (old) { old.ontrack = old.onconnectionstatechange = old.onicegatheringstatechange = null; old.close(); }
  }
  function fallback(reason = 'connection-failed', retryable = true) {
    const current = id;
    if (current === null) return;
    close(); onStop(reason);
    send({ type: 'rtc-fallback', id: current, ...(retryable ? {} : { retryable: false }) });
  }
  async function receive(message) {
    if (message.type === 'rtc-stop') {
      if (message.id === id) { close(); onStop('server-stop'); }
      return;
    }
    if (message.type !== 'rtc-offer') return;
    close(); id = message.id; codec = message.codec === 'H265' ? 'H265' : 'H264';
    recoveryGraceMs = [4000, 10000, 30000].includes(message.recoveryGraceMs) ? message.recoveryGraceMs : 10000;
    const token = generation, current = () => generation === token && peer !== null;
    timeout = schedule(() => { if (generation === token) fallback('first-frame-timeout'); }, Math.max(10000, recoveryGraceMs));
    try {
      if (!supportsCodec(codec)) { fallback('codec-unsupported', false); return; }
      const pc = createPeer(); peer = pc;
      const displayed = size => {
        if (!current() || ready || !size.width || !size.height) return;
        ready = true; cancel(timeout); timeout = null;
        lastDecoded = 0; sent = 0; sentAtLastProgress = 0; lastSentAt = now();
        lastRendered = renderer?.snapshot().renderedFrames ?? 0;
        send({ type: 'rtc-ready', id }); onReady(size);
        if (current()) statsTimer = schedule(sample, 1000);
      };
      const sample = async () => {
        if (!current()) return;
        try {
          const report = await pc.getStats();
          if (!current()) return;
          statsFailedSince = null;
          report.forEach(stat => {
            if (!current()) return;
            if (stat.type !== 'inbound-rtp' || (stat.kind || stat.mediaType) !== 'video') return;
            const decoded = stat.framesDecoded || 0, at = now();
            const output = renderer.snapshot();
            const presented = output.presentedFrames;
            const rendered = output.renderedFrames;
            if (rendered === null || rendered !== lastRendered || sent <= sentAtLastProgress || at - lastSentAt >= 2000) {
              renderStalledSince = null;
            } else if (renderStalledSince === null) renderStalledSince = at;
            lastRendered = rendered;
            if (decoded !== lastDecoded || sent <= sentAtLastProgress || at - lastSentAt >= 2000) {
              stalledSince = null; sentAtLastProgress = sent;
            } else if (stalledSince === null) stalledSince = at;
            const previous = previousStats;
            const elapsed = previous ? Math.max(1, at - previous.at) : 0;
            const delta = key => previous && Number.isFinite(stat[key]) && Number.isFinite(previous.stat[key])
              ? Math.max(0, stat[key] - previous.stat[key]) : null;
            const rate = key => elapsed && delta(key) !== null ? delta(key) * 1000 / elapsed : null;
            const averageMs = (total, count) => delta(count) > 0 && delta(total) !== null ? delta(total) * 1000 / delta(count) : null;
            let rttMs = null;
            report.forEach(entry => {
              if (entry.type === 'candidate-pair' && entry.state === 'succeeded' && entry.nominated && Number.isFinite(entry.currentRoundTripTime)) rttMs = entry.currentRoundTripTime * 1000;
            });
            const metrics = {
              receivedFps: rate('framesReceived'), decodedFps: rate('framesDecoded'),
              presentedFps: presented !== null && previous?.presented !== null && elapsed ? Math.max(0, presented - previous.presented) * 1000 / elapsed : null,
              renderedFps: rendered !== null && previous?.rendered !== null && elapsed ? Math.max(0, rendered - previous.rendered) * 1000 / elapsed : null,
              renderedFrames: rendered,
              decodeMs: averageMs('totalDecodeTime', 'framesDecoded'),
              jitterBufferMs: averageMs('jitterBufferDelay', 'jitterBufferEmittedCount'),
              processingMs: averageMs('totalProcessingDelay', 'framesDecoded'),
              dropped: delta('framesDropped'), lost: delta('packetsLost'), nack: delta('nackCount'),
              bitrateMbps: rate('bytesReceived') === null ? null : rate('bytesReceived') * 8 / 1e6,
              rttMs,
            };
            onStats(metrics.presentedFps ?? metrics.decodedFps, metrics);
            if (!current()) return;
            send({ type: 'rtc-feedback', id, packets: Math.max(0, stat.packetsReceived || 0),
              lost: Math.max(0, stat.packetsLost || 0), decoded, nack: Math.max(0, stat.nackCount || 0), rttMs });
            previousStats = { at, stat, presented, rendered };
            lastDecoded = decoded;
            // Static CarPlay screens legitimately stop producing frames.
            if (stalledSince !== null && at - stalledSince >= recoveryGraceMs) fallback('decode-stalled');
            else if (renderStalledSince !== null && at - renderStalledSince >= recoveryGraceMs) fallback('render-stalled');
          });
        } catch (_) {
          if (current()) {
            if (statsFailedSince === null) statsFailedSince = now();
            if (now() - statsFailedSince >= recoveryGraceMs) fallback('stats-unavailable');
          }
        }
        if (current()) statsTimer = schedule(sample, 1000);
      };
      renderer = createRenderer({
        onReady: displayed,
        onResize: size => { if (current()) onResize(size); },
        onError: (reason, retryable) => { if (current()) fallback(reason, retryable); },
      });
      pc.onconnectionstatechange = () => {
        if (!current()) return;
        if (['failed', 'closed'].includes(pc.connectionState)) { fallback(); return; }
        if (pc.connectionState === 'disconnected') {
          if (disconnectTimer === null) {
            const timer = schedule(() => {
              if (current() && disconnectTimer === timer && pc.connectionState !== 'connected') fallback();
            }, recoveryGraceMs);
            disconnectTimer = timer;
          }
        } else if (pc.connectionState === 'connected') {
          cancel(disconnectTimer); disconnectTimer = null;
        }
      };
      pc.ontrack = event => {
        if (!current()) return;
        if (track) { fallback('track-replaced'); return; }
        track = event.track;
        track.onended = () => { if (current()) fallback('track-ended'); };
        renderer.start(track, event.streams);
      };
      await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp });
      if (!current()) return;
      const answer = await pc.createAnswer();
      if (!current()) return;
      if (/^m=video 0(?: |\r?$)/m.test(answer.sdp)) { fallback('codec-unsupported', false); return; }
      // Use the browser as DTLS server: Mbed TLS 3.6 cannot reassemble a fragmented
      // ClientHello from newer Chrome versions. Passive is a standard answer role.
      await pc.setLocalDescription({ type: answer.type, sdp: answer.sdp.replace(/a=setup:active/g, 'a=setup:passive') });
      if (!current()) return;
      const answerReady = () => {
        if (!current() || pc.iceGatheringState !== 'complete') return;
        pc.onicegatheringstatechange = null;
        send({ type: 'rtc-answer', id, sdp: pc.localDescription.sdp });
      };
      if (pc.iceGatheringState === 'complete') answerReady();
      else pc.onicegatheringstatechange = answerReady;
    } catch (error) {
      if (generation === token) fallback(error.message === 'processor-unsupported' ? error.message : 'connection-failed',
        error.message !== 'processor-unsupported' && isRtcErrorRetryable(error));
    }
  }
  return { receive, close, fallback, get active() { return ready; }, get codec() { return codec; },
    get recovering() { return ready && (disconnectTimer !== null || stalledSince !== null || renderStalledSince !== null || statsFailedSince !== null); },
    progress(count) {
      if (count > sent) {
        if (now() - lastSentAt >= 2000) { stalledSince = null; renderStalledSince = null; }
        lastSentAt = now();
      }
      sent = count;
    },
  };
}

import { createBrowserAudio } from './browser-audio.js';
import { createBrowserLocation } from './browser-location.js';
import { createBrowserMediaSession } from './browser-media-session.js';

if (typeof document !== 'undefined') {
  const $ = id => document.getElementById(id);
  const screen = $('screen');
  const screens = [screen, $('screen-next'), $('screen-third')];
  const heldFrame = $('held-frame');
  let video = null;
  const canvas = $('video-canvas');
  const display = $('display');
  const touchSurface = $('touch-surface');
  // Read-only diagnostic snapshots; counters never include pairing credentials or video payloads.
  const rendererStats = { selectedMode: 'native', activeMode: 'waiting', renderedFps: null, renderedFrames: null, fallbackReason: null };
  const performanceStats = { source: null, transport: null, receiver: null, image: null, presentation: rendererStats };
  const webpSupport = probeWebp();
  Object.defineProperty(window, 'wheelplayStats', { configurable: true, get: () => JSON.parse(JSON.stringify(performanceStats)) });
  let rtc = null, rtcRequested = false, rtcFps = null;
  let socket = null, stopped = true, paired = false, retry = null, attempts = 0;
  let width = 1280, height = 720, live = false, lastStatus = 0, rtcMedia = {};
  let heartbeat = null, movePending = false, moveGeneration = 0, outbox = null;
  let authGeneration = 0, deviceCredential = null, storageAvailable = false;
  let launchPresentation = null;
  const locationOption = $('browser-location');
  locationOption.checked = false; // Never inherit form restoration or grant consent through storage.
  const browserLocation = createBrowserLocation({ send: data => send(data), onState(enabled, message, key) {
    locationOption.checked = enabled;
    $('location-status').textContent = message;
    $('location-message').textContent = message;
    $('location-message').hidden = !message || ['web_location_ready', 'web_location_active'].includes(key);
  } });
  locationOption.addEventListener('change', () => { void browserLocation.setEnabled(locationOption.checked); });
  const devicePreference = 'wheelplay.browserDevice';
  let pairingToken = null, qrGeneration = 0, qrTimer = null, qrRequest = null;
  let viewportReportTimer = null, lastViewportSignature = '';
  const ignoreZoomOption = $('ignore-browser-zoom');
  const ignoreZoomPreference = 'wheelplay.ignoreBrowserZoom';
  try { ignoreZoomOption.checked = window.localStorage.getItem(ignoreZoomPreference) === 'true'; } catch (_) {}
  function syncDisplayZoom() {
    if (!display.style) return;
    const area = ignoreZoomOption.checked && !display.hidden
      ? unzoomedDisplayRect(display.parentElement.getBoundingClientRect(), window.visualViewport) : null;
    display.classList.toggle('ignore-browser-zoom', Boolean(area));
    if (area) {
      Object.assign(display.style, {
        left: `${area.left}px`, top: `${area.top}px`,
        width: `${area.width * area.scale}px`, height: `${area.height * area.scale}px`,
        transform: `scale(${1 / area.scale})`,
      });
    } else {
      for (const name of ['left', 'top', 'width', 'height', 'transform']) display.style.removeProperty(name);
    }
  }
  ignoreZoomOption.addEventListener('change', () => {
    try { window.localStorage.setItem(ignoreZoomPreference, String(ignoreZoomOption.checked)); } catch (_) {}
    release(); syncDisplayZoom(); scheduleViewportReport();
  });
  const presentation = createPresentationController({ onChange: renderPresentation });
  const jpeg = createJpegPresenter(screens, {
    onFrame: (meta, frameWidth, frameHeight) => {
      if (meta.width && (meta.width !== frameWidth || meta.height !== frameHeight)) return false;
      if (!presentation.present(meta, frameWidth, frameHeight, 'jpeg', meta.revision)) return false;
      width = frameWidth; height = frameHeight;
      clearHeldFrame(); return true;
    },
    onError: meta => {
      if (meta.mime === 'image/webp') send({ type: 'image-format', format: 'jpeg' });
      if (!jpeg.current) presentation.invalidate('jpeg');
    },
  });
  const rendererPreference = 'wheelplay.videoRenderer';
  let selectedMode = 'native';
  try {
    selectedMode = videoRendererPreference(window.localStorage.getItem(rendererPreference), window.localStorage.getItem('wheelplay.canvasVideo'));
    window.localStorage.setItem(rendererPreference, selectedMode);
  } catch (_) {}
  let launchMode = selectedMode;
  const processorSupported = () => typeof MediaStreamTrackProcessor === 'function';
  function updateRendererOptions() {
    for (const mode of ['native', 'video-canvas', 'track-canvas']) {
      $(`renderer-${mode}`).checked = selectedMode === mode;
      $(`renderer-${mode}-card`).classList.toggle('selected', selectedMode === mode);
    }
    $('renderer-hint').hidden = selectedMode !== 'track-canvas' || processorSupported();
  }
  for (const mode of ['native', 'video-canvas', 'track-canvas']) {
    $(`renderer-${mode}`).addEventListener('change', () => {
      if (!$(`renderer-${mode}`).checked) return;
      selectedMode = mode; updateRendererOptions();
      try { window.localStorage.setItem(rendererPreference, mode); } catch (_) {}
    });
  }
  updateRendererOptions();
  function createLegacyVideo() {
    video = document.createElement('video');
    video.id = 'video'; video.autoplay = true; video.muted = true; video.playsInline = true; video.hidden = true;
    video.setAttribute('disablepictureinpicture', '');
    video.setAttribute('aria-label', t('web_live_carplay_video'));
    display.insertBefore(video, canvas); bindSurface(video);
    return video;
  }
  function showRtcSize(size) {
    if (!presentation.present(rtcMedia, size.width, size.height, 'rtc', rtcMedia.revision)) return;
    width = size.width; height = size.height; jpeg.cancelPending(); jpeg.hide(); clearHeldFrame();
  }
  const pairApi = async (path, request = null) => {
    const headers = request?.token ? { 'X-Pair-Token': request.token } :
      request?.code ? { 'X-Pair-Code': request.code } :
      request?.device ? { 'X-Device-Id': request.device.id, 'X-Device-Secret': request.device.secret } :
      request ? { 'X-Pair-Id': request.id, 'X-Pair-Secret': request.secret } : {};
    const controller = typeof AbortController === 'undefined' ? null : new AbortController();
    const timeout = setTimeout(() => controller && controller.abort(), 8000);
    try {
      const response = await fetch(`/pair/${path}`, { method: 'POST', headers, cache: 'no-store',
        keepalive: path === 'cancel' || path === 'revoke', ...(controller ? { signal: controller.signal } : {}) });
      if (!response.ok) {
        const error = new Error(response.status === 404 ? 'expired' : 'unavailable');
        error.status = response.status; throw error;
      }
      return await response.json();
    } finally { clearTimeout(timeout); }
  };
  try {
    const stored = window.localStorage.getItem(devicePreference);
    storageAvailable = true;
    if (stored) {
      const device = JSON.parse(stored);
      if (typeof device.id === 'string' && typeof device.secret === 'string' && device.id.length === 32 && device.secret.length === 32) deviceCredential = device;
    }
  } catch (_) {}
  function showPairingReady(name = t("web_browser_paired")) {
    $('remembered-device').hidden = false;
    $('device-name').textContent = name;
    $('qr-pairing').hidden = true; $('pair-instructions').hidden = true;
    $('code-entry').hidden = true; $('code').required = false; $('code').disabled = true;
    $('connect-label').textContent = t("web_start_display");
    $('connect').disabled = false;
    $('status').textContent = t("web_paired_ready_to_start"); $('message').textContent = '';
  }
  function showPairingEntry() {
    $('remembered-device').hidden = true;
    $('qr-pairing').hidden = false; $('pair-instructions').hidden = false;
    $('code-entry').hidden = false; $('code').required = true; $('code').disabled = false;
    $('connect-label').textContent = t("web_pair_start_display");
    $('connect').disabled = false;
  }
  async function preparePairing(token, generation) {
    pairingToken = token;
    let name = t("web_browser_paired");
    let message = t("web_paired_choose_the_options_above_then_tap_start_display");
    if (storageAvailable) {
      try {
        const device = await pairApi('remember', { token });
        if (generation !== authGeneration) return;
        deviceCredential = device;
        window.localStorage.setItem(devicePreference, JSON.stringify(device));
        name = device.name;
      } catch (_) {
        message = t("web_paired_for_this_session_but_the_device_could_not");
      }
    } else message = t("web_paired_for_this_session_this_browser_cannot_remember_the");
    if (generation !== authGeneration) return;
    showPairingReady(name); $('device-status').textContent = message;
  }
  async function initializePairing(linkedCode = null) {
    const generation = ++authGeneration;
    stopQr(); pairingToken = null;
    showPairingEntry(); $('connect').disabled = true;
    if (deviceCredential) {
      $('status').textContent = t("web_restoring_pairing");
      try {
        const result = await pairApi('resume', { device: deviceCredential });
        if (generation !== authGeneration) return;
        pairingToken = result.token;
        showPairingReady(result.name);
        $('device-status').textContent = t("web_pairing_restored_automatically_choose_the_options_above_then_tap");
        return;
      } catch (error) {
        if (generation !== authGeneration) return;
        if (error.status === 401) {
          deviceCredential = null;
          try { window.localStorage.removeItem(devicePreference); } catch (_) {}
          $('message').textContent = t("web_remembered_device_expired_or_removed_pair_again");
        } else {
          showPairingReady(deviceCredential.name || t("web_remembered_browser"));
          $('status').textContent = t("web_waiting_to_restore_pairing");
          $('device-status').textContent = t("web_could_not_restore_pairing_yet_check_the_service_and");
          return;
        }
      }
    }
    if (linkedCode) {
      $('code').value = linkedCode;
      try {
        const result = await pairApi('code', { code: linkedCode });
        if (generation !== authGeneration) return;
        await preparePairing(result.token, generation); return;
      } catch (_) {
        if (generation !== authGeneration) return;
        $('message').textContent = t("web_the_pairing_code_in_this_link_has_expired_scan");
      }
    }
    if (generation !== authGeneration) return;
    showPairingEntry(); $('status').textContent = t("web_waiting_to_pair"); startQr();
  }
  function stopQr(cancel = true) {
    qrGeneration++; clearTimeout(qrTimer);
    if (cancel && qrRequest) pairApi('cancel', qrRequest).catch(() => {});
    qrRequest = null;
    $('pair-qr').onload = null; $('pair-qr').onerror = null;
    $('pair-qr').hidden = true; $('pair-qr').removeAttribute('src');
  }
  async function startQr() {
    stopQr(); const generation = qrGeneration;
    $('qr-status').textContent = t("web_generating_qr_code");
    $('refresh-qr').disabled = true;
    try {
      const request = await pairApi('request');
      if (generation !== qrGeneration) { pairApi('cancel', request).catch(() => {}); return; }
      qrRequest = request;
      $('pair-qr').onload = () => {
        if (generation === qrGeneration) $('pair-qr').hidden = false;
      };
      $('pair-qr').onerror = () => {
        if (generation !== qrGeneration) return;
        $('pair-qr').hidden = true;
        $('qr-status').textContent = t("web_could_not_load_qr_code_refresh_and_try_again");
      };
      $('pair-qr').src = `/pair/qr?id=${encodeURIComponent(request.id)}&secret=${encodeURIComponent(request.secret)}`;
      $('qr-status').textContent = t("web_scan_with_the_server_app_and_allow_the_connection");
      const expiresAt = Date.now() + request.expiresIn * 1000;
      const poll = async () => {
        try {
          if (generation !== qrGeneration) return;
          if (Date.now() >= expiresAt) throw new Error('expired');
          const result = await pairApi('status', request);
          if (generation !== qrGeneration) return;
          if (result.state === 'approved') {
            stopQr(false); $('connect').disabled = true;
            await preparePairing(result.token, ++authGeneration); return;
          }
          qrTimer = setTimeout(poll, 1000);
        } catch (error) {
          if (generation !== qrGeneration) return;
          stopQr();
          $('qr-status').textContent = error.message === 'expired' ? t("web_qr_code_expired_refresh_and_scan_again") : t("web_could_not_check_pairing_status_refresh_the_qr_code");
        }
      };
      qrTimer = setTimeout(poll, 1000);
    } catch (_) {
      if (generation === qrGeneration) $('qr-status').textContent = t("web_qr_code_unavailable_refresh_and_try_again_or_enter");
    } finally {
      if (generation === qrGeneration) $('refresh-qr').disabled = false;
    }
  }
  $('refresh-qr').addEventListener('click', startQr);
  const pointers = new Map();
  let maxTouchContacts = 2;
  let pointerInput = false, keyboardInput = false, backButton = false;
  const send = (data, kind) => {
    if ((data.type === 'touch' || data.type === 'input') && presentation.current.videoEpoch !== null) {
      const { sessionId, videoEpoch } = presentation.current;
      data = { ...data, sessionId, videoEpoch };
    }
    return outbox ? outbox.send(data, kind) : false;
  };
  const desktop = createDesktopInput({ send, pointerEnabled: () => pointerInput,
    keyboardEnabled: () => keyboardInput, interactive: () => live,
    point: (event, surface, clamp) => mapPoint(event.clientX, event.clientY, surface.getBoundingClientRect(), width, height, clamp) });
  $('desktop-back').addEventListener('click', () => {
    if (!backButton || !live) return;
    release();
    send({ type: 'input', kind: 'key', key: 'BrowserBack', down: true }, 'boundary');
    send({ type: 'input', kind: 'key', key: 'BrowserBack', down: false }, 'boundary');
  });
  let desktopRevision = presentation.current.revision;
  const browserMedia = createBrowserMediaSession({ send });
  const browserAudio = createBrowserAudio({
    send,
    sendBinary: packet => {
      if (socket?.readyState !== WebSocket.OPEN || socket.bufferedAmount > 8192) return false;
      try { socket.send(packet); return true; } catch (_) { return false; }
    },
    onState: ({ error }) => {
      $('audio-message').textContent = error;
      $('audio-message').hidden = !error || !paired;
    },
  });
  document.addEventListener('pointerdown', () => browserAudio.unlock(), { capture: true });
  $('secure-setup').addEventListener('toggle', async () => {
    if (!$('secure-setup').open) return;
    $('https-entry').removeAttribute('href');
    try {
      const response = await fetch('/tls.json', { cache: 'no-store' });
      const info = await response.json();
      if (info.available && Number.isInteger(info.port) && info.port >= 1 && info.port <= 65535) {
        const endpoint = new URL(location.href);
        endpoint.protocol = 'https:'; endpoint.port = String(info.port); endpoint.pathname = '/';
        endpoint.search = ''; endpoint.hash = '';
        $('https-entry').href = endpoint.href;
      }
      $('tls-fingerprint').textContent = info.enabled === false ? t("web_https_is_disabled_enable_it_in_the_app_s")
        : info.fingerprint || t("web_preparing_https_close_and_reopen_this_section_shortly");
      $('tls-error').textContent = info.error || '';
    } catch (_) { $('tls-error').textContent = t("web_certificate_information_is_temporarily_unavailable"); }
  });
  function scheduleViewportReport() {
    clearTimeout(viewportReportTimer);
    viewportReportTimer = setTimeout(() => {
      syncDisplayZoom();
      if (!socket || socket.readyState !== WebSocket.OPEN || display.hidden) return;
      const rect = display.getBoundingClientRect();
      const zoom = ignoreZoomOption.checked ? window.visualViewport?.scale || 1 : 1;
      const scale = (window.devicePixelRatio || 1) * zoom;
      const size = browserViewportSize(rect.width * scale, rect.height * scale);
      if (!size) return;
      const { width, height } = size;
      const signature = `${width}x${height}`;
      if (signature === lastViewportSignature) return;
      lastViewportSignature = signature;
      send({ type: 'viewport', width, height });
    }, 180);
  }
  if (typeof ResizeObserver !== 'undefined') new ResizeObserver(scheduleViewportReport).observe(display);
  window.addEventListener('resize', scheduleViewportReport);
  for (const event of ['resize', 'scroll']) window.visualViewport?.addEventListener(event, () => {
    if (!ignoreZoomOption.checked) return;
    syncDisplayZoom(); scheduleViewportReport();
  });
  function cancelMove() { moveGeneration++; movePending = false; }
  function contacts() { return [...pointers.values()].map(p => ({ id: p.id, x: p.x, y: p.y, down: true })); }
  function release() { cancelMove(); pointers.clear(); desktop.release(); send({ type: 'touch', contacts: [] }); }
  function renderPresentation(value) {
    if (value.revision !== desktopRevision) {
      desktop.release(false); desktopRevision = value.revision;
    }
    live = value.interactive;
    for (const surface of [...screens, video, canvas, heldFrame].filter(Boolean)) surface.classList.toggle('stale', !value.visible);
    touchSurface.classList.toggle('stale', !live);
    $('waiting').hidden = value.visible;
    $('desktop-back').disabled = !live;
    if (!live && (pointers.size || desktop.active)) release();
    const rtcVisible = rtc && value.visible && value.frame?.transport === 'rtc';
    rendererStats.activeMode = rtcVisible ? launchMode : value.visible ? 'jpeg' : 'waiting';
    const rendererLabel = launchMode === 'native' ? 'Video' : t(launchMode === 'video-canvas' ? 'web_video_renderer_compat' : 'web_video_renderer_direct');
    const fallbackLabel = rendererStats.fallbackReason ? t(rendererStats.fallbackReason === 'processor-unsupported' ? 'web_video_renderer_unavailable' : 'web_video_renderer_failed') : '';
    const transport = rtcVisible ? `${rtc.codec === 'H265' ? 'HEVC' : 'H.264'} / WebRTC / ${rendererLabel}${rtcFps === null ? '' : ` · ${rtcFps} fps`}` : `${value.frame?.mime === 'image/webp' ? 'WebP' : 'JPEG'}${fallbackLabel}`;
    $('status').textContent = value.state === 'recovering' ? t('web_video_recovering', transport) :
      value.visible ? value.interactive ? t('web_transport_touch_connected', transport, rtc?.recovering ? t('web_waiting_for_recovery') : '') :
        t('web_video_input_unavailable', transport) : t('web_connected_waiting_for_iphone_video');
    $('media-message').hidden = !value.visible || value.interactive;
    $('media-message').textContent = value.visible && !value.interactive ? $('status').textContent : '';
  }
  function clearHeldFrame() {
    heldFrame.hidden = true; heldFrame.width = heldFrame.height = 0;
  }
  function holdRtcFrame() {
    if (presentation.current.frame?.transport !== 'rtc' || !heldFrame.hidden) return;
    try {
      const source = canvas.hidden ? video : canvas;
      const frame = presentation.current.frame;
      heldFrame.width = frame.width; heldFrame.height = frame.height;
      heldFrame.getContext('2d', { alpha: false }).drawImage(source, 0, 0, frame.width, frame.height);
      heldFrame.hidden = false;
    } catch (_) { clearHeldFrame(); presentation.forget(); }
  }
  function cleanupImage() {
    jpeg.clear(); clearHeldFrame();
  }
  function cleanupRtc() {
    if (rtc) rtc.close(); rtc = null; rtcRequested = false; rtcFps = null;
    video = null;
    performanceStats.source = performanceStats.transport = performanceStats.receiver = performanceStats.image = null;
    rendererStats.activeMode = 'waiting';
  }
  function exitLaunchFullscreen() {
    try { Promise.resolve(document.exitFullscreen()).catch(() => {}); } catch (_) {}
  }
  function prepareLaunchPresentation() {
    const presentation = { immersive: $('fullscreen-on-start').checked, ownsFullscreen: false, cancelled: false };
    launchPresentation = presentation;
    if (!presentation.immersive || document.fullscreenElement || !document.documentElement?.requestFullscreen) return;
    presentation.ownsFullscreen = true;
    try {
      // Keep the request in the submit gesture, before asynchronous pairing or WebSocket setup.
      Promise.resolve(document.documentElement.requestFullscreen()).then(() => {
        if (presentation.cancelled && !launchPresentation?.immersive && document.fullscreenElement) exitLaunchFullscreen();
      }).catch(() => { presentation.ownsFullscreen = false; });
    } catch (_) { presentation.ownsFullscreen = false; }
  }
  function restoreLaunchPresentation() {
    if (!launchPresentation) return;
    launchPresentation.cancelled = true;
    launchPresentation.immersive = false;
    if (launchPresentation.ownsFullscreen && document.fullscreenElement) exitLaunchFullscreen();
  }
  function disconnect(refreshQr = true) {
    stopQr();
    authGeneration++;
    restoreLaunchPresentation();
    stopped = true; paired = false; release();
    if (outbox) { outbox.close(); outbox = null; }
    clearTimeout(retry); clearInterval(heartbeat);
    if (socket) { const old = socket; socket = null; old.close(); }
    presentation.close(); cleanupRtc(); cleanupImage();
    browserMedia.close(); browserAudio.close(); $('audio-message').hidden = true;
    browserLocation.close();
    $('topbar').hidden = false; $('pairing').hidden = false; $('display').hidden = true;
    syncDisplayZoom();
    $('disconnect').hidden = true; $('connect').disabled = false;
    $('status').textContent = t("web_disconnected");
    if (refreshQr) initializePairing();
  }
  function connect() {
    clearTimeout(retry);
    $('connect').disabled = true;
    $('status').textContent = paired ? t("web_reconnecting") : t("web_connecting");
    const auth = pairingToken ? `token=${encodeURIComponent(pairingToken)}` : `code=${encodeURIComponent($('code').value)}`;
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/stream?${auth}&media=2&images=3&nowPlaying=1`);
    ws.binaryType = 'arraybuffer';
    const mode = launchMode;
    socket = ws;
    const connectTimer = setTimeout(() => ws.close(), 8000);
    ws.onopen = () => {
      clearTimeout(connectTimer);
      if (socket !== ws) return;
      if (outbox) outbox.close();
      outbox = createControlOutbox(ws, { onFailure: () => {
        if (socket !== ws) return;
        cancelMove(); pointers.clear(); presentation.close(); ws.close();
      } });
      paired = true; attempts = 0; lastStatus = Date.now();
      browserMedia.open(); browserAudio.attach();
      browserLocation.attach();
      cleanupRtc();
      presentation.open();
      webpSupport.then(supported => {
        if (supported && socket === ws && ws.readyState === WebSocket.OPEN) send({ type: 'image-format', format: 'webp' });
      });
      Object.assign(rendererStats, { selectedMode: mode, activeMode: 'waiting', renderedFps: null, renderedFrames: null,
        fallbackReason: mode === 'track-canvas' && !processorSupported() ? 'processor-unsupported' : null });
      rtc = createRtcReceiver(callbacks => {
        if (mode === 'track-canvas') {
          if (!processorSupported()) throw new Error('processor-unsupported');
          return createTrackCanvasRenderer(canvas, callbacks);
        }
        return createVideoRenderer(mode, canvas, { ...callbacks, createVideo: createLegacyVideo });
      }, { send,
        onReset: () => { holdRtcFrame(); presentation.invalidate('rtc'); },
        onResize: size => { if (socket === ws) showRtcSize(size); },
        onReady: size => {
          if (socket !== ws) return;
          rendererStats.activeMode = mode; rendererStats.fallbackReason = null;
          rendererStats.renderedFrames = mode === 'native' ? null : 1;
          showRtcSize(size);
        },
        onStop: reason => {
          if (socket !== ws) return;
          video = null; rtcFps = null; rendererStats.fallbackReason = reason; rendererStats.renderedFps = null;
          presentation.invalidate('rtc');
        },
        onStats: (fps, metrics) => {
          const outputFps = mode === 'track-canvas' ? metrics.renderedFps : fps;
          rtcFps = outputFps === null ? null : Math.round(outputFps); performanceStats.receiver = metrics;
          rendererStats.renderedFps = metrics.renderedFps; rendererStats.renderedFrames = metrics.renderedFrames;
          presentation.refresh();
        },
      });
      $('message').textContent = '';
      $('phone-connect').disabled = false;
      $('phone-connect-message').textContent = t("web_keep_wheelplay_in_the_foreground_on_android_tap_connect");
      $('pairing').hidden = true; $('display').hidden = false; $('disconnect').hidden = false;
      $('topbar').hidden = $('hide-toolbar').checked || Boolean(launchPresentation?.immersive);
      lastViewportSignature = '';
      scheduleViewportReport();
      $('status').textContent = t("web_connected_waiting_for_video");
      send({ type: 'ping' });
      clearInterval(heartbeat);
      heartbeat = setInterval(() => {
        presentation.refresh();
        if (Date.now() - lastStatus > 6000) { ws.close(); return; }
        send({ type: 'ping' });
        if (pointers.size) send({ type: 'touch', contacts: contacts() }, 'move');
        desktop.heartbeat();
      }, 400);
    };
    ws.onmessage = event => {
      if (socket !== ws || ws.readyState !== WebSocket.OPEN) return;
      if (typeof event.data === 'string') {
        const data = JSON.parse(event.data);
        if (browserMedia.receive(data)) return;
        if (browserAudio.control(data)) return;
        if (data.type === 'rtc-offer' || data.type === 'rtc-stop') {
          if (data.type === 'rtc-stop') rtcRequested = false;
          else {
            const previousRevision = presentation.current.revision;
            if (!presentation.accepts(data)) return;
            if (previousRevision !== presentation.current.revision) jpeg.cancelPending();
            rtcMedia = { ...data, revision: presentation.current.revision };
          }
          rtc.receive(data); return;
        }
        if (data.type === 'phone-connect-result') {
          $('phone-connect').disabled = false;
          $('phone-connect-message').textContent = data.message;
          return;
        }
        if (data.type === 'busy') {
          const message = data.message; disconnect(); $('message').textContent = message; return;
        }
        if (data.type === 'status') {
          performanceStats.source = data.performance?.source ?? null;
          performanceStats.transport = data.performance?.transport ?? null;
          performanceStats.image = data.performance?.image ?? null;
          lastStatus = Date.now();
          const nextTouchLimit = Number.isInteger(data.maxTouchContacts) ? Math.max(0, Math.min(10, data.maxTouchContacts)) : 2;
          if (nextTouchLimit !== maxTouchContacts) { release(); maxTouchContacts = nextTouchLimit; }
          const previousRevision = presentation.current.revision;
          if (!presentation.status(data)) return;
          if (previousRevision !== presentation.current.revision) desktop.release(false);
          if (pointerInput !== (data.pointerInput === true) || keyboardInput !== (data.keyboardInput === true)) desktop.release();
          pointerInput = data.pointerInput === true; keyboardInput = data.keyboardInput === true;
          backButton = data.backButton === true;
          $('desktop-back').hidden = !backButton;
          if (previousRevision !== presentation.current.revision) {
            jpeg.cancelPending();
            if (rtcMedia.revision !== presentation.current.revision) { rtc.close(); rtcRequested = false; }
          }
          $('stage').textContent = data.stage || t("web_waiting_for_iphone_video");
          rtc.progress(data.rtcFrames || 0);
          if (data.rtcAvailable && !rtcRequested && !rtc.active && typeof RTCPeerConnection !== 'undefined' &&
              (mode !== 'track-canvas' || processorSupported())) {
            rtcRequested = true; send({ type: 'rtc-start' });
          }
          // The server resets availability when the iPhone starts a new video configuration.
          if (!data.rtcAvailable) rtcRequested = false;
          presentation.refresh();
        }
        if (data.type === 'input-unavailable') presentation.inputUnavailable();
        return;
      }
      if (browserAudio.receive(event.data)) return;
      let packet;
      try { packet = decodeVideoPacket(event.data); } catch (_) { ws.close(); return; }
      const meta = packet || {};
      const acknowledge = () => {
        if (socket !== ws || ws.readyState !== WebSocket.OPEN) return;
        send(packet?.protocol === 3 ? { type: 'ack', sessionId: packet.sessionId,
          videoEpoch: packet.videoEpoch, sequence: packet.sequence } : { type: 'ack' });
      };
      const previousRevision = presentation.current.revision;
      if (!presentation.accepts(meta)) { acknowledge(); return; }
      if (previousRevision !== presentation.current.revision) { rtc.close(); rtcRequested = false; }
      if (rtc?.active) { acknowledge(); return; }
      meta.revision = presentation.current.revision;
      const payload = packet ? packet.jpeg : event.data;
      jpeg.load(payload instanceof ArrayBuffer ? new Blob([payload], { type: packet?.mime || 'image/jpeg' }) : payload, meta,
        () => socket === ws && ws.readyState === WebSocket.OPEN && !rtc?.active && presentation.current.revision === meta.revision,
        acknowledge);
    };
    ws.onclose = () => {
      clearTimeout(connectTimer);
      if (socket !== ws) return;
      if (outbox) { outbox.close(); outbox = null; }
      cancelMove();
      socket = null; pointers.clear(); desktop.release(false); pointerInput = false; keyboardInput = false; backButton = false; $('desktop-back').hidden = true;
      clearInterval(heartbeat); presentation.close(); cleanupRtc(); cleanupImage();
      browserMedia.close(); browserAudio.close(); $('audio-message').hidden = true;
      browserLocation.close();
      if (stopped) return;
      if (!paired || ++attempts > 8) {
        disconnect(); $('status').textContent = t("web_could_not_connect");
        $('message').textContent = t("web_check_that_the_server_is_running_and_the_pairing");
        return;
      }
      $('status').textContent = t("web_connection_interrupted_retrying");
      $('stage').textContent = t("web_check_that_the_server_and_head_unit_are_still");
      const generation = authGeneration;
      retry = setTimeout(async () => {
        if (deviceCredential) {
          try {
            const result = await pairApi('resume', { device: deviceCredential });
            if (stopped || generation !== authGeneration) return;
            pairingToken = result.token;
          } catch (error) {
            if (stopped || generation !== authGeneration) return;
            if (error.status === 401) { disconnect(); return; }
          }
        }
        if (!stopped && generation === authGeneration) connect();
      }, Math.min(8000, 500 * 2 ** attempts));
    };
    ws.onerror = () => {}; // onclose owns retry and visible error state.
  }
  $('connect-form').addEventListener('submit', event => {
    event.preventDefault();
    launchMode = selectedMode;
    const generation = ++authGeneration;
    prepareLaunchPresentation();
    stopQr(); $('connect').disabled = true; $('message').textContent = '';
    const start = () => { if (generation === authGeneration) { stopped = false; attempts = 0; connect(); } };
    if (pairingToken && !deviceCredential) { start(); return; }
    (async () => {
      try {
        if (deviceCredential) {
          const result = await pairApi('resume', { device: deviceCredential });
          if (generation !== authGeneration) return;
          pairingToken = result.token;
        } else {
          const result = await pairApi('code', { code: $('code').value });
          if (generation !== authGeneration) return;
          await preparePairing(result.token, generation);
        }
        start();
      } catch (error) {
        if (generation !== authGeneration) return;
        restoreLaunchPresentation();
        $('connect').disabled = false;
        if (error.status === 401 && deviceCredential) {
          pairingToken = null; deviceCredential = null;
          try { window.localStorage.removeItem(devicePreference); } catch (_) {}
          showPairingEntry(); startQr();
        }
        $('status').textContent = t("web_could_not_pair");
        $('message').textContent = t("web_check_the_service_and_network_or_scan_again_enter");
      }
    })();
  });
  $('phone-connect').addEventListener('click', () => {
    if (!paired || !send({ type: 'phone-connect' })) {
      $('phone-connect-message').textContent = t("web_browser_connection_interrupted_pair_again_and_retry");
      return;
    }
    $('phone-connect').disabled = true;
    $('phone-connect-message').textContent = t("web_requesting_iphone_connection");
  });
  $('disconnect').addEventListener('click', () => disconnect());
  $('fullscreen').addEventListener('click', async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (document.documentElement.requestFullscreen) await document.documentElement.requestFullscreen();
      else $('status').textContent = t("web_this_browser_does_not_support_full_screen");
    } catch (_) { $('status').textContent = t("web_use_the_browser_menu_to_toggle_full_screen"); }
  });
  document.addEventListener('fullscreenchange', () => {
    $('fullscreen').textContent = document.fullscreenElement ? t("web_exit_full_screen") : t("web_full_screen");
    if (!document.fullscreenElement && launchPresentation?.immersive) {
      launchPresentation.immersive = false;
      launchPresentation.ownsFullscreen = false;
      $('topbar').hidden = false;
    }
    scheduleViewportReport();
  });
  // A separate layer prevents Android Chrome's native video gestures cancelling drags.
  function bindSurface(surface) {
  desktop.bind(surface);
  surface.addEventListener('contextmenu', event => event.preventDefault());
  surface.addEventListener('pointerdown', event => {
    if (pointerInput && event.pointerType === 'mouse') return;
    if (!live || pointers.size >= maxTouchContacts || (event.pointerType === 'mouse' && event.button !== 0)) return;
    const point = mapPoint(event.clientX, event.clientY, surface.getBoundingClientRect(), width, height);
    if (!point) return;
    event.preventDefault();
    const used = new Set([...pointers.values()].map(p => p.id));
    let id = 0; while (used.has(id)) id++;
    pointers.set(event.pointerId, { id, ...point });
    surface.setPointerCapture(event.pointerId);
    cancelMove();
    send({ type: 'touch', contacts: contacts() });
  });
  surface.addEventListener('pointermove', event => {
    const p = pointers.get(event.pointerId); if (!p) return;
    const point = mapPoint(event.clientX, event.clientY, surface.getBoundingClientRect(), width, height, true);
    if (!point) return;
    Object.assign(p, point);
    if (!movePending) {
      movePending = true;
      const generation = moveGeneration;
      requestAnimationFrame(() => {
        if (generation !== moveGeneration) return;
        movePending = false;
        if (pointers.size) send({ type: 'touch', contacts: contacts() }, 'move');
      });
    }
  });
  function up(event) {
    const p = pointers.get(event.pointerId); if (!p) return;
    cancelMove();
    pointers.delete(event.pointerId);
    send({ type: 'touch', contacts: [...contacts(), { ...p, down: false }] });
  }
  surface.addEventListener('pointerup', up);
  surface.addEventListener('pointercancel', up);
  surface.addEventListener('lostpointercapture', up);
  }
  for (const surface of [...screens, canvas, touchSurface]) bindSurface(surface);
  window.addEventListener('blur', release);
  document.addEventListener('visibilitychange', () => { if (document.hidden) release(); presentation.refresh(); });
  window.addEventListener('pagehide', () => disconnect(false));
  window.addEventListener('pageshow', event => { if (event.persisted) initializePairing(); });
  const linkedCode = pairingCodeFromUrl(window.location?.search || '');
  if (linkedCode) {
    $('code').value = linkedCode;
    // Keep the short-lived code out of copied URLs and browser history entries.
    window.history?.replaceState(null, '', `${window.location.pathname}${window.location.hash}`);
  }
  initializePairing(linkedCode);
}
