// Runs inside Chromium via CDP. Synthetic color sources verify rendering, not
// phone/WebRTC throughput. Software ANGLE makes it reproducible without a GPU.
export async function verifyCanvasWebgl() {
  const { createCanvasFramePainter, createVideoCanvasRenderer, createTrackCanvasRenderer } = await import('/app.js');
  const check = (condition, message) => { if (!condition) throw new Error(message); };
  const option = document.getElementById('canvas-webgl');
  check(!option.checked && option.disabled, 'WebGL must default off and be disabled for native Video');
  document.getElementById('renderer-video-canvas').click();
  check(!option.disabled, 'Canvas mode must enable the experiment option');
  option.click();
  check(option.checked && localStorage.getItem('wheelplay.canvasWebgl') === 'true', 'WebGL choice must persist');
  const tick = () => new Promise(resolve => requestAnimationFrame(resolve));
  const within = async (promise, stage) => {
    let timer;
    try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('WebGL check timed out: ' + stage)), 5000); })]); }
    finally { clearTimeout(timer); }
  };
  const source = document.createElement('canvas'), output = document.getElementById('video-canvas-webgl');
  const defaultCanvas = document.getElementById('video-canvas');
  const context = source.getContext('2d');
  function paint(width, height) {
    source.width = width; source.height = height;
    context.fillStyle = '#ff0000'; context.fillRect(0, 0, width / 2, height / 2);
    context.fillStyle = '#00ff00'; context.fillRect(width / 2, 0, width / 2, height / 2);
    context.fillStyle = '#0000ff'; context.fillRect(0, height / 2, width / 2, height / 2);
    context.fillStyle = '#ffffff'; context.fillRect(width / 2, height / 2, width / 2, height / 2);
  }
  function colors(canvas) {
    const copy = document.createElement('canvas'); copy.width = canvas.width; copy.height = canvas.height;
    const ctx = copy.getContext('2d'); ctx.drawImage(canvas, 0, 0);
    return [[.25, .25], [.75, .25], [.25, .75], [.75, .75]].map(([x, y]) =>
      [...ctx.getImageData(Math.floor(canvas.width * x), Math.floor(canvas.height * y), 1, 1).data]);
  }
  function verifyColors(canvas) {
    const expected = [[255, 0, 0, 255], [0, 255, 0, 255], [0, 0, 255, 255], [255, 255, 255, 255]];
    const actual = colors(canvas);
    check(actual.every((pixel, i) => pixel.every((v, channel) => Math.abs(v - expected[i][channel]) <= 8)),
      'Incorrect color, orientation or retained frame: ' + JSON.stringify(actual));
  }
  let painter, videoRenderer, trackRenderer, stream, video;
  try {
    paint(64, 32); defaultCanvas.width = 64; defaultCanvas.height = 32;
    const initial = createCanvasFramePainter(defaultCanvas);
    check(initial.backend === '2d', 'Default must use Canvas 2D');
    initial.draw(source, 64, 32); verifyColors(defaultCanvas); initial.dispose();
    painter = createCanvasFramePainter(output, { webgl: true }); check(painter.backend === 'webgl', 'WebGL is required for this check');
    for (const [width, height] of [[64, 32], [32, 64]]) {
      paint(width, height); output.width = width; output.height = height;
      painter.draw(source, width, height);
      await tick(); await tick(); verifyColors(output); // Recovery copies in a later task.
      const frame = new VideoFrame(source, { timestamp: 0 });
      try { painter.draw(frame, width, height); verifyColors(output); } finally { frame.close(); }
    }
    painter.dispose();
    video = document.createElement('video'); video.muted = true; video.playsInline = true; document.body.append(video);
    stream = source.captureStream(0); video.srcObject = stream;
    const playing = video.play(); paint(source.width, source.height); stream.getVideoTracks()[0].requestFrame();
    await within(playing, 'video playback');
    const videoErrors = [];
    videoRenderer = createVideoCanvasRenderer(video, output, { webgl: true, onError: error => videoErrors.push(error.message) });
    videoRenderer.start(); check(videoRenderer.backend === 'webgl', 'Video renderer must use WebGL');
    await tick(); await tick(); verifyColors(output); check(!output.hidden, 'Video must produce a visible frame');
    videoRenderer.stop(); check(videoErrors.length === 0, JSON.stringify(videoErrors));
    // The same visible canvas can change modes after stop. VideoFrame uploads
    // use actual browser VideoFrames; this reader is a bounded test source.
    const frame = new VideoFrame(source, { timestamp: 1 });
    let delivered = false, canceled = false, release;
    let resolveReady;
    const ready = new Promise(resolve => { resolveReady = resolve; });
    const trackErrors = [];
    trackRenderer = createTrackCanvasRenderer(output, {
      webgl: true,
      onReady: resolveReady, onError: reason => trackErrors.push(reason),
      createProcessor: () => ({ readable: { getReader: () => ({
        read() {
          if (!delivered) { delivered = true; return Promise.resolve({ value: frame, done: false }); }
          return new Promise(resolve => { release = resolve; });
        },
        cancel() { canceled = true; release?.({ done: true }); }, releaseLock() {},
      }) } }),
    });
    trackRenderer.start({}); await within(ready, 'track readiness'); await tick(); await tick(); verifyColors(output);
    check(trackRenderer.snapshot().renderBackend === 'webgl', 'Track renderer must use WebGL');
    check(frame.displayWidth === 0, 'Consumed VideoFrame must be closed');
    trackRenderer.stop(); check(canceled && trackErrors.length === 0, 'Track must stop cleanly');
    // A context loss follows the same stop/fallback signal as drawing errors,
    // and restoration permits a new painter on the original canvas.
    const lossErrors = [];
    painter = createCanvasFramePainter(output, { webgl: true, onError: error => { lossErrors.push(error.message); painter.dispose(); } });
    const gl = output.getContext('webgl'), extension = gl.getExtension('WEBGL_lose_context');
    check(extension, 'Context loss extension required');
    const lost = new Promise(resolve => output.addEventListener('webglcontextlost', resolve, { once: true }));
    extension.loseContext(); await within(lost, 'context loss'); check(lossErrors.length === 1, 'Loss must report once');
    // Restoration is legal after the cancelable loss event finishes dispatching.
    await tick();
    const restored = new Promise(resolve => output.addEventListener('webglcontextrestored', resolve, { once: true }));
    extension.restoreContext(); await within(restored, 'context restoration');
    painter = createCanvasFramePainter(output, { webgl: true }); output.width = source.width; output.height = source.height;
    painter.draw(source, source.width, source.height); verifyColors(output);
    // Force acquisition failure on a fresh canvas to exercise the browser's
    // real 2D fallback, rather than accepting a skipped WebGL check.
    const fallback = document.createElement('canvas'), getContext = fallback.getContext.bind(fallback);
    fallback.getContext = (type, options) => type === 'webgl' ? null : getContext(type, options);
    const cpu = createCanvasFramePainter(fallback, { webgl: true }); fallback.width = source.width; fallback.height = source.height;
    cpu.draw(source, source.width, source.height); verifyColors(fallback); cpu.dispose();
    const disabled = createCanvasFramePainter(defaultCanvas);
    defaultCanvas.width = source.width; defaultCanvas.height = source.height;
    disabled.draw(source, source.width, source.height); verifyColors(defaultCanvas); disabled.dispose();
    return { backend: 'webgl', checks: ['quadrant colors and orientation', 'landscape and portrait', 'retained recovery frame',
      'HTMLVideoElement upload', 'VideoFrame upload and close', 'Canvas mode switching', 'context loss and restoration', 'Canvas 2D fallback',
      'default 2D, opt-in WebGL and return to 2D on page surfaces', 'experiment UI defaults and persistence'] };
  } finally {
    painter?.dispose(); videoRenderer?.stop(); trackRenderer?.stop();
    stream?.getTracks().forEach(track => track.stop());
    if (video) { video.srcObject = null; video.remove(); }
  }
}
