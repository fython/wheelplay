package com.ucar.vehiclesdk;
public class UCarCommon {
 public enum KeyCodeType { KEY_CODE_MEDIA_PLAY, KEY_CODE_MEDIA_PAUSE, KEY_CODE_MEDIA_NEXT, KEY_CODE_MEDIA_PREVIOUS }
 public enum KeyEventActionType { KEY_EVENT_ACTION_DOWN, KEY_EVENT_ACTION_UP, KEY_EVENT_ACTION_PRESS }
 public enum AppListState {}
 public enum AppState {}
 public static class CallInfo {}
 public static class NavigationInfo {}
 public static class POIAddress {}
 public static class PhoneStateInfo {}
 public static class MusicInfo {
  public String getTitle() { throw new UnsupportedOperationException(); }
  public String getArtistName() { throw new UnsupportedOperationException(); }
  public String getAlbumName() { throw new UnsupportedOperationException(); }
  public long getTotalTimesMs() { throw new UnsupportedOperationException(); }
  public int getPlayingCurrentTimeMs() { throw new UnsupportedOperationException(); }
  public boolean isPlaying() { throw new UnsupportedOperationException(); }
  public boolean isDestroyed() { throw new UnsupportedOperationException(); }
  public int getAppId() { throw new UnsupportedOperationException(); }
 }

 public enum AudioType {
  STREAM_UNDEFINED, STREAM_IP_CALL, STREAM_MODEM_CALL, STREAM_AI_ASSISTANT, STREAM_RING,
  STREAM_NOTIFICATION, STREAM_TTS, STREAM_SYSTEM, STREAM_CAST_MUSIC;
  public int getValue() { throw new UnsupportedOperationException(); }
 }
 public static class AudioFormat {
  public String getMimeType() { throw new UnsupportedOperationException(); }
  public int getSampleRate() { throw new UnsupportedOperationException(); }
  public int getChannelConfig() { throw new UnsupportedOperationException(); }
  public int getEncodingFormat() { throw new UnsupportedOperationException(); }
 }
 public static class AudioAttributes {}
 public static class WorkMode {
  public static class Builder {
   public Builder setSupportNormalCast(boolean value) { throw new UnsupportedOperationException(); }
   public WorkMode build() { throw new UnsupportedOperationException(); }
  }
 }
}
