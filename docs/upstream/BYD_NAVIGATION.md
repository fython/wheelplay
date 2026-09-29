# BYD navigation displays

Phone navigation arrows, next-turn distance and street names can appear on supported BYD displays. Ordinary operation requires no ADB, root, laptop or helper process. The map app must provide structured navigation metadata; compatibility is not guaranteed for every map app or version.

## Validated windshield path

Live guidance and street names were physically confirmed in both DiAuto and DiPlay on DiLink5.1 / Android13, firmware `BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys`. The standalone output is restricted to that firmware and the verified stock receiver version10601004/signing certificate. Other firmware is not implicitly enabled by this result. Existing cluster/SOME-IP outputs remain available on supported factory services; the DiPlay contributor independently reported DiLink5.0 cluster/HUD operation.

The app sends navigation-only broadcasts to the stock ClusterDebug receiver as its normal Android UID. Vendor output runs outside phone control callbacks. Street text uses the installed HAL's UTF-16LE chunk protocol, capped at48 UTF-16 units without splitting a surrogate pair. Output logs exclude street text.

Normal route end, disconnect, disabling navigation output and stale guidance trigger cleanup. Force-stop/process kill may leave the last instruction visible until the app opens again; a recovery journal handles that next launch. There is no guaranteed process-independent expiry. Run only one projection app at a time.

Enable BYD navigation in settings. In DiAuto it is opt-in under Navigation; in DiPlay it is enabled by default when available. Debug-only receivers/demos require Android's DUMP permission and are absent from release manifests. Development starter and vendor-access experiments are not part of the production navigation path.
