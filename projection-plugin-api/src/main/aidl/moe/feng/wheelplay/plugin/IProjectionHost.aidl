package moe.feng.wheelplay.plugin;

import android.os.Bundle;

oneway interface IProjectionHost {
    void onInfo(String token, in Bundle info);
    void onState(String token, int state, String message);
    /** clean=true only after ALL producers and platform resources are released. */
    void onStopped(String token, boolean clean, String message);
}
