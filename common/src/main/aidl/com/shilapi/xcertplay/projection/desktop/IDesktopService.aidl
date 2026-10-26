package com.shilapi.xcertplay.projection.desktop;
import android.view.Surface;
import android.os.IBinder;

/** Version 1: fixed 1280x720/160 dpi, layout revision 1. No arbitrary commands. */
interface IDesktopService {
    int hello(int version) = 0;
    int start(String token, IBinder owner, in Surface surface, String component) = 1;
    void touch(String token, int layout, in int[] ids, in float[] xs, in float[] ys, in boolean[] down) = 2;
    void back(String token, int layout) = 3;
    boolean healthy(String token, int layout) = 4;
    void stop(String token) = 5;
    void destroy() = 16777114;
}
