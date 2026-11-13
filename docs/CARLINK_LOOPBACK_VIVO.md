# vivo 本机 CarLink 回环：首次验证

日期：2026-11-12。

## 范围与结论

在一台 vivo V2547A 上同时运行系统智能车载发送端和 WheelPlay CarLink 接收插件，使用默认 P2P 无线流程。未建立连接；接收插件最终提示配对超时。该结果仅说明本机原生发现流程未走通，不能推导为绕过发现后协议回环不可行。

本轮只有一台实体手机，未重新执行双机对照。不修改系统应用、不 root、不注入或修改发现结果，也未测试 USB、热点或强制指定 IP 的连接。

## 环境与操作

- 系统报告 Android 17；vivo 智能车载 / com.vivo.car.networking 为 7.0.0.9。
- 已有 WheelPlay debug 宿主；安装本地 CarLink 0.1.0-debug APK，启用插件并授予定位、附近设备权限。
- Wi-Fi、蓝牙及定位已开启。接收设置为 Wi-Fi Direct（P2P）及兼容视频输出。
- 插件首次打开出现原生库 16 KB 对齐兼容性提示；确认后 SDK 能启动。
- 从 WheelPlay 连接插件，随后打开系统智能车载 → 连接车辆 → 通用连接方式 → 无线连接 → ICCOA。
- vivo 的原生页面说明通过自动弹窗发起连接，而非提供设备搜索列表；本轮未出现本机设备连接弹窗。

## 观测证据

- 17:49:35：接收 SDK 创建 GATT 服务（UnionShare），启动 BLE 广播。
- `onAdvertisingSetStarted status 0` 与 `notifyOpenResult: true` 表明广播启动成功。
- 系统蓝牙转储确认该广播正在运行，且可连接、可扫描；vivo connbase 的进行中扫描包含 ICCOA 服务 UUID `0000fcfb-0000-1000-8000-00805f9b34fb`。扫描聚合计数不能证明发现了本机。
- 观察期间未见接收 SDK 的 GATT 连接/特征交互；P2P 转储显示 `mGroup null`，未建立投影网络。
- 17:51:42：SDK 清理并释放资源；插件状态页面显示“配对超时”。未到视频、输入回传验证阶段。
- SDK 尝试通过反射设置 BLE 广播间隔时被系统拒绝，但随后广播成功，不能将该警告直接当作本轮失败根因。
- `ss -ltn` 被系统权限限制，未获得完整监听端口证据。

## 指定地址入口初查

包信息包含 `com.vivo.debug_config.DebugConfigActivity`。直接从 ADB 打开返回 SecurityException，要求 `com.vivo.car.networking.CAR_PERMISSION`。只读检查该页面及 DebugConfigFragment，发现连接超时、Wi-Fi 行为、视频编码等选项，未发现输入 IP 地址的直接连接选项。尚未完整审计全部发送端入口。

## 后续最有价值的验证

1. 使用第二台实体接收设备做对照，确认这台 vivo 的同版本系统确实能发现并连接当前 SDK。
2. 追踪 vivo ICCOA 发现回调到 GATT / P2P 建链的调用路径，识别可替换的连接参数入口。
3. 如果确有可调用入口，再做独立实验，绕过发现并连接本机端点；分别确认认证、网络绑定及虚拟显示创建。

本轮未改产品代码。临时调试插件保留以便继续验证；会话已超时清理，没有活动投影。原始日志、转储和只读分析文件保存在本地 `.local/carlink-loopback-vivo/`，其中可能含设备或网络信息，不提交仓库。
