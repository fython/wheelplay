// Media Session is optional: missing/partial implementations must not interrupt video or audio.
export function createBrowserMediaSession({
  send,
  session = globalThis.navigator?.mediaSession,
  Metadata = globalThis.MediaMetadata,
  baseUrl = globalThis.location?.href,
  now = () => performance.now(),
  schedule = setTimeout, cancel = clearTimeout,
} = {}) {
  const actions = ['play', 'pause', 'stop', 'previoustrack', 'nexttrack'];
  const seekActions = ['seekto', 'seekbackward', 'seekforward'];
  let opened = false, state = null, revision = -1, epoch = 0, controls = null, metadataKey = null;
  let request = 0, pending = null, queuedSeek = null, seekTimer = null, stateAt = 0;
  const safely = operation => { try { operation(); } catch (_) { /* Optional browser capability. */ } };

  function clear() {
    epoch++; controls = null; metadataKey = null; pending = null;
    cancel(seekTimer); seekTimer = null; queuedSeek = null;
    if (!session) return;
    safely(() => { session.metadata = null; });
    safely(() => { session.playbackState = 'none'; });
    safely(() => session.setPositionState());
    for (const action of [...actions, ...seekActions]) safely(() => session.setActionHandler(action, null));
  }

  const seekable = () => state?.canSeek === true && Number.isFinite(state.durationMs) && state.durationMs > 0 &&
    Number.isFinite(state.positionMs);

  function flushSeek() {
    seekTimer = null;
    if (queuedSeek === null || !opened || !state?.available || !seekable()) return;
    if (pending && now() - pending.at < 3000) {
      seekTimer = schedule(flushSeek, 150); return;
    }
    const positionMs = queuedSeek; queuedSeek = null;
    const id = ++request;
    pending = { id, at: now(), positionMs };
    const accepted = send({ type: 'media-command', requestId: id, sessionId: state.sessionId,
      connectionGeneration: state.connectionGeneration, trackRevision: state.trackRevision,
      action: 'seekto', positionMs });
    if (!accepted) pending = null;
    // The phone's subsequent position report is authoritative, including when it rejects a seek.
  }

  function installControls() {
    const key = `${state.sessionId}/${state.connectionGeneration}/${state.trackRevision}/${seekable()}`;
    if (controls === key) return;
    controls = key; pending = null;
    cancel(seekTimer); seekTimer = null; queuedSeek = null;
    const expectedEpoch = ++epoch;
    for (const action of seekActions) safely(() => session.setActionHandler(action, seekable() ? (details = {}) => {
      if (!opened || !state?.available || epoch !== expectedEpoch || !seekable()) return;
      let target;
      if (action === 'seekto') {
        if (!Number.isFinite(details.seekTime)) return;
        target = details.seekTime * 1000;
      } else {
        const offset = details.seekOffset === undefined ? 10 : details.seekOffset;
        if (!Number.isFinite(offset) || offset <= 0) return;
        const elapsed = state.status === 'playing' ? Math.max(0, now() - stateAt) *
          (Number.isFinite(state.speed) && state.speed > 0 ? state.speed : 1) : 0;
        const position = queuedSeek ?? pending?.positionMs ?? Math.min(state.durationMs, state.positionMs + elapsed);
        target = position + (action === 'seekforward' ? 1 : -1) * offset * 1000;
      }
      if (!Number.isFinite(target)) return;
      queuedSeek = Math.round(Math.max(0, Math.min(state.durationMs, 0xffffffff, target)));
      cancel(seekTimer);
      // fastSeek is only a browser hint. Coalesce both intermediate and final absolute requests.
      seekTimer = schedule(flushSeek, 150);
    } : null));
    for (const action of actions) safely(() => session.setActionHandler(action, () => {
      if (!opened || !state?.available || epoch !== expectedEpoch || pending && now() - pending.at < 3000) return;
      const id = ++request;
      pending = { id, at: now() };
      const accepted = send({ type: 'media-command', requestId: id, sessionId: state.sessionId,
        connectionGeneration: state.connectionGeneration, action });
      if (!accepted) pending = null;
      // SENT acknowledges transport only. Playback state always comes from the iPhone.
    }));
  }

  function render() {
    if (!session) return;
    if (!state.connected || !state.available) { clear(); return; }
    let artwork = [];
    if (state.artworkUrl) safely(() => {
      const url = new URL(state.artworkUrl, baseUrl);
      if (url.origin === new URL(baseUrl).origin && url.pathname === '/now-playing/artwork')
        artwork = [{ src: url.href, type: 'image/jpeg' }];
    });
    const metadata = { title: state.title || state.appName || '', artist: state.artist || '', album: state.album || '', artwork };
    const key = JSON.stringify([state.sessionId, state.connectionGeneration, state.trackRevision, metadata]);
    if (metadataKey !== key) safely(() => {
      session.metadata = new Metadata(metadata);
      metadataKey = key;
    });
    const playback = state.status === 'playing' ? 'playing' : state.status === 'paused' ? 'paused' : 'none';
    safely(() => { session.playbackState = playback; });
    safely(() => {
      if (playback === 'none' || !Number.isFinite(state.durationMs) || state.durationMs <= 0 || !Number.isFinite(state.positionMs)) {
        session.setPositionState(); return;
      }
      // Android's elapsedRealtime is not the browser clock. The server sends an extrapolated position.
      session.setPositionState({ duration: state.durationMs / 1000,
        position: Math.max(0, Math.min(state.durationMs, state.positionMs)) / 1000,
        playbackRate: Number.isFinite(state.speed) && state.speed > 0 ? state.speed : 1 });
    });
    installControls();
  }

  return {
    open() { clear(); opened = true; state = null; revision = -1; },
    close() { opened = false; state = null; revision = -1; clear(); },
    receive(data) {
      if (data.type === 'media-command-result') {
        if (opened && pending?.id === data.requestId) pending = null;
        return true;
      }
      if (data.type !== 'now-playing') return false;
      if (!opened || typeof data.sessionId !== 'string' || !Number.isSafeInteger(data.revision) || data.revision <= revision ||
          !Number.isSafeInteger(data.connectionGeneration) || !Number.isSafeInteger(data.trackRevision)) return true;
      revision = data.revision;
      state = data; stateAt = now();
      render();
      return true;
    },
  };
}
