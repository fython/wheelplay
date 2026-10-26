package com.shilapi.xcertplay.projection.desktop;
import android.view.Surface;
import android.os.IBinder;

/** Version 2: validated display size/density, immutable layout revision 1 per session. No arbitrary commands. */
interface IDesktopService {
    int hello(int version) = 0;
    int start(String token, IBinder owner, in Surface surface, String component, int width, int height, int dpi) = 1;
    void touch(String token, int layout, in int[] ids, in float[] xs, in float[] ys, in boolean[] down) = 2;
    void back(String token, int layout) = 3;
    boolean healthy(String token, int layout) = 4;
    void stop(String token) = 5;
    void destroy() = 16777114;
}
