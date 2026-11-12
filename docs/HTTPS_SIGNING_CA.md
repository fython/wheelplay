# eMudhra CA 的 HTTPS 签发框架

此功能沿用 mitmproxy 的“加载签发 CA → 签发服务器证书 → 配置 TLS 证书链”流程，接入 WheelPlay 自己的 HTTPS 服务。支持编译时内置和运行时手动导入，不按客户端版本选择证书。仓库不附带实际 CA 材料。

## 编译时内置

与 mitmproxy 的 `EMUDHRA_CHAIN_PEM` 采用相同的组合格式：一个未加密的 RSA / EC CA 私钥，后面是签发 CA 及其有序上级证书链。文件总大小不超过 2 MiB。

```text
-----BEGIN PRIVATE KEY-----
签发 CA 私钥
-----END PRIVATE KEY-----
-----BEGIN CERTIFICATE-----
与私钥匹配的签发 CA
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
上级 CA（可继续追加同一路径中的上级）
-----END CERTIFICATE-----
```

将文件放到已被 Git 忽略的 `.local/https-ca/emudhra-ca.pem`，正常构建即可：

```bash
./gradlew :mobile:assembleDebug
```

也可以指定文件位置（相对路径以仓库根目录为基准）：

```bash
WHEELPLAY_HTTPS_CA_PEM=/absolute/path/to/emudhra-ca.pem \
  ./gradlew :mobile:assembleDebug
```

同一配置也适用于 Release 构建，Release 签名仍按项目原有配置提供。Gradle 的 `bundleHttpsCa` 任务只复制指定 PEM 到生成的 `assets/web-tls/emudhra-ca.pem`，不会打包源目录中的其他文件；未配置时不会内置 CA。显式指定的文件缺失、为空、超限或没有一个未加密私钥和证书时构建失败。密钥匹配、CA 权限和证书链签名在运行时通过共用的 CA 验证器检查。

首次启动且没有自定义身份或明确的本地证书选择时，会自动使用内置 CA，无需打开文件选择器。设置中显示「内置 eMudhra CA」，选中后可切换回内置来源。内置材料每次从当前 APK 加载，不复制为手动导入的持久身份，因此更新 APK 中的 CA 后，重启 Web 服务即可使用新材料。

来源优先级：手动导入的身份优先；否则使用已启用的内置 CA；最后是默认本地 CA。明确选择默认本地证书后保持该选择，重启不会重新启用内置 CA。选择手动导入表单但未导入文件，不会替换正在使用的身份。已选中的内置材料无效或在更新后缺失时报告 HTTPS 启动错误，不悄悄替换成另一张 CA。

移除环境变量及默认路径下的 PEM 后再次构建，任务会清理旧的生成文件，避免旧 CA 留在 APK 中。运行时手动导入的身份保留在应用私有存储，移除构建材料不会删除它。

内置私钥会随 APK 分发且可被提取；这种方式应只用于你决定随该构建一起分发的 CA 材料。构建日志不输出 PEM 内容，仓库也不保存该私钥。

## 手动导入

1. 在「设置 → Web 服务」打开 HTTPS，选择「eMudhra CA 签发（手动导入）」。此时只切换导入表单，现有证书保持使用。
2. 点击「选择 CA 证书链与私钥」，先选择 PEM 证书链，再选择与第一张 CA 证书匹配的 PEM 私钥；加密私钥在随后弹出的输入框填写口令。
3. 导入成功后 HTTPS 使用新签发的服务器证书。域名与 Tesla 兼容访问地址改变时会重新签发；App 重启时从已保存的 CA 重新生成服务器密钥和证书。
4. 选择「默认本地证书」会删除已导入的身份并恢复本地 CA。导入 PEM / PKCS#12 服务器证书也会替换当前签发身份。关闭 HTTPS 保留已导入的身份，重新开启后继续使用。

需要的是**可签发服务器证书的 CA 及其匹配私钥**。普通 eMudhra SSL 服务器证书仍从原有 PEM / PKCS#12 入口导入。

证书链文件按签发顺序排列：

```text
-----BEGIN CERTIFICATE-----
签发 CA（与私钥匹配）
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
上级 CA
-----END CERTIFICATE-----
……
```

每次提供一条有序路径；如存在交叉签名，手动选择所需路径，不把相同签发者的多个替代证书追加到同一文件。最后的根证书可省略，由客户端自身的信任库补全。

私钥支持 RSA、EC，含传统 PEM、PKCS#8、对应加密格式，以及传统 EC 私钥前的 EC PARAMETERS。证书链与私钥文件各限制为 2 MiB。文件选择和口令输入复用现有的临时导入流程。

## 签发与持久化

- CA 导入检查有效期、密钥匹配、CA 约束、keyCertSign（存在 Key Usage 时）、路径长度、服务器用途限制，以及相邻证书的名称和签名。
- 服务器使用独立的 RSA 2048 位密钥，CA 的 RSA / EC 私钥只用于签名。叶证书包含 `CA:FALSE`、TLS serverAuth、数字签名与密钥加密用途，以及随机序列号。
- SAN 包含当前局域网 IP、Tesla 兼容访问 IP、`127.0.0.1`、`::1`、`localhost` 和配置的 Web 域名。填写域名不会自动配置 DNS。
- AKI 复制签发 CA 的实际 SKI，兼容非 SHA-1 SKI。证书链从叶证书开始发送。
- 叶证书有效期最多七天，且不超出所提供 CA 链的有效区间；在导入、地址/域名刷新及服务重启时重新签发。当前框架没有后台定时续期，持续运行超过有效期前需重启 Web 服务。
- 手动导入的 CA 身份以 PKCS#12 的 `signer` 条目保存到应用私有 `noBackupFilesDir/browser-server-custom.p12`。原有服务器证书使用 `server` 条目，旧文件无需迁移。此运行时文件包含私钥，不作为构建输入或提交仓库；编译内置来源直接使用 APK 中配置的 PEM。
- 保存复用 `AtomicFile`，监听更新复用 `WebSession.replaceHttps` 的失败回滚；解析或签发失败不覆盖已有身份。HTTPS 更新不更换当前 HTTP 配对码。
- `/tls.json` 的 `signingCa` 表示使用提供的 CA 签发，`bundledCa` 进一步表示当前监听使用 APK 内置来源；`/certificate.crt` 仍只导出链尾的公开证书。

客户端仍执行正常的证书链、域名、有效期、用途、策略与吊销校验。此功能不修改客户端信任库，也不保证手动提供的证书会受车机信任。CA 导入检查不替代客户端完整的 PKIX 验证。

## 代码入口与验证

- `common/.../web/SigningCa.kt`：CA 验证、叶证书生成、AKI/SKI 和证书链。
- `common/.../web/LanTls.kt`：共用 PEM 解析、私有存储、CA/服务器身份识别和 SSLContext。
- `mobile/build.gradle.kts`：本地 PEM 构建输入、生成 assets 和显式打包范围。
- `common/.../web/WebSession.kt`：导入应用、地址变化刷新和监听回滚。
- `common/.../settings/WebSettingsScreen.kt`、`WheelPlayActivity.kt`：手动选择证书链、私钥与口令。

使用测试生成的 CA 运行：

```bash
./gradlew :common:testDebugUnitTest \
  --tests '*SigningCaTest' \
  --tests '*LanTlsTest' \
  --tests '*LanTlsEcTest' \
  --tests '*HttpsSettingsUiTest' \
  --tests '*WebListenSettingsTest'
```

测试包含 RSA / EC 根 CA 与中间 CA、真实 TLS 握手与主机名校验、独立服务器密钥、非标准 SKI、有效期限制、加密 PEM、重载与 SAN 刷新、非法材料不覆盖原身份，以及 Web 服务启停和恢复默认身份。

2026-11-12 验证：上述四个套件共 28 项测试通过（新增 CA 7 项、原有 TLS 13 项、设置界面 8 项）；三种语言资源目录一致性、`:mobile:assembleDebug` 与 `git diff --check` 通过。Debug APK 位于 `mobile/build/outputs/apk/debug/mobile-debug.apk`。测试运行库使用环境的本地缓存，未修改仓库工具链配置。未导入真实 eMudhra 材料，也未在车机上实测。

编译内置扩展验证：五个套件共 41 项测试通过，包含新增的六项组合 PEM、真实 AssetManager 读取、自动启用、手动身份优先、明确切换来源、无效材料失败与 HTTPS 请求/刷新测试。使用临时生成的 CA 构建 Debug APK，直接检查 ZIP 中 PEM 与输入逐字节一致，且没有打包源目录中的无关文件；取消配置后再次构建，确认生成目录与 APK 都已移除旧 PEM。两次正常配置缓存构建均通过，当前 Debug APK 未内置测试 CA。测试未使用真实 eMudhra 材料。

参考：[mitmproxy 证书机制](https://docs.mitmproxy.org/stable/concepts/certificates/)、[证书实现](https://github.com/mitmproxy/mitmproxy/blob/main/mitmproxy/certs.py)、[TLS 配置实现](https://github.com/mitmproxy/mitmproxy/blob/main/mitmproxy/addons/tlsconfig.py)。本框架不复制其内置的已泄露 CA 私钥、替代路径自动选择或吊销请求拦截逻辑。
