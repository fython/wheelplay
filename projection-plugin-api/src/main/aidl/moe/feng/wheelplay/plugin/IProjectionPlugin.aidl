package moe.feng.wheelplay.plugin;

import android.view.Surface;
import moe.feng.wheelplay.plugin.IProjectionHost;

/** All calls are asynchronous. connect must not acquire projection resources. */
oneway interface IProjectionPlugin {
    void connect(String token, int apiVersion, IProjectionHost host);
    void start(String token, in Surface surface);
    void touch(String token, in int[] ids, in double[] xs, in double[] ys, in boolean[] down);
    void setNightMode(String token, boolean night);
    void stop(String token);
}
