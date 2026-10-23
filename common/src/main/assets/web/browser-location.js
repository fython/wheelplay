import { t } from './text.js';

/** Opt-in for this page only. Permission probes never send their position. */
export function createBrowserLocation({
  send, onState = () => {},
  browser = () => navigator,
  secure = () => window.isSecureContext && location.protocol === 'https:',
  now = () => Date.now(),
} = {}) {
  let enabled = false, attached = false, generation = 0, watch = null, permission = null;
  const options = { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 };
  function report(key) { onState(enabled, key ? t(key) : '', key); }
  function stop() {
    generation++;
    if (watch !== null) browser().geolocation.clearWatch(watch);
    watch = null;
    if (permission) permission.onchange = null;
    permission = null;
  }
  function fail(key) {
    stop(); enabled = false;
    if (attached) send({ type: 'location-stop' });
    report(key);
  }
  function errorKey(error) {
    return error?.code === 1 ? 'web_location_denied' :
      error?.code === 3 ? 'web_location_timeout' : 'web_location_unavailable';
  }
  function startWatch(token) {
    if (!attached || !enabled || token !== generation) return;
    // Sending can synchronously close an overloaded socket and cancel this generation.
    if (send({ type: 'location-start' }) === false || !attached || token !== generation) return;
    try {
      watch = browser().geolocation.watchPosition(position => {
        if (token !== generation || !enabled || !attached) return;
        const c = position.coords, age = now() - position.timestamp;
        if (!Number.isFinite(c.latitude) || Math.abs(c.latitude) > 90 ||
            !Number.isFinite(c.longitude) || Math.abs(c.longitude) > 180 ||
            !Number.isFinite(c.accuracy) || c.accuracy < 0 ||
            !Number.isFinite(age) || age < 0 || age > 30000) return;
        const optional = (value, valid = () => true) => Number.isFinite(value) && valid(value) ? value : null;
        send({ type: 'location', latitude: c.latitude, longitude: c.longitude,
          accuracy: c.accuracy, altitude: optional(c.altitude),
          heading: optional(c.heading, value => value >= 0 && value < 360),
          speed: optional(c.speed, value => value >= 0), timestamp: position.timestamp, ageMillis: age });
        report('web_location_active');
      }, error => {
        if (token !== generation) return;
        if (error.code === 1) fail('web_location_denied');
        else {
          // A transient loss of GPS must not keep forwarding the previous fix.
          send({ type: 'location-clear' });
          report(errorKey(error));
        }
      }, options);
    } catch (_) { fail('web_location_unavailable'); }
  }
  async function setEnabled(value) {
    stop(); enabled = false;
    if (attached) send({ type: 'location-stop' });
    if (!value) { report(); return; }
    if (!secure()) { report('web_location_https'); return; }
    if (!browser().geolocation) { report('web_location_unsupported'); return; }
    const token = generation;
    onState(true, t('web_location_checking'), 'web_location_checking');
    try {
      let status = null;
      try { status = await browser().permissions?.query({ name: 'geolocation' }); } catch (_) {}
      if (token !== generation) return;
      if (status?.state === 'denied') { fail('web_location_denied'); return; }
      permission = status;
      if (permission) permission.onchange = () => {
        if (token === generation && permission?.state !== 'granted') fail('web_location_denied');
      };
      if (status?.state !== 'granted') {
        await new Promise((resolve, reject) => browser().geolocation.getCurrentPosition(resolve, reject, options));
      }
      if (token !== generation) return;
      enabled = true; report('web_location_ready');
      startWatch(token);
    } catch (error) {
      if (token === generation) fail(errorKey(error));
    }
  }
  return {
    setEnabled,
    attach() { attached = true; if (enabled) void setEnabled(true); },
    close() { attached = false; stop(); report(enabled ? 'web_location_ready' : null); },
  };
}
