package com.ucar.vehiclesdk;
import java.util.List;
public interface IPhoneDataListener {
 UCarCommon.AudioAttributes getAudioAttributesByType(UCarCommon.AudioType type);
 void onAppListStateChanged(String deviceId, List<Integer> apps, UCarCommon.AppListState state);
 void onAppStateChanged(String deviceId, int appId, boolean show, int displayMode, int rotation, UCarCommon.AppState state);
 void onAudioFocusChanged(UCarCommon.AudioType type, int change);
 void onCallInfoReceived(String deviceId, UCarCommon.CallInfo info);
 void onMusicInfoReceived(String deviceId, UCarCommon.MusicInfo info);
 void onNavigationInfoReceived(String deviceId, UCarCommon.NavigationInfo info);
 void onPOIReceived(String deviceId, UCarCommon.POIAddress info);
 void onPhoneStateInfoReceived(String deviceId, UCarCommon.PhoneStateInfo info);
}
