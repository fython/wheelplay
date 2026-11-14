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

## 触发构建

先配置上述四个 Secrets，再提交并推送 workflow 和构建配置变更。

- **Android Debug Build**：推送到 `main`、推送标签或在 `main`/标签上手动运行时，
  使用 Secrets 中的密钥。PR 和其他分支的手动运行使用默认 Debug 密钥。
  可信构建缺少 Secrets 时会失败，而不会静默改用其他签名。
- **Android Release Build**：在 Actions 页面选择此工作流，选择 `main`，点击
  **Run workflow**；也可推送 `v*` 标签触发。产物位于 `wheelplay-release` artifact，
  包含签名 APK 和 AAB，不会自动创建 GitHub Release 或发布到应用商店。

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
