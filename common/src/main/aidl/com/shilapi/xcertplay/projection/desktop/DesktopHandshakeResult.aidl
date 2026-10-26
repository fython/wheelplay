package com.shilapi.xcertplay.projection.desktop;
import com.shilapi.xcertplay.projection.desktop.DesktopStatus;
import com.shilapi.xcertplay.projection.desktop.DesktopServiceInfo;

@JavaDerive(equals=true, toString=true)
parcelable DesktopHandshakeResult {
    // A missing status is a malformed response, never an implicit success.
    @nullable DesktopStatus status;
    @nullable DesktopServiceInfo info;
}
