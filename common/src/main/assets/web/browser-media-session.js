// Media Session is optional: missing/partial implementations must not interrupt video or audio.
export function createBrowserMediaSession({
  send,
  session = globalThis.navigator?.mediaSession,
  Metadata = globalThis.MediaMetadata,
  baseUrl = globalThis.location?.href,
  now = () => performance.now(),
} = {}) {
  const actions = ['play', 'pause', 'stop', 'previoustrack', 'nexttrack'];
  const unsupported = ['seekto', 'seekbackward', 'seekforward'];
  let opened = false, state = null, revision = -1, epoch = 0, controls = null, metadataKey = null;
  let request = 0, pending = null;
  const safely = operation => { try { operation(); } catch (_) { /* Optional browser capability. */ } };

  function clear() {
    epoch++; controls = null; metadataKey = null; pending = null;
    if (!session) return;
    safely(() => { session.metadata = null; });
    safely(() => { session.playbackState = 'none'; });
    safely(() => session.setPositionState());
    for (const action of [...actions, ...unsupported]) safely(() => session.setActionHandler(action, null));
  }

  function installControls() {
    const key = `${state.sessionId}/${state.connectionGeneration}`;
    if (controls === key) return;
    controls = key; pending = null;
    const expectedEpoch = ++epoch;
    for (const action of unsupported) safely(() => session.setActionHandler(action, null));
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
      state = data;
      render();
      return true;
    },
  };
}
