# Tesla HTTP 兼容模式（实验）

此模式尝试让车机通过 Android 上的本地虚拟 IPv4 地址访问 WheelPlay。无需 Root、HTTPS 证书、证书签发服务或云端视频代理；不会修改系统热点的 IP 或 DHCP 网段。它不保证所有 Tesla 车机版本都允许 HTTP。

## 使用

1. 在运行 WheelPlay 的 Android 设备上开启系统热点，让车机连接这个热点。车机连接其他路由器时，通常没有到虚拟地址的路由。
2. 在 WheelPlay 设置中打开「Tesla HTTP 兼容（实验）」→「Tesla HTTP 模式」，完成系统本地 VPN 授权。先断开 iPhone，再切换模式或修改虚拟 IP。
3. 默认虚拟 IP 是 `100.96.0.1`，可修改为 `100.64.0.0–100.127.255.255` 内的其他地址。避免与移动网络、其他 VPN 或现有局域网地址冲突。
4. 等待「虚拟地址已创建」，在车机浏览器输入完整的 `http://100.96.0.1:8080/`，按网页提示配对。
5. 如需域名，先自行配置域名的 DNS A 记录，使其解析到虚拟 IP，再填写设置中的「HTTP 域名（可选）」。车机入口为 `http://你的域名:8080/`；不需要证书。App 不配置 DNS，也不验证解析结果。DNS 服务可能过滤共享地址，解析通常需要联网。
6. 地址或域名变更后，浏览器可能需要重新配对，因为设备记忆按网页 origin 保存。

模式默认关闭，配置会保存。重开 Web 服务时，只有已获 VPN 授权才自动创建接口；缺少授权时在设置中点「重新授权并启动」。被其他 VPN 替换或授权撤销后，不会反复抢占 VPN。

「虚拟地址已创建」仅表示 Android VPN 接口分配成功，不代表车机已可达。系统热点的本地投递、回程路由、客户端隔离和车机浏览器策略，都可能使访问失败。域名不能保证绕过车机对目标 IP 或 HTTP 的限制。

## 功能和生命周期

- 复用现有 `8080` HTTP 服务、同源 WebSocket、配对和设备撤销机制。无需改变网页媒体协议。
- Android 同一用户只能有一个活动 VPN。此模式与 WheelPlay 有线 CarPlay 共用 `CarPlayVpnService`，可能替换第三方 VPN；其他 VPN 启动也可能使此模式失效。
- VPN 仅添加虚拟地址的 `/32` 路由；有线 CarPlay 连接时还包含原有 `fe80::/64` 路由。不添加 Internet 默认路由或更改 DNS。虚拟地址的 HTTP 连接由本机网络栈处理，不向 iPhone USB 链路发送 IPv4 数据包。
- 连接或断开有线 iPhone 时，需要重建 VPN 接口，浏览器连接可能短暂中断；断开后虚拟入口继续存在。停止 Web 服务或关闭模式会释放入口。
- HTTP 支持现有画面、触控和浏览器音频路径；浏览器麦克风仍要求可信 HTTPS。可以继续使用 Android 麦克风。
- 仅用于可信热点网络。HTTP 配对、控制与 JPEG 流量未加密，不应映射到公网。

## 自动验证

- Android 单元测试：`shared` 238 个、`common` 126 个全部通过，覆盖配置校验、偏好保存、接口路由、VPN 生命周期以及 HTTP/WebSocket 配对与同源检查。
- 网页端：`npm test` 的 7 组测试和 `npm run check` 全部通过。
- JVM 测试使用 JDK 21；Gradle 构建使用项目要求的 JDK 25。VPN 测试替代了内核 TUN 分配，不证明真实热点的路由或车机可达性。

## 实机验证顺序

当前实现尚未在 Android 热点 + Tesla 实机上验证。按以下顺序记录 Android 型号/版本和车机版本：

1. **热点到虚拟地址**：先用另一台电脑或手机连接该 Android 热点，访问完整 HTTP IP 地址。失败时先排查系统路由、地址冲突和热点隔离，不要归因于 Tesla。
2. **车机 HTTP IP**：用相同地址在 Tesla 上打开网页并配对，记录是否被改为 HTTPS、是否到达服务端。
3. **可选 HTTP 域名**：确认域名解析到虚拟 IP 后测试；不能把 IP 或域名入口的单次失败当作另一入口必然可用。
4. **WebSocket / JPEG**：先选择 JPEG 模式，检查配对、状态、连续画面和触控。首次验证无需先接通 WebRTC UDP。
5. **WebRTC / 音频**：检查 ICE 可达性、UDP 和浏览器解码能力；WebRTC 失败时应回退 JPEG。
6. **生命周期**：测试连接/断开 USB iPhone、无线 CarPlay、热点关闭/重开、App 前后台切换、VPN 被替换/撤销、模式关闭及「停止服务」。网页入口应与 Web 服务生命周期一致。

若目标 Tesla 拒绝 HTTP IP 和 HTTP 域名，此模式无法承诺解决；修改监听地址或继续换共享地址也不一定有效。此分支不引入 HTTPS 域名和证书签发。

## 参考案例

- [Tesla Android 2022.18.1](https://github.com/tesla-android/tesla-android.github.io/blob/main/install-guide-2022-18-1.md#ip-address-range)：历史版本记录 RFC1918 地址限制和修改实际网络地址段的方案。
- [TeslaMirror FAQ](https://teslamirror.com/faq-android)：开发者记录本地 VPN/虚拟地址的方案，但最新文档要求 HTTPS，并说明旧虚拟 IP 不能直接作为浏览器入口。这提供了实现方向，不能证明本分支的 HTTP 方案兼容当前所有车机版本。
