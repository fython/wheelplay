package com.ucar.vehiclesdk;
public interface ICarConnectListener {
 void onConnectStateChanged(String deviceId, int state, int parameter);
 void onConnectingProgress(String deviceId, int progress);
 void onPinCode(String pin, String carName);
 void onReconfigureWifiInfo(com.share.connect.WifiOwnerConfig config);
 void onRethinkBluetoothConnect(boolean connect);
 UCarCommon.WorkMode onSelectWorkMode(String deviceId, UCarCommon.WorkMode requested);
}
