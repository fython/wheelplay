package moe.feng.wheelplay.projection.desktop;
import moe.feng.wheelplay.projection.desktop.DesktopStatus;
import moe.feng.wheelplay.projection.desktop.DesktopSessionState;

@JavaDerive(equals=true, toString=true)
parcelable DesktopStateResult {
    @nullable DesktopStatus status;
    @nullable DesktopSessionState state;
}
