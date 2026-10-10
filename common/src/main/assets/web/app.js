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

// Keep essential controls until WebSocket accepts them. A successful send is never retried.
export function createControlOutbox(socket, {
  schedule = setTimeout, cancel = clearTimeout, now = () => Date.now(), onFailure = () => socket.close(),
} = {}) {
  const touches = [];
  const signals = [];
  let ack = null, audioAck = null, audioReady = null, ping = null, feedback = null, viewport = null, timer = null, blockedSince = null, closed = false;
  const close = () => {
    closed = true; cancel(timer); timer = null;
    ack = null; audioAck = null; audioReady = null; ping = null; feedback = null; viewport = null; touches.length = 0; signals.length = 0;
  };
  const fail = () => { close(); onFailure(); };
  const flush = () => {
    if (closed) return;
    if (socket.readyState !== 1) { close(); return; }
    try {
      while ((ack || audioAck || audioReady || touches.length || signals.length || viewport || feedback || ping) && socket.bufferedAmount < 8192) {
        if (ack) { socket.send(ack); ack = null; }
        else if (audioAck) { socket.send(audioAck); audioAck = null; }
        else if (audioReady) { socket.send(audioReady); audioReady = null; }
        else if (touches.length) { socket.send(touches[0].json); touches.shift(); }
        else if (signals.length) { socket.send(signals[0]); signals.shift(); }
        else if (viewport) { socket.send(viewport); viewport = null; }
        else if (feedback) { socket.send(feedback); feedback = null; }
        else { socket.send(ping); ping = null; }
        blockedSince = null;
      }
    } catch (_) { fail(); return; }
    if (ack || audioAck || audioReady || touches.length || signals.length || viewport || feedback || ping) {
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
      if (data.type === 'ack') ack = json;
      else if (data.type === 'audio-ack') audioAck = json;
      else if (data.type === 'audio-ready') audioReady = json;
      else if (data.type === 'ping') ping = json;
      else if (data.type === 'rtc-feedback') feedback = json;
      else if (data.type === 'viewport') viewport = json;
      else if (data.type === 'touch') {
        const tail = touches[touches.length - 1];
        // Only consecutive moves may be replaced; never cross a down/up/release boundary.
        if (kind === 'move' && tail && tail.kind === 'move') tail.json = json;
        else if (touches.length < 64) touches.push({ kind, json });
        else { fail(); return false; }
      } else if (['rtc-start', 'rtc-answer', 'rtc-ready', 'rtc-fallback', 'phone-connect'].includes(data.type)) {
        if (signals.length >= 8 || json.length > 32768) { fail(); return false; }
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
  onFrame = () => {}, onError = () => {},
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
      if (!context) throw new Error('Canvas 2D unavailable');
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

// Video WebRTC remains receive-only. Browser audio uses the paired WebSocket.
export function createRtcReceiver(video, {
  send, onReady = () => {}, onStop = () => {}, onStats = () => {},
  onTrack = () => {}, onReset = () => {},
  createPeer = () => new RTCPeerConnection({ iceServers: [] }),
  supportsCodec = codec => {
    if (typeof RTCRtpReceiver === 'undefined' || !RTCRtpReceiver.getCapabilities) return true;
    const caps = RTCRtpReceiver.getCapabilities('video');
    return !caps || caps.codecs.some(c => c.mimeType.toLowerCase() === `video/${codec.toLowerCase()}`);
  },
  schedule = setTimeout, cancel = clearTimeout, now = () => Date.now(),
} = {}) {
  let frameCallback = null, presented = 0, previousStats = null;
  let codec = 'H264';
  let peer = null, id = null, generation = 0, timeout = null, statsTimer = null, ready = false;
  let lastDecoded = 0, lastDecodedAt = 0, lastSample = 0, sent = 0, lastSentAt = 0;
  function close() {
    generation++; cancel(timeout); cancel(statsTimer); timeout = null; statsTimer = null;
    const old = peer; peer = null; ready = false; id = null;
    if (frameCallback !== null && video.cancelVideoFrameCallback) video.cancelVideoFrameCallback(frameCallback);
    frameCallback = null; presented = 0; previousStats = null;
    video.onloadeddata = null; video.onerror = null;
    if (old) { old.ontrack = null; old.onconnectionstatechange = null; old.close(); }
    video.srcObject = null; video.hidden = true;
    onReset();
  }
  function fallback() {
    const current = id;
    close(); onStop();
    if (current !== null) send({ type: 'rtc-fallback', id: current });
  }
  async function receive(message) {
    if (message.type === 'rtc-stop') {
      if (message.id === id) { close(); onStop(); }
      return;
    }
    if (message.type !== 'rtc-offer') return;
    close(); id = message.id; codec = message.codec === 'H265' ? 'H265' : 'H264';
    const token = generation, current = () => generation === token && peer !== null;
    timeout = schedule(() => { if (generation === token) fallback(); }, 8000);
    try {
      if (!supportsCodec(codec)) { fallback(); return; }
      const pc = createPeer(); peer = pc;
      const displayed = () => {
        if (!current() || ready || !video.videoWidth || video.readyState < 2) return;
        ready = true; cancel(timeout); timeout = null;
        lastDecodedAt = lastSample = now(); lastDecoded = 0; sent = 0; lastSentAt = now();
        send({ type: 'rtc-ready', id }); onReady();
        if (current()) statsTimer = schedule(sample, 1000);
      };
      const sample = async () => {
        if (!current()) return;
        try {
          const report = await pc.getStats();
          if (!current()) return;
          report.forEach(stat => {
            if (stat.type !== 'inbound-rtp' || (stat.kind || stat.mediaType) !== 'video') return;
            const decoded = stat.framesDecoded || 0, at = now();
            if (decoded > lastDecoded) lastDecodedAt = at;
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
              presentedFps: video.requestVideoFrameCallback && elapsed ? Math.max(0, presented - previous.presented) * 1000 / elapsed : null,
              decodeMs: averageMs('totalDecodeTime', 'framesDecoded'),
              jitterBufferMs: averageMs('jitterBufferDelay', 'jitterBufferEmittedCount'),
              processingMs: averageMs('totalProcessingDelay', 'framesDecoded'),
              dropped: delta('framesDropped'), lost: delta('packetsLost'), nack: delta('nackCount'),
              bitrateMbps: rate('bytesReceived') === null ? null : rate('bytesReceived') * 8 / 1e6,
              rttMs,
            };
            onStats(metrics.presentedFps ?? metrics.decodedFps, metrics);
            send({ type: 'rtc-feedback', id, packets: Math.max(0, stat.packetsReceived || 0),
              lost: Math.max(0, stat.packetsLost || 0), decoded, nack: Math.max(0, stat.nackCount || 0), rttMs });
            previousStats = { at, stat, presented };
            lastDecoded = decoded; lastSample = at;
            // Static CarPlay screens legitimately stop producing frames.
            if (at - lastDecodedAt > 5000 && at - lastSentAt < 2000 && sent > 0) fallback();
          });
        } catch (_) { if (current()) fallback(); }
        if (current()) statsTimer = schedule(sample, 1000);
      };
      pc.onconnectionstatechange = () => {
        if (current() && ['failed', 'disconnected', 'closed'].includes(pc.connectionState)) fallback();
      };
      pc.ontrack = event => {
        if (!current()) return;
        video.onloadeddata = displayed;
        video.onerror = () => { if (current()) fallback(); };
        if (video.requestVideoFrameCallback && frameCallback === null) {
          let firstPresented = null;
          const observe = (_, metadata) => {
            if (!current()) return;
            if (firstPresented === null) firstPresented = metadata.presentedFrames;
            presented = metadata.presentedFrames - firstPresented;
            frameCallback = video.requestVideoFrameCallback(observe);
          };
          frameCallback = video.requestVideoFrameCallback(observe);
        }
        video.srcObject = event.streams[0] || new MediaStream([event.track]);
        onTrack();
        if (!current()) return;
        video.play().then(displayed).catch(() => { if (current()) fallback(); });
      };
      await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp });
      if (!current()) return;
      const answer = await pc.createAnswer();
      if (!current()) return;
      if (/^m=video 0(?: |\r?$)/m.test(answer.sdp)) { fallback(); return; }
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
    } catch (_) { if (generation === token) fallback(); }
  }
  return { receive, close, fallback, get active() { return ready; }, get codec() { return codec; },
    progress(count) { if (count > sent) lastSentAt = now(); sent = count; },
  };
}

import { createBrowserAudio } from './browser-audio.js';

if (typeof document !== 'undefined') {
  const $ = id => document.getElementById(id);
  const screen = $('screen');
  const video = $('video');
  const canvas = $('video-canvas');
  const display = $('display');
  const touchSurface = $('touch-surface');
  // Read-only diagnostic snapshots; counters never include pairing credentials or video payloads.
  const performanceStats = { source: null, transport: null, receiver: null };
  Object.defineProperty(window, 'wheelplayStats', { configurable: true, get: () => JSON.parse(JSON.stringify(performanceStats)) });
  let rtc = null, rtcRequested = false, rtcFps = null;
  let socket = null, stopped = true, paired = false, retry = null, attempts = 0;
  let width = 1280, height = 720, live = false, objectUrl = null, lastFrame = 0, lastStatus = 0;
  let heartbeat = null, movePending = false, moveGeneration = 0, outbox = null;
  let authGeneration = 0, deviceCredential = null, storageAvailable = false;
  let launchPresentation = null;
  const devicePreference = 'wheelplay.browserDevice';
  let pairingToken = null, qrGeneration = 0, qrTimer = null, qrRequest = null;
  let viewportReportTimer = null, lastViewportSignature = '';
  const canvasOption = $('canvas-video');
  const canvasPreference = 'wheelplay.canvasVideo';
  try { canvasOption.checked = window.localStorage.getItem(canvasPreference) === 'true'; } catch (_) {}
  canvasOption.addEventListener('change', () => {
    try { window.localStorage.setItem(canvasPreference, String(canvasOption.checked)); } catch (_) {}
  });
  const canvasRenderer = createVideoCanvasRenderer(video, canvas, {
    onFrame: (frameWidth, frameHeight) => {
      width = frameWidth; height = frameHeight; screen.hidden = true; setLive(true);
    },
    onError: () => { if (rtc) rtc.fallback(); },
  });
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
  function showPairingReady(name = '浏览器已配对') {
    $('remembered-device').hidden = false;
    $('device-name').textContent = name;
    $('qr-pairing').hidden = true; $('pair-instructions').hidden = true;
    $('code-entry').hidden = true; $('code').required = false; $('code').disabled = true;
    $('connect-label').textContent = '启动显示';
    $('connect').disabled = false;
    $('status').textContent = '已配对 · 等待启动'; $('message').textContent = '';
  }
  function showPairingEntry() {
    $('remembered-device').hidden = true;
    $('qr-pairing').hidden = false; $('pair-instructions').hidden = false;
    $('code-entry').hidden = false; $('code').required = true; $('code').disabled = false;
    $('connect-label').textContent = '配对并启动显示';
    $('connect').disabled = false;
  }
  async function preparePairing(token, generation) {
    pairingToken = token;
    let name = '浏览器已配对';
    let message = '已配对。选择上方选项后，点击「启动显示」。';
    if (storageAvailable) {
      try {
        const device = await pairApi('remember', { token });
        if (generation !== authGeneration) return;
        deviceCredential = device;
        window.localStorage.setItem(devicePreference, JSON.stringify(device));
        name = device.name;
      } catch (_) {
        message = '本次配对成功，但未能记住设备。下次可能需要重新配对；可检查浏览器存储或 App 中的设备数量。';
      }
    } else message = '本次配对成功。浏览器无法保存设备，下次仍需配对。';
    if (generation !== authGeneration) return;
    showPairingReady(name); $('device-status').textContent = message;
  }
  async function initializePairing(linkedCode = null) {
    const generation = ++authGeneration;
    stopQr(); pairingToken = null;
    showPairingEntry(); $('connect').disabled = true;
    if (deviceCredential) {
      $('status').textContent = '正在恢复配对…';
      try {
        const result = await pairApi('resume', { device: deviceCredential });
        if (generation !== authGeneration) return;
        pairingToken = result.token;
        showPairingReady(result.name);
        $('device-status').textContent = '已自动恢复配对。选择上方选项后，点击「启动显示」。';
        return;
      } catch (error) {
        if (generation !== authGeneration) return;
        if (error.status === 401) {
          deviceCredential = null;
          try { window.localStorage.removeItem(devicePreference); } catch (_) {}
          $('message').textContent = '设备记忆已失效或已被移除，请重新配对。';
        } else {
          showPairingReady(deviceCredential.name || '已记住的浏览器');
          $('status').textContent = '等待恢复配对';
          $('device-status').textContent = '暂时无法恢复配对，请检查服务和网络后点击「启动显示」重试。';
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
        $('message').textContent = '访问链接中的配对码已失效，请重新扫码或输入当前配对码。';
      }
    }
    if (generation !== authGeneration) return;
    showPairingEntry(); $('status').textContent = '等待配对'; startQr();
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
    $('qr-status').textContent = '正在生成二维码…';
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
        $('qr-status').textContent = '二维码加载失败，请刷新重试或手动输入配对码。';
      };
      $('pair-qr').src = `/pair/qr?id=${encodeURIComponent(request.id)}&secret=${encodeURIComponent(request.secret)}`;
      $('qr-status').textContent = '请用服务端 App 扫码并允许连接。二维码两分钟内有效。';
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
          $('qr-status').textContent = error.message === 'expired' ? '二维码已过期，请刷新后重新扫描。' : '暂时无法获取配对状态，请刷新二维码或输入配对码。';
        }
      };
      qrTimer = setTimeout(poll, 1000);
    } catch (_) {
      if (generation === qrGeneration) $('qr-status').textContent = '二维码暂不可用，请刷新重试或手动输入配对码。';
    } finally {
      if (generation === qrGeneration) $('refresh-qr').disabled = false;
    }
  }
  $('refresh-qr').addEventListener('click', startQr);
  const pointers = new Map();
  const send = (data, kind) => outbox ? outbox.send(data, kind) : false;
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
    $('https-entry').href = `https://${location.hostname}:8443/`;
    try {
      const response = await fetch('/tls.json', { cache: 'no-store' });
      const info = await response.json();
      $('tls-fingerprint').textContent = info.fingerprint || 'HTTPS 正在准备，请稍后重新展开。';
      $('tls-error').textContent = info.error || '';
    } catch (_) { $('tls-error').textContent = '证书信息暂时无法读取'; }
  });
  function scheduleViewportReport() {
    clearTimeout(viewportReportTimer);
    viewportReportTimer = setTimeout(() => {
      if (!socket || socket.readyState !== WebSocket.OPEN || display.hidden) return;
      const rect = display.getBoundingClientRect(), scale = window.devicePixelRatio || 1;
      const width = Math.round(rect.width * scale / 2) * 2;
      const height = Math.round(rect.height * scale / 2) * 2;
      if (width < 320 || height < 240) return;
      const signature = `${width}x${height}`;
      if (signature === lastViewportSignature) return;
      lastViewportSignature = signature;
      send({ type: 'viewport', width, height });
    }, 180);
  }
  if (typeof ResizeObserver !== 'undefined') new ResizeObserver(scheduleViewportReport).observe(display);
  window.addEventListener('resize', scheduleViewportReport);
  function cancelMove() { moveGeneration++; movePending = false; }
  function contacts() { return [...pointers.values()].map(p => ({ id: p.id, x: p.x, y: p.y, down: true })); }
  function release() { cancelMove(); pointers.clear(); send({ type: 'touch', contacts: [] }); }
  function setLive(value) {
    live = value;
    screen.classList.toggle('stale', !value);
    video.classList.toggle('stale', !value);
    canvas.classList.toggle('stale', !value);
    touchSurface.classList.toggle('stale', !value);
    $('waiting').hidden = value;
    if (!value && pointers.size) release();
  }
  function cleanupImage() {
    screen.onload = null; screen.onerror = null; screen.removeAttribute('src');
    screen.hidden = true;
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = null;
  }
  function cleanupRtc() {
    if (rtc) rtc.close(); rtc = null; rtcRequested = false; rtcFps = null;
    canvasRenderer.stop();
    performanceStats.source = performanceStats.transport = performanceStats.receiver = null;
    screen.hidden = !(screen.complete && screen.naturalWidth > 0); video.hidden = true;
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
    cleanupRtc(); cleanupImage(); setLive(false);
    browserAudio.close(); $('audio-message').hidden = true;
    $('topbar').hidden = false; $('pairing').hidden = false; $('display').hidden = true;
    $('disconnect').hidden = true; $('connect').disabled = false;
    $('status').textContent = '已断开';
    if (refreshQr) initializePairing();
  }
  function connect() {
    clearTimeout(retry);
    $('connect').disabled = true;
    $('status').textContent = paired ? '正在重新连接…' : '正在连接…';
    const auth = pairingToken ? `token=${encodeURIComponent(pairingToken)}` : `code=${encodeURIComponent($('code').value)}`;
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/stream?${auth}`);
    ws.binaryType = 'arraybuffer';
    const useCanvas = canvasOption.checked;
    socket = ws;
    const connectTimer = setTimeout(() => ws.close(), 8000);
    ws.onopen = () => {
      clearTimeout(connectTimer);
      if (socket !== ws) return;
      if (outbox) outbox.close();
      outbox = createControlOutbox(ws, { onFailure: () => {
        if (socket !== ws) return;
        cancelMove(); pointers.clear(); setLive(false); ws.close();
      } });
      paired = true; attempts = 0; lastStatus = Date.now();
      browserAudio.attach();
      cleanupRtc();
      rtc = createRtcReceiver(video, { send,
        onTrack: () => { if (useCanvas) canvasRenderer.prepare(); },
        onReset: () => canvasRenderer.stop(),
        onReady: () => {
          if (socket !== ws) return;
          if (useCanvas) { canvasRenderer.start(); return; }
          screen.hidden = true; video.hidden = false;
          width = video.videoWidth; height = video.videoHeight; setLive(true);
        },
        onStop: () => {
          if (socket !== ws) return;
          screen.hidden = !(screen.complete && screen.naturalWidth > 0); video.hidden = true; rtcFps = null;
          setLive(Boolean(lastFrame && screen.naturalWidth));
        },
        onStats: (fps, metrics) => { rtcFps = fps === null ? null : Math.round(fps); performanceStats.receiver = metrics; },
      });
      $('message').textContent = '';
      $('phone-connect').disabled = false;
      $('phone-connect-message').textContent = '请保持 Android 上的 WheelPlay 在前台；点击连接 iPhone，按提示完成授权。';
      $('pairing').hidden = true; $('display').hidden = false; $('disconnect').hidden = false;
      $('topbar').hidden = $('hide-toolbar').checked || Boolean(launchPresentation?.immersive);
      lastViewportSignature = '';
      scheduleViewportReport();
      $('status').textContent = '已连接 · 等待画面';
      send({ type: 'ping' });
      clearInterval(heartbeat);
      heartbeat = setInterval(() => {
        if (Date.now() - lastStatus > 6000) { ws.close(); return; }
        send({ type: 'ping' });
        if (pointers.size) send({ type: 'touch', contacts: contacts() }, 'move');
      }, 400);
    };
    ws.onmessage = event => {
      if (socket !== ws || ws.readyState !== WebSocket.OPEN) return;
      if (typeof event.data === 'string') {
        const data = JSON.parse(event.data);
        if (browserAudio.control(data)) return;
        if (data.type === 'rtc-offer' || data.type === 'rtc-stop') {
          if (data.type === 'rtc-stop') rtcRequested = false;
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
          lastStatus = Date.now(); width = data.width; height = data.height;
          $('stage').textContent = data.stage || '等待 iPhone 画面…';
          rtc.progress(data.rtcFrames || 0);
          if (data.rtcAvailable && !rtcRequested && typeof RTCPeerConnection !== 'undefined') {
            rtcRequested = true; send({ type: 'rtc-start' });
          }
          // The server resets availability when the iPhone starts a new video configuration.
          if (!data.rtcAvailable) rtcRequested = false;
          setLive(Boolean(data.streaming && (rtc.active || lastFrame && screen.naturalWidth)));
          const transport = rtc.active ? `${rtc.codec === 'H265' ? 'HEVC' : 'H.264'} / WebRTC${useCanvas ? ' / Canvas' : ''}${rtcFps === null ? '' : ` · ${rtcFps} fps`}` : `JPEG${data.fallback ? ' · 已回退' : ''}`;
          $('status').textContent = live ? `${transport} · 触控已连接` : '已连接 · 等待 iPhone 画面';
        }
        if (data.type === 'input-unavailable') $('status').textContent = '等待 iPhone 触控通道就绪';
        return;
      }
      if (browserAudio.receive(event.data)) return;
      if (rtc && rtc.active) { send({ type: 'ack' }); return; }
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      objectUrl = URL.createObjectURL(event.data instanceof ArrayBuffer ? new Blob([event.data], { type: 'image/jpeg' }) : event.data);
      screen.onload = () => {
        if (socket !== ws || ws.readyState !== WebSocket.OPEN) return;
        if (rtc && rtc.active) { send({ type: 'ack' }); return; }
        screen.hidden = false;
        lastFrame = Date.now(); width = screen.naturalWidth; height = screen.naturalHeight;
        if (!rtc || !rtc.active) { setLive(true); $('status').textContent = 'JPEG · 触控已连接'; }
        send({ type: 'ack' });
      };
      screen.onerror = () => { screen.hidden = true; ws.close(); };
      screen.src = objectUrl;
    };
    ws.onclose = () => {
      clearTimeout(connectTimer);
      if (socket !== ws) return;
      if (outbox) { outbox.close(); outbox = null; }
      cancelMove();
      socket = null; pointers.clear(); clearInterval(heartbeat); cleanupRtc(); cleanupImage(); setLive(false);
      browserAudio.close(); $('audio-message').hidden = true;
      if (stopped) return;
      if (!paired || ++attempts > 8) {
        disconnect(); $('status').textContent = '连接未成功';
        $('message').textContent = '请检查服务端是否运行、配对码是否正确。连续输错后请等待 30 秒再试。';
        return;
      }
      $('status').textContent = '连接中断 · 正在重试';
      $('stage').textContent = '检查服务端与车机是否仍在同一局域网。';
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
        $('status').textContent = '配对未成功';
        $('message').textContent = '请检查服务和网络，或重新扫码 / 输入当前配对码。连续输错后请等待 30 秒再试。';
      }
    })();
  });
  $('phone-connect').addEventListener('click', () => {
    if (!paired || !send({ type: 'phone-connect' })) {
      $('phone-connect-message').textContent = '浏览器连接已中断，请重新配对后再试。';
      return;
    }
    $('phone-connect').disabled = true;
    $('phone-connect-message').textContent = '正在请求连接 iPhone…';
  });
  $('disconnect').addEventListener('click', () => disconnect());
  $('fullscreen').addEventListener('click', async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (document.documentElement.requestFullscreen) await document.documentElement.requestFullscreen();
      else $('status').textContent = '此浏览器不支持全屏';
    } catch (_) { $('status').textContent = '请通过浏览器菜单切换全屏'; }
  });
  document.addEventListener('fullscreenchange', () => {
    $('fullscreen').textContent = document.fullscreenElement ? '退出全屏' : '全屏';
    if (!document.fullscreenElement && launchPresentation?.immersive) {
      launchPresentation.immersive = false;
      launchPresentation.ownsFullscreen = false;
      $('topbar').hidden = false;
    }
    scheduleViewportReport();
  });
  // A separate layer prevents Android Chrome's native video gestures cancelling drags.
  for (const surface of [screen, video, canvas, touchSurface]) {
  surface.addEventListener('contextmenu', event => event.preventDefault());
  surface.addEventListener('pointerdown', event => {
    if (!live || pointers.size >= 2 || (event.pointerType === 'mouse' && event.button !== 0)) return;
    const point = mapPoint(event.clientX, event.clientY, surface.getBoundingClientRect(), width, height);
    if (!point) return;
    event.preventDefault();
    const id = [...pointers.values()].some(p => p.id === 0) ? 1 : 0;
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
  window.addEventListener('blur', release);
  document.addEventListener('visibilitychange', () => { if (document.hidden) release(); });
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
