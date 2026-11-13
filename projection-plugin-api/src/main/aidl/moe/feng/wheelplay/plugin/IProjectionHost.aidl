package moe.feng.wheelplay.plugin;

import android.os.Bundle;

oneway interface IProjectionHost {
    void onInfo(String token, in Bundle info);
    void onState(String token, int state, String message);
    /** clean=true only after ALL producers and platform resources are released. */
    void onStopped(String token, boolean clean, String message);
    // Reply only to a negotiated updateDisplaySize request.
    void onDisplaySizeResult(String token, long requestId, boolean restartRequired);
    // Optional interaction v1, only after bind-time and onInfo negotiation.
    void onInteraction(String token, in Bundle interaction);
    // Optional host-UI v1; opens host controls without stopping projection.
    void onHostUiRequested(String token);
    // Media v1; full snapshots and transport receipts, scoped to this session.
    void onMediaState(String token, in Bundle state);
    void onMediaCommandResult(String token, long requestId, boolean sent);
}
