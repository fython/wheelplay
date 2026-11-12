// Real Chromium + WebSocket + JPEG/WebP images. No phone or CarPlay hardware is simulated.
import assert from 'node:assert/strict';
import http from 'node:http';
import { readFile, mkdtemp, rm, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import WebSocket, { WebSocketServer } from 'ws';
import ffmpeg from 'ffmpeg-static';
import { resourceStrings, renderBrowserTemplate } from './resource-strings.mjs';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const messages = resourceStrings('values-zh-rCN');
const root = new URL('../common/src/main/assets/web/', import.meta.url);
const androidServer = await readFile(new URL('../common/src/main/java/com/shilapi/xcertplay/web/LanWebServer.kt', import.meta.url), 'utf8');
const csp = androidServer.match(/"(default-src 'self';[^"\n]+)"/)[1];
const profile = await mkdtemp(join(tmpdir(), 'wheelplay-media-'));
function image(width, height, codec = 'mjpeg') {
  const result = spawnSync(ffmpeg, ['-v', 'error', '-f', 'lavfi', '-i', `testsrc=size=${width}x${height}:rate=1`,
    '-frames:v', '1', '-f', 'image2pipe', '-vcodec', codec, 'pipe:1']);
  assert.equal(result.status, 0, result.stderr.toString()); return result.stdout;
}
const images = { jpeg: [image(320, 180), image(180, 320)], webp: [image(320, 180, 'libwebp'), image(180, 320, 'libwebp')] };
let viewer, connections = 0, acks = 0, paused = false, sequence = 0, format = 'jpeg', jpegFallbacks = 0, webpProbes = 0;
const pendingImages = new Map();
const touches = [], errors = [], mediaCommands = [], desktopInputs = [];
let media = { type: 'status', mediaProtocol: 3, sessionId: 7, videoEpoch: 1, width: 320, height: 180,
  sourceState: 'ready', inputReady: true, streaming: true, stage: 'Synthetic browser test' };
const server = http.createServer(async (req, res) => {
  res.setHeader('Content-Security-Policy', csp);
  if (req.url.startsWith('/pair/qr?')) {
    res.setHeader('Content-Type', 'image/svg+xml');
    res.end(await readFile(new URL('../test/fixtures/pairing-qr.svg', import.meta.url))); return;
  }
  if (req.url === '/now-playing/artwork?id=test-cover') {
    res.setHeader('Content-Type', 'image/jpeg'); res.setHeader('Cache-Control', 'private, no-store');
    res.end(images.jpeg[0]); return;
  }
  if (req.url.startsWith('/pair/')) {
    if (req.url === '/pair/code') assert.equal(req.headers['x-pair-code'], '123456');
    const result = req.url === '/pair/remember' ? { id: 'd'.repeat(32), secret: 's'.repeat(32), name: 'Test' } :
      req.url === '/pair/request' ? { id: 'qr', secret: 'poll', expiresIn: 120 } :
        req.url === '/pair/status' ? { state: 'pending' } : { token: 'session', name: 'Test' };
    res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(result)); return;
  }
  if (req.url === '/i18n.js') {
    res.setHeader('Content-Type', 'text/javascript'); res.end(`globalThis.wheelplayMessages=${JSON.stringify(messages)};`); return;
  }
  const file = { '/': 'index.html', '/app.js': 'app.js', '/desktop-input.js': 'desktop-input.js', '/text.js': 'text.js', '/browser-audio.js': 'browser-audio.js', '/browser-media-session.js': 'browser-media-session.js', '/browser-location.js': 'browser-location.js',
    '/style.css': 'style.css', '/audio-worklet.js': 'audio-worklet.js' }[req.url];
  if (!file) { res.writeHead(404); res.end(); return; }
  let body = await readFile(new URL(file, root));
  if (file === 'index.html') body = renderBrowserTemplate(body.toString(), messages);
  res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store'); res.end(body);
});
const wss = new WebSocketServer({ noServer: true });
server.on('upgrade', (req, socket, head) => {
  const query = new URL(req.url, 'http://localhost').searchParams;
  assert.equal(query.get('media'), '2'); assert.equal(query.get('images'), '3');
  wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws));
});
function status(extra = {}) { media = { ...media, ...extra }; viewer.send(JSON.stringify(media)); }
function frame(epoch = media.videoEpoch, width = media.width, height = media.height, corrupt = false) {
  const image = corrupt ? Buffer.from([0, 1, 2]) : images[format][width === 320 ? 0 : 1], packet = Buffer.alloc(40 + image.length);
  packet.writeUInt32LE(0x33565057); packet.writeDoubleLE(7, 4); packet.writeDoubleLE(epoch, 12);
  packet.writeDoubleLE(++sequence, 20); packet.writeUInt32LE(width, 28); packet.writeUInt32LE(height, 32);
  packet.writeUInt32LE(format === 'webp' ? 2 : 1, 36);
  pendingImages.set(sequence, { sessionId: 7, videoEpoch: epoch });
  image.copy(packet, 40); viewer.send(packet);
}
wss.on('connection', ws => {
  viewer = ws; connections++; format = 'jpeg'; pendingImages.clear();
  let initial = true;
  ws.on('message', data => {
    const message = JSON.parse(data);
    if (message.type === 'input') desktopInputs.push(message);
    if (message.type === 'ack') {
      assert.deepEqual(pendingImages.get(message.sequence), { sessionId: message.sessionId, videoEpoch: message.videoEpoch });
      pendingImages.delete(message.sequence); acks++;
    }
    if (message.type === 'image-format') {
      format = message.format;
      if (format === 'jpeg') jpegFallbacks++; else webpProbes++;
      if (connections === 1) frame();
    }
    if (message.type === 'touch') touches.push(message);
    if (message.type === 'media-command') {
      mediaCommands.push(message);
      ws.send(JSON.stringify({ type: 'media-command-result', requestId: message.requestId, result: 'sent' }));
    }
    if (message.type === 'ping' && !paused) {
      ws.send(JSON.stringify(media));
      if (initial && connections === 1) frame();
      initial = false;
    }
  });
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = spawn(process.env.BROWSER_BIN || 'chromium', [...(process.env.WHEELPLAY_BROWSER_HEADFUL === '1' ? [] : ['--headless']), '--no-sandbox', '--disable-gpu',
  '--disable-background-networking', '--no-first-run', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
{ stdio: ['ignore', 'ignore', 'ignore'] });
let devtools;
try {
  let port;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (browser.exitCode !== null) throw new Error(`Browser exited: ${browser.exitCode}`);
    try { port = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break; } catch {}
    await pause(50);
  }
  assert.ok(port, 'Chromium DevTools must start');
  const targets = await fetch(`http://127.0.0.1:${port}/json/list`).then(response => response.json());
  devtools = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => devtools.once('open', resolve));
  let id = 0;
  const pending = new Map();
  devtools.on('message', data => {
    const message = JSON.parse(data);
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails);
    if (message.id) {
      const promise = pending.get(message.id); pending.delete(message.id);
      if (message.error) promise.reject(new Error(JSON.stringify(message.error))); else promise.resolve(message.result);
    }
  });
  const command = (method, params = {}) => new Promise((resolve, reject) => {
    pending.set(++id, { resolve, reject }); devtools.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async (expression, { userGesture = false } = {}) => {
    const result = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture });
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const wait = async expression => {
    for (let attempt = 0; attempt < 100; attempt++) {
      if (await evaluate(expression)) return;
      await pause(50);
    }
    const diagnostics = await evaluate(`({status:document.getElementById('status')?.textContent,
      waiting:document.getElementById('waiting')?.hidden,fullscreen:!!document.fullscreenElement,
      fullscreenEnabled:document.fullscreenEnabled,topbarHidden:document.getElementById('topbar')?.hidden,
      fullscreenTrace:window.fullscreenTrace,
      exceptions:${JSON.stringify(errors)}})`);
    throw new Error(`Browser condition timed out: ${expression}\n${JSON.stringify(diagnostics)}`);
  };
  const showing = `!!document.getElementById('waiting') && document.getElementById('waiting').hidden && !document.getElementById('touch-surface').classList.contains('stale')`;
  const touch = type => command('Input.dispatchTouchEvent', {
    type: type === 'pointerdown' ? 'touchStart' : 'touchEnd',
    touchPoints: type === 'pointerdown' ? [{ x: 100, y: 100, id: 1 }] : [],
  });
  await command('Runtime.enable');
  await command('Page.navigate', { url: `http://127.0.0.1:${server.address().port}/` });
  await wait(`!!window.wheelplayStats && !!document.getElementById('connect') && !document.getElementById('connect').disabled`);
  await wait(`!document.getElementById('pair-qr').hidden && document.getElementById('pair-qr').naturalWidth > 0`);
  assert.equal(await evaluate(`document.getElementById('hide-toolbar') === null && document.getElementById('fullscreen-on-start') === null`), true);
  const screenshot = async name => {
    if (!process.env.WHEELPLAY_BROWSER_REPORT_DIR) return;
    await mkdir(process.env.WHEELPLAY_BROWSER_REPORT_DIR, { recursive: true });
    const { data } = await command('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(process.env.WHEELPLAY_BROWSER_REPORT_DIR, name), Buffer.from(data, 'base64'));
  };
  assert.equal(await evaluate(`document.getElementById('connect-form').checkValidity()`), false, 'Incomplete PIN cannot submit');
  await evaluate(`
    const digit=document.getElementById('code-digit-3');
    digit.dispatchEvent(new ClipboardEvent('paste', {clipboardData:(()=>{const data=new DataTransfer();data.setData('text','123456');return data})(),cancelable:true}));`);
  assert.equal(await evaluate(`document.getElementById('code').value`), '123456');
  assert.equal(await evaluate(`document.activeElement.id`), 'code-digit-6');
  assert.equal(await evaluate(`document.getElementById('connect-form').checkValidity()`), true);
  await evaluate(`
    window.fullscreenTrace=[];
    const originalFullscreen=Element.prototype.requestFullscreen;
    Element.prototype.requestFullscreen=function(...args){
      const entry={active:navigator.userActivation.isActive,focused:document.hasFocus()};fullscreenTrace.push(entry);
      const result=originalFullscreen.apply(this,args);
      result.then(()=>{entry.resolved=true},error=>{entry.error=error.message});return result;
    };
    document.addEventListener('fullscreenchange',()=>fullscreenTrace.push({event:!!document.fullscreenElement}));
    document.addEventListener('fullscreenerror',()=>fullscreenTrace.push({event:'error'}));`);
  await command('Page.bringToFront');
  const startButton = await evaluate(`(() => {
    const button=document.getElementById('connect');button.scrollIntoView({block:'center'});
    const rect=button.getBoundingClientRect();return {x:rect.left+rect.width/2,y:rect.top+rect.height/2};
  })()`);
  await command('Input.dispatchMouseEvent', { type: 'mousePressed', ...startButton, button: 'left', clickCount: 1 });
  await command('Input.dispatchMouseEvent', { type: 'mouseReleased', ...startButton, button: 'left', clickCount: 1 });
  await wait(showing);
  await wait(`document.getElementById('topbar').hidden`);
  assert.equal(await evaluate(`document.getElementById('debug-overlay').hidden`), true);
  assert.equal(await evaluate(`fullscreenTrace[0]?.active`), true, 'Default fullscreen is requested in the launch gesture');
  for (let attempt = 0; attempt < 40 && !await evaluate(`!!document.fullscreenElement`); attempt++) await pause(50);
  const nativeFullscreenEntered = await evaluate(`!!document.fullscreenElement`);
  // The OEM signal opens a modal above the stream, including immersive/zoomed layouts.
  await touch('pointerdown');
  viewer.send(JSON.stringify({ type: 'open-settings' }));
  await wait(`document.getElementById('settings-dialog').open`);
  await touch('pointerup');
  const centeredSettings = await evaluate(`(() => {
    const dialog=document.getElementById('settings-dialog'), rect=dialog.getBoundingClientRect();
    return { x:Math.abs(rect.left+rect.width/2-innerWidth/2), y:Math.abs(rect.top+rect.height/2-innerHeight/2),
      modal:dialog.matches(':modal'), focus:dialog.contains(document.activeElement) };
  })()`);
  assert.ok(centeredSettings.x < 2 && centeredSettings.y < 2, 'Settings must be centered in the viewport');
  assert.equal(centeredSettings.modal, true);
  assert.equal(centeredSettings.focus, true);
  viewer.send(JSON.stringify({ type: 'open-settings' })); status();
  await pause(100);
  const settingsInputCount = touches.length + desktopInputs.length;
  await touch('pointerdown'); await touch('pointerup'); await pause(100);
  assert.equal(touches.length + desktopInputs.length, settingsInputCount, 'Dialog backdrop cannot forward input');
  assert.equal(await evaluate(`document.getElementById('waiting').hidden`), true, 'Modal preserves video');
  await evaluate(`document.getElementById('settings-debug').click()`);
  assert.equal(await evaluate(`document.getElementById('debug-overlay').hidden`), false);
  if (nativeFullscreenEntered) {
    await evaluate(`document.getElementById('settings-fullscreen').click()`, { userGesture: true });
    await wait(`!document.fullscreenElement && document.getElementById('topbar').hidden`);
    assert.equal(await evaluate(`document.getElementById('settings-dialog').open`), true, 'OEM settings remain available after leaving fullscreen');
    await evaluate(`document.getElementById('settings-fullscreen').click()`, { userGesture: true });
    await wait(`!!document.fullscreenElement && document.getElementById('topbar').hidden`);
    await evaluate(`document.getElementById('settings-fullscreen').click()`, { userGesture: true });
    await wait(`!document.fullscreenElement && document.getElementById('topbar').hidden`);
  } else {
    assert.equal(await evaluate(`document.getElementById('settings-fullscreen').textContent`), messages.web_full_screen);
  }
  await command('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
  await command('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
  await wait(`!document.getElementById('settings-dialog').open && (${showing})`);
  assert.equal(await evaluate(`document.getElementById('topbar').hidden`), true, 'The display never restores the homepage toolbar');
  const debugGeometry = await evaluate(`(() => {
    const panel=document.getElementById('debug-overlay'), rect=panel.getBoundingClientRect(), display=document.getElementById('display').getBoundingClientRect();
    const style=getComputedStyle(panel);
    return {top:rect.top-display.top,right:display.right-rect.right,pointerEvents:style.pointerEvents,
      foreground:style.color,background:style.backgroundColor,opacity:style.opacity,
      target:document.elementFromPoint(rect.left+40,rect.top+60)?.id,x:rect.left+40,y:rect.top+60};
  })()`);
  assert.ok(debugGeometry.top >= 8 && debugGeometry.top <= 24 && debugGeometry.right >= 8 && debugGeometry.right <= 24);
  assert.equal(debugGeometry.pointerEvents, 'none');
  assert.equal(debugGeometry.target, 'touch-surface');
  assert.equal(debugGeometry.opacity, '1', 'Text remains opaque');
  const foreground = debugGeometry.foreground.match(/[\d.]+/g).map(Number);
  const background = debugGeometry.background.match(/[\d.]+/g).map(Number);
  assert.ok(background[3] >= .8 && background[3] < 1, 'Background is translucent with enough contrast');
  const luminance = rgb => rgb.slice(0, 3).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
    .reduce((sum, v, index) => sum + v * [.2126, .7152, .0722][index], 0);
  const brightestBackdrop = background.slice(0, 3).map(v => v * background[3] + 255 * (1 - background[3]));
  assert.ok((luminance(foreground) + .05) / (luminance(brightestBackdrop) + .05) >= 4.5, 'Debug text stays readable over white video');
  const debugTouches = touches.length;
  await command('Input.dispatchTouchEvent', {type:'touchStart',touchPoints:[{x:debugGeometry.x,y:debugGeometry.y,id:1}]});
  await command('Input.dispatchTouchEvent', {type:'touchEnd',touchPoints:[]});
  for (let attempt = 0; touches.length < debugTouches + 2 && attempt < 100; attempt++) await pause(10);
  assert.equal(touches.length, debugTouches + 2, 'Debug overlay passes touches to the video');
  await wait(`document.getElementById('debug-decode').textContent.includes('ms')`);
  await screenshot('display-debug.png');
  viewer.send(JSON.stringify({type:'open-settings'}));
  await wait(`document.getElementById('settings-dialog').open`);
  await evaluate(`document.getElementById('settings-debug').click();document.getElementById('settings-close').click()`);
  assert.equal(await evaluate(`document.getElementById('debug-overlay').hidden`), true);
  await evaluate(`
    window.mediaHandlers = {};
    const originalActionHandler = MediaSession.prototype.setActionHandler;
    MediaSession.prototype.setActionHandler = function(action, handler) {
      window.mediaHandlers[action] = handler;
      return originalActionHandler.call(this, action, handler);
    };`);
  const nowPlaying = { type: 'now-playing', sessionId: 'phone-one', revision: 1, connectionGeneration: 7, trackRevision: 1,
    connected: true, available: true, title: 'Browser test song', artist: 'Test artist', album: 'Test album',
    status: 'playing', durationMs: 100000, positionMs: 12000, speed: 1, canSeek: false,
    artworkUrl: '/now-playing/artwork?id=test-cover' };
  viewer.send(JSON.stringify(nowPlaying));
  await wait(`navigator.mediaSession.metadata?.title === 'Browser test song' && navigator.mediaSession.playbackState === 'playing'`);
  assert.equal(await evaluate(`new Promise(resolve => { const image = new Image(); image.onload = () => resolve(image.naturalWidth); image.onerror = () => resolve(0); image.src = navigator.mediaSession.metadata.artwork[0].src; })`), 320);
  await evaluate(`window.retiredPause = mediaHandlers.pause; mediaHandlers.pause()`);
  for (let attempt = 0; !mediaCommands.length && attempt < 100; attempt++) await pause(10);
  assert.equal(mediaCommands[0]?.action, 'pause'); assert.equal(mediaCommands[0]?.connectionGeneration, 7);
  assert.equal(await evaluate('navigator.mediaSession.playbackState'), 'playing', 'Sending pause is not a phone acknowledgement');
  viewer.send(JSON.stringify({ ...nowPlaying, revision: 2, status: 'paused', speed: 0 }));
  await wait(`navigator.mediaSession.playbackState === 'paused'`);
  assert.equal(await evaluate('mediaHandlers.seekto === null && mediaHandlers.seekforward === null'), true);
  viewer.send(JSON.stringify({ ...nowPlaying, revision: 3, status: 'paused', speed: 0, canSeek: true }));
  await wait(`typeof mediaHandlers.seekto === 'function' && typeof mediaHandlers.seekforward === 'function'`);
  await evaluate(`window.retiredSeek = mediaHandlers.seekto; mediaHandlers.seekto({seekTime: 10, fastSeek: true}); mediaHandlers.seekto({seekTime: 42.123})`);
  for (let attempt = 0; mediaCommands.length < 2 && attempt < 100; attempt++) await pause(10);
  assert.equal(mediaCommands.length, 2);
  assert.equal(mediaCommands[1].action, 'seekto');
  assert.equal(mediaCommands[1].positionMs, 42123);
  assert.equal(mediaCommands[1].trackRevision, 1);
  assert.equal(await evaluate('navigator.mediaSession.playbackState'), 'paused');
  viewer.send(JSON.stringify({ ...nowPlaying, revision: 4, trackRevision: 2, title: 'New track', artworkUrl: null, durationMs: null, positionMs: null }));
  await wait(`navigator.mediaSession.metadata?.title === 'New track' && navigator.mediaSession.metadata.artwork.length === 0`);
  await evaluate(`retiredSeek({seekTime: 60})`);
  await pause(200); assert.equal(mediaCommands.length, 2, 'Retired seek cannot control the new track');
  await wait(`document.getElementById('status').textContent.includes('WebP')`);
  assert.equal(webpProbes, 1, 'WebP is advertised only after real decoding succeeds');
  await evaluate(`const option=document.getElementById('ignore-browser-zoom');option.checked=true;option.dispatchEvent(new Event('change'))`);
  await command('Emulation.setPageScaleFactor', { pageScaleFactor: 1.5 });
  await wait(`document.getElementById('display').classList.contains('ignore-browser-zoom') &&
    Math.abs(parseFloat(document.getElementById('display').style.transform.slice(6))-1/visualViewport.scale)<0.01`);
  const beforeZoomTouch = touches.length; await touch('pointerdown'); await touch('pointerup');
  for (let attempt = 0; touches.length < beforeZoomTouch + 2 && attempt < 100; attempt++) await pause(10);
  assert.equal(touches.length, beforeZoomTouch + 2, 'Zoom-compensated WebP must retain touch');
  assert.equal(await evaluate(showing), true);
  await command('Emulation.setPageScaleFactor', { pageScaleFactor: 1 });
  await evaluate(`document.getElementById('ignore-browser-zoom').checked=false;document.getElementById('ignore-browser-zoom').dispatchEvent(new Event('change'))`);
  // Source capabilities replace the old global two-finger limit.
  status({ maxTouchContacts: 5 });
  await pause(100);
  await command('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints:
    Array.from({ length: 5 }, (_, id) => ({ x: 70 + id * 30, y: 100, id })) });
  for (let attempt = 0; touches.at(-1)?.contacts.length !== 5 && attempt < 100; attempt++) await pause(10);
  assert.equal(touches.at(-1).contacts.length, 5, 'Negotiated five-touch source accepts five fingers');
  assert.equal(new Set(touches.at(-1).contacts.map(p => p.id)).size, 5);
  await command('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  status({ maxTouchContacts: 0, inputReady: false });
  await pause(100);
  const readOnlyCount = touches.length;
  await touch('pointerdown'); await touch('pointerup'); await pause(50);
  assert.equal(touches.length, readOnlyCount, 'Read-only source must not accept touches');
  assert.equal(await evaluate(`document.getElementById('waiting').hidden`), true, 'Read-only video stays visible');
  status({ maxTouchContacts: 2, inputReady: true });
  await wait(showing);
  status({ pointerInput: true, keyboardInput: true, maxTouchContacts: 1 });
  await pause(100);
  const touchBeforeMouse = touches.length;
  await command('Input.dispatchMouseEvent', { type: 'mousePressed', x: 100, y: 100, button: 'right', buttons: 2, clickCount: 1 });
  await command('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 120, y: 110, button: 'right', buttons: 2 });
  await command('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 120, y: 110, button: 'right', buttons: 0, clickCount: 1 });
  await command('Input.dispatchMouseEvent', { type: 'mouseWheel', x: 120, y: 110, deltaX: 0, deltaY: -100 });
  await command('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', windowsVirtualKeyCode: 65 });
  await command('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', windowsVirtualKeyCode: 65 });
  await pause(100);
  assert.equal(touches.length, touchBeforeMouse, 'Desktop mouse must not also synthesize touch');
  assert.ok(desktopInputs.some(e => e.kind === 'pointer' && e.buttons === 2));
  assert.ok(desktopInputs.some(e => e.kind === 'pointer' && e.buttons === 0));
  assert.ok(desktopInputs.some(e => e.kind === 'scroll' && e.dy === -1));
  assert.deepEqual(desktopInputs.filter(e => e.kind === 'key').map(e => [e.key,e.down]), [['a',true],['a',false]]);
  assert.ok(desktopInputs.every(e => e.sessionId === media.sessionId && e.videoEpoch === media.videoEpoch));
  await command('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Shift', code: 'ShiftLeft', windowsVirtualKeyCode: 16 });
  await evaluate("document.getElementById('touch-surface').blur()");
  await pause(100);
  assert.equal(desktopInputs.at(-1).kind, 'release', 'Blur releases remote modifiers');
  status({ pointerInput: false, keyboardInput: false, maxTouchContacts: 2 });
  await pause(100);
  await evaluate(`window.overlayFlashes=0;new MutationObserver(()=>{if(!document.getElementById('waiting').hidden)overlayFlashes++}).observe(document.getElementById('waiting'),{attributes:true,attributeFilter:['hidden']})`);
  for (let index = 0; index < 10; index++) {
    const previous = acks; frame(); status();
    for (let attempt = 0; acks === previous && attempt < 100; attempt++) await pause(10);
    assert.ok(acks > previous, 'JPEG must commit and ACK');
  }
  assert.equal(await evaluate('overlayFlashes'), 0, 'Decoding must not flash the overlay');
  const beforePipeline = acks; frame(); frame();
  for (let attempt = 0; acks < beforePipeline + 2 && attempt < 100; attempt++) await pause(10);
  assert.equal(acks, beforePipeline + 2, 'Both images must ACK their own identities');
  assert.equal(await evaluate(showing), true);
  frame(media.videoEpoch, media.width, media.height, true);
  await wait(`document.getElementById('status').textContent.includes('JPEG')`);
  assert.equal(jpegFallbacks, 1, 'A WebP decode failure must request JPEG and recover');
  assert.equal(await evaluate('overlayFlashes'), 0, 'A failed WebP must preserve the last displayed picture');
  const releasesBeforeGap = desktopInputs.filter(input => input.kind === 'release').length;
  await touch('pointerdown'); paused = true;
  status({ sourceState: 'recovering', streaming: false, inputReady: false });
  await wait(`!document.getElementById('media-message').hidden`);
  for (let attempt = 0; desktopInputs.filter(input => input.kind === 'release').length === releasesBeforeGap && attempt < 100; attempt++) await pause(10);
  assert.equal(desktopInputs.at(-1).kind, 'release', 'Versioned input releases the touch lease on a gap');
  await touch('pointerup');
  const count = touches.length; await touch('pointerdown'); await pause(50);
  assert.equal(touches.length, count, 'Gap must block new touch');
  await touch('pointerup');
  await pause(2100);
  assert.equal(await evaluate(`document.getElementById('waiting').hidden`), false, 'Deadline expires without status');
  assert.equal(viewer.readyState, WebSocket.OPEN);
  paused = false; status({ sourceState: 'ready', streaming: true, inputReady: true }); await wait(showing);
  status({ sourceState: 'recovering', inputReady: false }); await pause(100);
  status({ sourceState: 'ready', inputReady: true }); await wait(showing); await pause(2100);
  assert.equal(await evaluate(showing), true, 'Cancelled deadline must not hide recovered picture');
  status({ pointerInput: true, keyboardInput: true, maxTouchContacts: 1 });
  await pause(100);
  const beforeDesktopDrag = desktopInputs.length;
  await command('Input.dispatchMouseEvent', { type: 'mousePressed', x: 100, y: 100, button: 'left', buttons: 1, clickCount: 1 });
  for (let attempt = 0; !desktopInputs.slice(beforeDesktopDrag).some(input => input.kind === 'pointer' && input.buttons === 1) && attempt < 100; attempt++) await pause(10);
  assert.ok(desktopInputs.slice(beforeDesktopDrag).some(input => input.kind === 'pointer' && input.buttons === 1), 'Mouse press arrives even if a keepalive follows it');
  status({ videoEpoch: 2 });
  await wait(`document.getElementById('touch-surface').classList.contains('stale')`);
  frame(1); await pause(100);
  assert.equal(await evaluate(showing), false, 'Old epoch cannot restore touch');
  frame(2); await wait(showing);
  const inputsAfterResize = desktopInputs.length;
  await command('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 130, y: 110, button: 'left', buttons: 1 });
  await command('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 130, y: 110, button: 'left', buttons: 0, clickCount: 1 });
  await pause(100);
  assert.equal(desktopInputs.length, inputsAfterResize, 'Old physical drag must not enter the new epoch');
  await command('Input.dispatchMouseEvent', { type: 'mousePressed', x: 100, y: 100, button: 'left', buttons: 1, clickCount: 1 });
  await command('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 100, y: 100, button: 'left', buttons: 0, clickCount: 1 });
  await pause(100);
  assert.deepEqual(desktopInputs.slice(inputsAfterResize).map(e => [e.buttons,e.videoEpoch]), [[1,2],[0,2]],
    'A fresh press must work after the cancelled drag is released');
  status({ videoEpoch: 3, width: 180, height: 320 }); frame(3);
  await wait(`(${showing}) && [...document.querySelectorAll('#screen,#screen-next,#screen-third')].find(image=>!image.hidden)?.naturalWidth === 180`);
  assert.equal(await evaluate(`[...document.querySelectorAll('#screen,#screen-next,#screen-third')].find(image=>!image.hidden).naturalWidth`), 180);
  viewer.close();
  for (let attempt = 0; connections < 2 && attempt < 100; attempt++) await pause(50);
  assert.equal(connections, 2);
  assert.equal(await evaluate(`navigator.mediaSession.metadata === null && navigator.mediaSession.playbackState === 'none'`), true);
  await evaluate('retiredPause()'); await pause(50);
  assert.equal(mediaCommands.length, 2, 'Retired system controls cannot reach the replacement socket');
  assert.equal(await evaluate(`document.getElementById('waiting').hidden`), false, 'Reconnect must wait for its own frame');
  frame(); await wait(showing);
  // Capture the home layout after returning from the display session.
  await evaluate(`document.getElementById('settings-disconnect').click();localStorage.clear()`);
  await command('Page.navigate', { url: `http://127.0.0.1:${server.address().port}/` });
  await wait(`!document.getElementById('pair-qr')?.hidden && document.getElementById('pair-qr')?.naturalWidth > 0`);
  const homeGeometry = `(() => {
    const left=document.querySelector('.pairing-panel').getBoundingClientRect(), right=document.querySelector('.setup-panel').getBoundingClientRect();
    return {left:left.x,right:right.x,leftBottom:left.bottom,rightTop:right.top,overflow:document.querySelector('main').scrollWidth>innerWidth};
  })()`;
  await command('Emulation.setDeviceMetricsOverride', { width: 1360, height: 1000, deviceScaleFactor: 1, mobile: false });
  let home = await evaluate(homeGeometry);
  assert.ok(home.right > home.left + 300, 'Wide home separates pairing and settings into two columns');
  assert.equal(home.overflow, false);
  await screenshot('home-wide.png');
  await command('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: false });
  home = await evaluate(homeGeometry);
  assert.ok(home.rightTop >= home.leftBottom, 'Narrow home places settings below pairing');
  assert.equal(home.overflow, false);
  await screenshot('home-mobile.png');
  const keypadGeometry = `(() => {
    const panel=document.getElementById('pairing-keypad'), anchor=document.getElementById('pin-inputs'), visual=visualViewport;
    return {hidden:panel.hidden,panel:panel.getBoundingClientRect().toJSON(),anchor:anchor.getBoundingClientRect().toJSON(),content:document.getElementById('main-content').getBoundingClientRect().toJSON(),
      viewport:{left:visual?.offsetLeft||0,top:visual?.offsetTop||0,width:visual?.width||innerWidth,height:visual?.height||innerHeight},
      placement:panel.dataset.placement,readonly:[...anchor.querySelectorAll('input')].every(input=>input.readOnly&&input.inputMode==='none')};
  })()`;
  const tapKey = async id => {
    const point=await evaluate(`(()=>{const r=document.getElementById('${id}').getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+r.height/2}})()`);
    await command('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...point,id:1}]});
    await command('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    await pause(30);
  };
  for (const [width,height,name] of [[1360,1000,'wide'],[390,844,'mobile'],[800,360,'landscape'],[320,320,'short']]) {
    await evaluate(`document.getElementById('keypad-done').click()`);
    await command('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});
    await evaluate(`{document.getElementById('main-content').scrollTop=0;const field=document.getElementById('code-digit-1');field.focus({preventScroll:true});field.click()}`);
    await wait(`!document.getElementById('pairing-keypad').hidden`);
    await pause(80);
    const geometry=await evaluate(keypadGeometry), {panel,anchor,viewport}=geometry;
    assert.equal(geometry.hidden,false,`${name}: keypad remains open after bringing the field into view`);
    assert.equal(geometry.readonly,true,'PIN fields cannot summon the system keyboard');
    assert.ok(panel.left>=viewport.left+10&&panel.top>=viewport.top+10&&panel.right<=viewport.left+viewport.width-10&&panel.bottom<=viewport.top+viewport.height-10,`${name}: keypad fits the visual viewport`);
    assert.ok(panel.right<=anchor.left||panel.left>=anchor.right||panel.bottom<=anchor.top||panel.top>=anchor.bottom,`${name}: keypad does not cover the six-digit input`);
    assert.ok(anchor.top>=Math.max(viewport.top,geometry.content.top)+10&&anchor.bottom<=Math.min(viewport.top+viewport.height,geometry.content.bottom)-10,`${name}: input remains visible below the header`);
    await tapKey('keypad-clear');
    for (const digit of '012') await tapKey('keypad-'+digit);
    assert.equal(await evaluate(`document.getElementById('code').value`),'012');
    assert.equal(await evaluate(`document.getElementById('keypad-code').textContent`),'0 1 2 · · ·');
    await screenshot('keypad-'+name+'.png');
    if (name === 'wide') {
      await command('Browser.grantPermissions',{origin:`http://127.0.0.1:${server.address().port}`,permissions:['clipboardReadWrite','clipboardSanitizedWrite']});
      await evaluate(`navigator.clipboard.writeText('098765')`,{userGesture:true});
      await evaluate(`document.getElementById('code-digit-1').focus({preventScroll:true})`);
      await command('Input.dispatchKeyEvent',{type:'keyDown',key:'v',code:'KeyV',modifiers:2,windowsVirtualKeyCode:86});
      await command('Input.dispatchKeyEvent',{type:'keyUp',key:'v',code:'KeyV',modifiers:2,windowsVirtualKeyCode:86});
      await wait(`document.getElementById('code').value==='098765'`);
      await tapKey('keypad-clear');
      for (const digit of '012') await tapKey('keypad-'+digit);
    }
    for (const digit of '345') await tapKey('keypad-'+digit);
    assert.equal(await evaluate(`document.getElementById('code').value`),'012345');
    assert.equal(await evaluate(`document.getElementById('connect-form').checkValidity()`),true);
    await tapKey('keypad-delete');
    assert.equal(await evaluate(`document.getElementById('code').value`),'01234');
    await tapKey('keypad-done');
    assert.equal(await evaluate(`document.getElementById('pairing-keypad').hidden`),true);
  }
  await command('Emulation.setPageScaleFactor',{pageScaleFactor:1.5});
  await evaluate(`document.getElementById('code-digit-1').click()`);
  await pause(100);
  const zoomKeypad=await evaluate(keypadGeometry);
  assert.equal(zoomKeypad.hidden,false);
  assert.ok(zoomKeypad.panel.left>=zoomKeypad.viewport.left&&zoomKeypad.panel.right<=zoomKeypad.viewport.left+zoomKeypad.viewport.width);
  assert.ok(zoomKeypad.panel.top>=zoomKeypad.viewport.top&&zoomKeypad.panel.bottom<=zoomKeypad.viewport.top+zoomKeypad.viewport.height);
  await evaluate(`document.getElementById('setup-title').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));document.getElementById('setup-title').click()`);
  assert.equal(await evaluate(`document.getElementById('pairing-keypad').hidden`),true,'Outside tap dismisses the keypad');
  await command('Emulation.setPageScaleFactor',{pageScaleFactor:1});
  await command('Emulation.clearDeviceMetricsOverride');
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({ passed: true, connections, imageAcks: acks, webpProbes, jpegFallbacks,
    nativeFullscreenEntered,
    blockedChecks: nativeFullscreenEntered ? [] : ['Native fullscreen did not enter in this browser environment; OEM fullscreen transitions were not exercised.'],
    checks: ['responsive home columns and stacking', 'six-digit PIN validation and paste', 'floating keypad on wide, narrow, short and zoomed viewports',
      'trusted keypad touches, deletion, leading zeroes and outside dismissal', 'default fullscreen request and hidden toolbar',
      ...(nativeFullscreenEntered ? ['OEM native fullscreen toggle'] : []),
      'OEM debug toggle, top-right position, contrast over white video and touch passthrough',
      'OEM settings centered modal, focus, input isolation and Escape', 'five-touch source', 'read-only source', 'WebP capability probe', 'WebP touch with zoom compensation', 'JPEG fallback after corrupt WebP', 'two-image pipeline with exact ACKs',
      'decode without overlay flashes', 'gap releases touch', 'deadline without status', 'cancelled deadline',
      'same-size rebuild', 'cancelled mouse drag cannot cross video epochs', 'old frame rejection', 'rotation', 'real socket reconnect', 'native browser Media Session metadata and cover',
      'media action roundtrip', 'desktop right drag, scroll and versioned keyboard', 'desktop blur releases modifiers', 'phone-authoritative pause', 'capability-gated seek and final drag position', 'media cleanup and retired callback isolation'] }, null, 2));
} finally {
  devtools?.close(); browser.kill('SIGTERM');
  for (const client of wss.clients) client.terminate();
  wss.close(); await new Promise(resolve => server.close(resolve));
  await new Promise(resolve => browser.exitCode !== null ? resolve() : browser.once('exit', resolve));
  await rm(profile, { recursive: true, force: true, maxRetries: 3 });
}
