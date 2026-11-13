package com.ucar.vehiclesdk.audio;
import com.ucar.vehiclesdk.UCarCommon;
public class UCarAudioManager {
 public interface IUCarAudioListener { void onAudioFocusChanged(UCarCommon.AudioType type, int change); }
 public enum PlayerState { START_PLAYER, STOP_PLAYER, PAUSE_PLAYER, RESUME_PLAYER }
}
