package moe.feng.wheelplay.projection.desktop;
import moe.feng.wheelplay.projection.desktop.DesktopStatus;
import moe.feng.wheelplay.projection.desktop.DesktopDisplayInfo;

@JavaDerive(equals=true, toString=true)
parcelable DesktopStartResult {
    @nullable DesktopStatus status;
    // Success must identify an owned, non-default display (> 0).
    @nullable DesktopDisplayInfo display;
    long enabledFeatures = 0;
}
