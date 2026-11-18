# CarLife 品牌发现与 Mixing 探针

> 历史调试记录：独立探针和插件模块已移除，当前代码、构建命令与架构见 [CarLife 内置实现](CARLIFE_WIFI.md)。下文旧模块路径及安装命令仅用于还原当时的验证环境。

## 本轮范围

品牌候选配置位于 `carlife-receiver/.../CarLifeBrands.java`，Android 组件发现和入口解析位于 `projection-plugin-carlife/.../CarLifeEnvironment.kt`。优先依据可见、启用的系统宿主或引擎包选择环境；多个候选时才用 manufacturer 排序，无法确定则进入通用手动模式。入口检查 enabled/exported/permission，缺少已知入口时退回宿主启动页或系统设置。包存在不代表协议兼容。

保留 vivo TCP 路径；小米、OPPO、三星、荣耀仅添加候选组件和通用准备引导，均未实机验证。荣耀没有已确认的 mixing 端点。`tcpVerified` 仅表示该品牌已有本仓库实机记录，不是所有系统版本的保证。TCP 不按包名阻止连接。后续已加入仅用于小米环境的 RSA/AES 协商，见 [小米实验适配](CARLIFE_XIAOMI.md)。尺寸手动重连策略保持保守，没有因发现品牌而开启自动尺寸重连。

## 独立实验

入口：`moe.feng.wheelplay.carlife.probe/.MixingProbeActivity`。使用探针自己的 applicationId，不替换原厂 CarLife APK。停止 TCP 投影后才运行。点击“绑定”依次检查组件、Binder 描述和回调注册；只有接口检查通过后才允许“请求画面”。预留 1280×720 Surface、PixelCopy 像素统计、触控转发；回调校验宿主 UID。离开页面或点击停止时释放连接。停止后重新启动 Activity 才能重试。

探针保留 `files/mixing.log`（每次启动覆盖），仅记录阶段、异常和像素统计，不保存音频或个人媒体信息。Binder 描述检查直接读取 INTERFACE_TRANSACTION 返回，以免系统异常 Parcel 被 getInterfaceDescriptor 误读为空描述。

## 2026-11-16 vivo 结果

- 设备：现有 vivo 测试机，Android 17 / PD2547B_A_17.1.13.4.W10。
- 系统宿主：`com.vivo.car.networking` 7.0.0.9（7009）。
- 服务：`com.vivo.vivo_car_service.CarLifeMixingService`，exported=true、enabled=true、无 Manifest permission。
- 调用方：`moe.feng.wheelplay.carlife.probe`，UID 10323。
- bindService 返回 true；INTERFACE_TRANSACTION 返回 `UnsupportedOperationException: Not yet implemented`。
- 本机反编译源码中 CarLifeMixingService.onTransact 会先检查调用 UID 对应包；`base.util.g.l()` 在调用方校验失败时抛出同样异常。由此判断失败发生于调用方身份校验，不是 TCP 或 Surface 编码阶段。
- 没有注册回调、没有请求 Surface，画面、触控、音频、尺寸重建均未得到验证。未集成 mixing 后端。
- 点击停止后记录 UNBOUND，dumpsys 不再显示该服务和探针绑定关系。

本机证据保存在忽略目录 `.local/carlife-vivo/mixing-own-package.log`。不能把成功绑定解释为兼容，也不能把这个结果推广到其他品牌或版本。下一步应继续各品牌 TCP/加密协商验证；mixing 需要厂商允许的调用身份或公开接口。

## 来源与许可证

候选 mixing 端点、AIDL 和 Parcelable 模型参考/改编自 [CarVeX](https://github.com/iamr0s/carvex-android/tree/aa7b75000dcb1e09ec92e27cd24dd8f9ddb24ba6)，作者 iamr0s，提交 aa7b75000dcb1e09ec92e27cd24dd8f9ddb24ba6，GPL-3.0。AIDL 位于探针 `src/main/aidl`，模型位于 `src/main/java/com/baidu/carlife/mixing/Models.kt`。原许可证随 APK 资产 `carvex-LICENSE.txt` 提供；WheelPlay 同为 GPL-3.0。探针 Activity 为本仓库实验代码。

## 验证

`ANDROID_HOME=~/Library/Android/sdk ./gradlew :carlife-probe:assembleDebug :carlife-receiver:test :projection-plugin-carlife:testDebugUnitTest :projection-plugin-carlife:assembleDebug`

品牌选择测试覆盖未知品牌、实际组件优先、大小写、多候选歧义、荣耀无 mixing 端点。实机结论以以上限制为准。
