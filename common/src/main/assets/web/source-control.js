// Session readiness and browser presentation are separate. Never infer either from SDK text.
export function sourcePanel(control, presentation, mediaFailed = false) {
  if (!control || control.version !== 1) return { title: 'web_source_waiting', hint: 'web_source_refresh', action: '', retry: false };
  const name = control.activeName || control.selectedName || '';
  const phase = control.phase;
  const active = !!control.activeId;
  const action = control.action || '';
  if (control.cleanup === 'unconfirmed') return { name, title: 'web_source_cleanup_failed', hint: 'web_source_android_help', action: '', retry: false };
  if (phase === 'stopping') return { name, title: control.reconfiguring ? 'web_source_resizing' : 'web_source_stopping',
    hint: 'web_source_stopping_hint', action: control.reconfiguring ? action : '', label: 'web_source_stop', retry: false };
  if (active) {
    const waiting = phase === 'connecting' || phase === 'negotiating';
    return { name, title: waiting ? 'web_source_connecting' : mediaFailed ? 'web_source_media_failed' : 'web_source_receiving',
      hint: waiting ? 'web_source_pair_hint' : 'web_source_media_hint', action,
      label: waiting ? 'web_source_cancel' : 'web_source_stop', retry: !waiting && !presentation.visible };
  }
  return { name, title: control.unavailable ? 'web_source_unavailable' : 'web_source_selected',
    hint: control.needsForeground ? 'web_source_foreground' : control.unavailable ? 'web_source_android_help' : 'web_source_connect_hint',
    action, label: action === 'configure' ? 'web_source_configure' : 'web_source_connect', retry: false };
}

// Only an explicit server-side manual resize request can prompt a reconnect.
export function resizeNotice(control) {
  const size = control?.resize;
  if (control?.version !== 1 || !control.activeId || control.phase !== 'running' ||
      control.cleanup === 'unconfirmed' || control.reconfiguring ||
      !Number.isInteger(size?.width) || !Number.isInteger(size?.height) ||
      size.width < 1 || size.height < 1) return null;
  return { key: `${control.generation}:${control.activeId}:${size.width}x${size.height}`,
    width: size.width, height: size.height, name: control.activeName || '',
    canReconnect: control.resizeAction === 'reconnect' };
}
