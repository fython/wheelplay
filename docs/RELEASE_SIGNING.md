# GitHub Actions 签名配置

Debug 和 Release 工作流共用同一套 WheelPlay 签名密钥。私钥不提交到仓库，
使用 GitHub Actions 加密 Secrets 保存，在构建步骤的 runner 临时目录中还原，
步骤结束后删除。签名构建关闭 Gradle configuration cache，避免缓存序列化密码。

## 添加四个 Repository Secrets

进入仓库 **Settings → Secrets and variables → Actions → New repository secret**。
选择 **Secrets**，不要使用 Variables，也不要上传 `.jks` 或密码 JSON 到仓库。

| Secret 名称 | 值 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `.local/signing/wheelplay-release.jks` 的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | JSON 中的同名字段 |
| `ANDROID_KEY_ALIAS` | JSON 中的同名字段，当前为 `wheelplay-release` |
| `ANDROID_KEY_PASSWORD` | JSON 中的同名字段 |

在项目根目录的 PowerShell 中，依次执行下面的命令。每执行一个 `Set-Clipboard`，
将剪贴板值粘贴到对应的 Secret 输入框并保存，再执行下一个命令。
这些命令不会把密码写入终端输出或命令历史，也不生成 Base64 明文文件。

```powershell
$signing = Get-Content .local/signing/release-signing.json -Raw | ConvertFrom-Json

# ANDROID_KEYSTORE_BASE64
Set-Clipboard ([Convert]::ToBase64String([IO.File]::ReadAllBytes($signing.ANDROID_KEYSTORE_PATH)))

# ANDROID_KEYSTORE_PASSWORD
Set-Clipboard $signing.ANDROID_KEYSTORE_PASSWORD

# ANDROID_KEY_ALIAS
Set-Clipboard $signing.ANDROID_KEY_ALIAS

# ANDROID_KEY_PASSWORD
Set-Clipboard $signing.ANDROID_KEY_PASSWORD

# 四项均保存后清空剪贴板和当前 PowerShell 变量
Set-Clipboard -Value ''
Remove-Variable signing
```

Base64 只是二进制到文本的编码，**不是加密**。安全存储由 GitHub Actions Secrets
提供。不要把 Base64 值放入 YAML、Variables、日志、Issue 或聊天。
公开证书 `.pem` 和本地加载脚本无需上传；本地路径也无需配置为 Secret。

## 可选的 HTTPS 签发 CA Secret

编译内置的 HTTPS 签发 CA 与 keystore 分开，使用另一个 Secret：

| Secret 名称 | 值 |
| --- | --- |
| `WHEELPLAY_HTTPS_CA_PEM_BASE64` | `.local/https-ca/emudhra-ca.pem`（组合 PEM）的 Base64 内容 |

```powershell
Set-Clipboard ([Convert]::ToBase64String([IO.File]::ReadAllBytes('.local/https-ca/emudhra-ca.pem')))
```

未设置时 Release 仍会内置 CA：默认 `WHEELPLAY_HTTPS_CA_FALLBACK=mitmproxy`，从固定 commit 读取
mitmproxy 公开源码中的组合 PEM，并给宿主 artifact 加 `-MITMPROXY-CA` 后缀。Release 页的宿主 APK 下载链接也会标注 `MITMPROXY-CA`。该私钥是公开材料，
签出的证书可以被复现，只适合冒烟；正式分发请配置上面的 Secret 或按
[HTTPS_SIGNING_CA.md](HTTPS_SIGNING_CA.md) 生成自有 CA。把 `WHEELPLAY_HTTPS_CA_FALLBACK`
设为其他值可关闭回退，此时 `WHEELPLAY_HTTPS_CA_REQUIRED`（默认 `1`）决定缺 Secret 时失败还是跳过。
Android Debug Build 工作流使用同一组默认值，因此 PR 与分支构建的 Debug APK 也会内置 CA。

## 触发构建

先配置上述四个签名 Secrets（以及可选的 CA Secret），再提交并推送 workflow 和构建配置变更。

- **Android Debug Build**：推送到 `main`、推送标签或在 `main`/标签上手动运行时，
  使用 Secrets 中的密钥。PR 和其他分支的手动运行使用默认 Debug 密钥。
  可信构建缺少 Secrets 时会失败，而不会静默改用其他签名。
  CarLink 和 CarLife 的独立 Debug 构建任务使用同一套 Secrets 与上述触发规则。
  CarLink 二次打包也使用该密钥；CarLife 任务执行插件 lint、接收器及插件单元测试。
  APK 分别上传为 `wheelplay-carlink-debug-apk`、`wheelplay-carlife-debug-apk`，
  CarLife 检查报告上传为 `wheelplay-carlife-reports`。
  PR/其他分支仍使用各任务生成的默认 Debug 密钥，不保证跨独立 runner 的证书一致。
- **Android Release Build** 使用同一把发行密钥构建 WheelPlay 和 CarLink，并对两个最终 APK 验签。
  推送 `v*` 标签会把 `wheelplay-release-<tag>.apk` 和 `wheelplay-carlink-release-<tag>.apk`
  附加到该标签的 GitHub Release。首次发布会创建 Release 并生成说明，已有 Release 只补缺失的 APK。
  首次发布中断后，重跑会补齐附件并公开带有该工作流标记的草稿。手动创建或旧流程留下的无标记草稿会在附件齐备后提示维护者手动发布，此时任务以失败结束。
  WheelPlay 的 AAB 保存在宿主的 Actions artifact 中，插件的依赖清单保存在 `wheelplay-carlink-release` artifact 中。
- 补发已有版本时，在 Actions → **Android Release Build** → **Run workflow** 中选择 `main`，
  将 `release_tag` 填为目标标签（例如 `v1.1.0`）。工作流检出该标签的源码构建两个 APK。
  在 `main` 上留空此字段时，仅构建并保存 Actions artifacts。

Debug 应用 ID 仍为 `moe.feng.wheelplay.debug`，Release 为 `moe.feng.wheelplay`。
旧 Debug 包若使用默认 Debug 密钥签名，切换后需要先卸载旧包才能安装，卸载会删除应用数据。
签名不等于注入 CarPlay 认证资源；这套工作流不打包本地认证资源。

当前密钥的 SHA-256 证书指纹（公开信息）：

```text
77:FD:6D:8D:E6:12:24:19:3E:B1:29:0B:91:17:36:BA:C1:E8:B4:65:C1:DC:A3:D3:68:31:07:43:C4:C8:A4:A6
```

在本地检查下载的 APK：

```powershell
& .local/android-sdk/build-tools/36.0.0/apksigner.bat verify --print-certs path/to/app.apk
```

保留密钥库和密码的离线安全备份；GitHub Secrets 不能替代可恢复的密钥备份。
有权限修改可信分支 workflow 的人员能够编写读取 Secrets 的任务，请限制相应写权限。

参考：[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
