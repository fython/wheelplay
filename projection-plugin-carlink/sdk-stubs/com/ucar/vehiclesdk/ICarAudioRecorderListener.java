package com.ucar.vehiclesdk;
public interface ICarAudioRecorderListener {
 void onStartRecorder(UCarCommon.AudioFormat format, boolean call);
 void onStopRecorder();
}
