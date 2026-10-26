package moe.feng.wheelplay.projection.desktop;

@JavaDerive(equals=true, toString=true)
parcelable DesktopDisplayInfo {
    int displayId = -1;
    int width = 0;
    int height = 0;
    int dpi = 0;
    int rotation = 0;
    int layoutRevision = 0;
}
