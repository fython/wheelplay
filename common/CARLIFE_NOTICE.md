# CarLife protocol references

The built-in WheelPlay CarLife receiver lives in `common`. The framing, protobuf
field numbers and handshake sequence were implemented with reference to:

- [CarPToCarL](https://github.com/jjuugk/CarPToCarL/tree/3cbc8e85544bcffcc2aa45f1f3c5e1c70f9ca715),
  commit `3cbc8e85544bcffcc2aa45f1f3c5e1c70f9ca715`: `CarLifeHuClient.kt` and
  `CarLifeProtocol.kt`. MIT, copyright (c) 2026 lizhihua.
- [mcarplay](https://github.com/wangxso/mcarplay/tree/8e25e0f69ab7a3bec14649542adea6eb22f74f9a),
  commit `8e25e0f69ab7a3bec14649542adea6eb22f74f9a`: CarLife channel, launcher and
  session architecture. MIT, copyright (c) 2026 lizhihua.

License texts are included in `src/main/assets/CarPToCarL-LICENSE.txt` and
`src/main/assets/mcarplay-LICENSE.txt`, and packaged in the APK.

No vendor APK, decompiled vendor implementation, signing material or
authentication keys are distributed. The vivo-specific field/behavior checks
and their limitations are recorded in `docs/CARLIFE_LOOPBACK_VIVO.md` in the
repository root. Current integration and protocol verification are recorded in
`docs/CARLIFE_WIFI.md` and `docs/CARLIFE_XIAOMI.md`. Support for a protocol version
does not establish compatibility with every vendor or system build.
