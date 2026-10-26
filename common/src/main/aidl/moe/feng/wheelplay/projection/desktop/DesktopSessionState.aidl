package moe.feng.wheelplay.projection.desktop;
import moe.feng.wheelplay.projection.desktop.DesktopDisplayInfo;
import moe.feng.wheelplay.projection.desktop.DesktopStatus;

@JavaDerive(equals=true, toString=true)
parcelable DesktopSessionState {
    const int IDLE = 0;
    const int RUNNING = 1;
    const int STOPPED = 2;
    const int FAILED = 3;
    int phase = 0;
    long enabledFeatures = 0;
    int inputEpoch = 0;
    @nullable DesktopDisplayInfo display;
    @nullable DesktopStatus failure;
}
