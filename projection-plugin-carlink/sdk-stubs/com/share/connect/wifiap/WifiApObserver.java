package com.share.connect.wifiap;
public interface WifiApObserver extends android.os.IInterface {
 void onOpenSuccess() throws android.os.RemoteException;
 void onOpenFailed() throws android.os.RemoteException;
 void onApCreated(String address, String ssid, String password, String mac, int frequency) throws android.os.RemoteException;
 void onApCreateFailed() throws android.os.RemoteException;
 void onConnected() throws android.os.RemoteException;
 void onDisconnected(boolean userClose) throws android.os.RemoteException;
 void onAcceptFailed() throws android.os.RemoteException;
}
