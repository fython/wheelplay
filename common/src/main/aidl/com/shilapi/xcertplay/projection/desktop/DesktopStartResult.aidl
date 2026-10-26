package com.shilapi.xcertplay.projection.desktop;
import com.shilapi.xcertplay.projection.desktop.DesktopStatus;

@JavaDerive(equals=true, toString=true)
parcelable DesktopStartResult {
    @nullable DesktopStatus status;
    // Success must identify an owned, non-default display (> 0).
    int displayId = 0;
}
