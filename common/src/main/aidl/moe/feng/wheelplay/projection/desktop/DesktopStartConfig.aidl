package moe.feng.wheelplay.projection.desktop;

@JavaDerive(equals=true, toString=true)
parcelable DesktopStartConfig {
    String component;
    int width = 0;
    int height = 0;
    int dpi = 0;
    long requestedFeatures = 0;
    long requiredFeatures = 0;
}
