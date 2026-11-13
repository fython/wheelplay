package com.ucar.vehiclesdk;
public class UCarAdapter {
 public static UCarAdapter getInstance() { throw new UnsupportedOperationException(); }
 public boolean init(android.content.Context context, UCarConfig config, ICarInitCallback cb) { throw new UnsupportedOperationException(); }
 public void registerCarConnectListener(ICarConnectListener l) { throw new UnsupportedOperationException(); }
 public void unregisterCarConnectListener(ICarConnectListener l) { throw new UnsupportedOperationException(); }
 public void enableUsbDeviceDetection(boolean enabled) { throw new UnsupportedOperationException(); }
 public boolean startAdvertise() { throw new UnsupportedOperationException(); }
 public boolean stopAdvertise() { throw new UnsupportedOperationException(); }
 public boolean startCast(android.view.Surface surface, int width, int height, IAppSurfaceStateListener l) { throw new UnsupportedOperationException(); }
 public boolean pauseCast() { throw new UnsupportedOperationException(); }
 public boolean disconnect() { throw new UnsupportedOperationException(); }
 public void registerAudioRecorderListener(ICarAudioRecorderListener listener) { throw new UnsupportedOperationException(); }
 public void unregisterAudioRecorderListener(ICarAudioRecorderListener listener) { throw new UnsupportedOperationException(); }
 public boolean sendMicRecordData(int size, short[] data, long timestamp) { throw new UnsupportedOperationException(); }
 public void registerPhoneDataListener(IPhoneDataListener listener) { throw new UnsupportedOperationException(); }
 public void unregisterPhoneDataListener(IPhoneDataListener listener) { throw new UnsupportedOperationException(); }
 public boolean sendKeyEvent(UCarCommon.KeyEventActionType action, UCarCommon.KeyCodeType code, int metaState) { throw new UnsupportedOperationException(); }
 public void deInit() { throw new UnsupportedOperationException(); }
 public boolean sendTouchEvent(int action, int count, int[] ids, int[] xs, int[] ys) { throw new UnsupportedOperationException(); }
}
