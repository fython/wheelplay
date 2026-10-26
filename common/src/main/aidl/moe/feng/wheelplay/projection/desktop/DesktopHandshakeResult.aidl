package moe.feng.wheelplay.projection.desktop;
import moe.feng.wheelplay.projection.desktop.DesktopStatus;
import moe.feng.wheelplay.projection.desktop.DesktopServiceInfo;

@JavaDerive(equals=true, toString=true)
parcelable DesktopHandshakeResult {
    // A missing status is a malformed response, never an implicit success.
    @nullable DesktopStatus status;
    @nullable DesktopServiceInfo info;
}
