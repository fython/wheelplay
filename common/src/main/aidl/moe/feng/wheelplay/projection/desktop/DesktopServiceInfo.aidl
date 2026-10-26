package moe.feng.wheelplay.projection.desktop;
/** Accepted protocol operations and request limits, not a device compatibility guarantee. */
@JavaDerive(equals=true, toString=true)
parcelable DesktopServiceInfo {
    int protocolVersion = 0;
    int uid = -1;
    long supportedFeatures = 0;
    long enabledFeatures = 0;
    int maxTouchContacts = 0;
    int minSize = 0;
    int maxSize = 0;
    long maxPixels = 0;
    int minDpi = 0;
    int maxDpi = 0;
}
