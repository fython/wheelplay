package moe.feng.wheelplay.plugin;

import android.view.Surface;
import android.os.ParcelFileDescriptor;
import moe.feng.wheelplay.plugin.IProjectionHost;

/** All calls are asynchronous. connect must not acquire projection resources. */
oneway interface IProjectionPlugin {
    void connect(String token, int apiVersion, IProjectionHost host);
    void start(String token, in Surface surface);
    void touch(String token, in int[] ids, in double[] xs, in double[] ys, in boolean[] down);
    void setNightMode(String token, boolean night);
    void stop(String token);
    // Optional extension: call only when PluginVideoInfo.nativeCancelInput is true.
    void cancelInput(String token);
    // Encoded-video extension v1. Only after explicit bind-time and onInfo negotiation.
    void startEncoded(String token, in ParcelFileDescriptor output);
    // Optional display-size extension v1; requires bind-time and onInfo negotiation.
    void updateDisplaySize(String token, long requestId, int width, int height);
}
