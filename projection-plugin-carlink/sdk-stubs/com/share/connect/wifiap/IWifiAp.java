package com.share.connect.wifiap;
public interface IWifiAp extends android.os.IInterface {
 void open();
 void close();
 void createSoftAp(int channel, String ssid, String password, boolean coexist, boolean prefix);
 void cancelConnect();
 boolean isUserClose();
 void registerWifiApObserver(WifiApObserver observer);
 void unregisterWifiApObserver(WifiApObserver observer);
 abstract class Stub extends android.os.Binder implements IWifiAp {
  public android.os.IBinder asBinder() { return this; }
 }
}
