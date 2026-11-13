# Sources and dependency boundary

The CarLink integration was written for WheelPlay using its independent APK plugin API.
SDK signatures and behavior were checked against:

- [MiAuto v0.1.3](https://github.com/PiggyBackBayonet/MiAuto/releases/tag/v0.1.3)
- [MiAuto source at 8ee36108ab74a58cbb0ef5c305e813a5f2fd8f41](https://github.com/PiggyBackBayonet/MiAuto/tree/8ee36108ab74a58cbb0ef5c305e813a5f2fd8f41), particularly `IccoaManager.java`, `IcocaBridge.kt`, the manifest and SDK integration notes. MiAuto identifies its source license as AGPL-3.0.

The `sdk-stubs/` files are minimal, independently written compile-only declarations checked
against MiAuto's published signatures and runtime DEX. They contain no protocol implementation;
all callable placeholder bodies throw. They must never be included in an APK.

No MiAuto application code, SDK bytecode, SDK native libraries, or certification asset is
checked into this module. The blue bitmap is original placeholder artwork needed by the
SDK's hard-coded bitmap resource reference. It is not a CarLink logo.

## Binary packaging and distribution

WheelPlay supports building and distributing the independent CarLink plugin APK. Both Debug
and Release artifacts include the pinned runtime DEX, native libraries and `ccd.data` described
in `sdk-lock.json`. These inputs are provisioned during the build rather than committed to the
source tree. CI downloads the pinned reference APK, verifies its hash, and uploads the verified
plugin APK with its dependency manifest (`carlink-build.json`).

Release builds use the same `ANDROID_KEYSTORE_*` / `ANDROID_KEY_*` environment configuration
as the mobile module. Keep this source attribution and the dependency manifest with releases.
This notice records provenance and packaging policy; it does not relicense third-party components.
