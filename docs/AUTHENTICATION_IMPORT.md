# CarPlay 认证资源导入

WheelPlay 的源码和默认安装包不包含 CarPlay 认证资源。未配置时，Web 服务和浏览器预览仍可使用；连接 iPhone 前需要完成以下任意一种导入。

## 从 DiPlay 官网 APK 导入

1. 打开 WheelPlay **设置 → CarPlay 认证资源 → DiPlay 官网下载**，或直接访问 [DiPlay 官网](https://shihabal3amri.github.io/DiPlay/)。
2. 下载官网提供的 DiPlay APK，无需安装 DiPlay。
3. 返回 WheelPlay，选择“从 DiPlay APK 导入”，在系统文件选择器中选择下载的 APK。也可以在文件管理器中将 APK 分享给 WheelPlay，并点击“导入”。
4. 出现“认证资源已导入并通过校验”后，返回 iPhone 页面连接设备。

支持的 APK 必须包含 `assets/offline-mfi/identity.pk8` 和 `assets/offline-mfi/certificate.p7b`。APK 最大 256 MB，每份解压后的资源最大 16 KB；缺少资源、重复条目、错误格式或不匹配的文件会被拒绝。文件管理器未提供 WheelPlay 分享入口时，请使用设置中的文件选择器。

## 手动选择资源

选择“手动选择认证资源”，先选择 `identity.pk8`，再选择 `certificate.p7b`。需要未加密的 PKCS#8 DER 格式 EC 私钥和单张 P-256 X.509 证书（DER 或 PKCS#7 容器），两份文件应来自同一套资源。取消选择或校验失败会保留原有配置。

导入前请先断开 iPhone；连接或正在连接期间不能替换资源。导入成功后无需重启应用，下次连接使用新资源。校验只确认格式、曲线和密钥匹配，不代表 iPhone 一定接受该身份，实际认证与兼容性仍需设备验证。

## 本地存储与开源分发

- WheelPlay 只读取两个固定 ZIP 条目，不安装 APK、不执行其中的代码，不将其他文件解压到磁盘。
- 导入的资源保存在应用私有的 `noBackupFilesDir/offline-mfi/`，不参与系统备份，不上传，不写入诊断报告。
- APK 的临时副本与校验暂存文件在导入结束时删除；文件管理器中用户下载的原始 APK 不会被删除。
- 整套资源校验成功后才替换原有目录；替换中断时，下次加载会恢复上一套完整资源或保留已完成的新配置。
- 导入状态由 ViewModel 保留，旋转或重建设置页面不会重新执行导入。卸载 WheelPlay 或清除其应用数据会删除导入的资源，需要重新导入。
- 不将用户资源提交到 Git，也不复制到公开 APK。原有显式本地构建配置仅用于开发者自己的构建，参见 README。

如果提示无法读取文件，请重新选择可访问的本地文件，并确认系统文件管理器已启用；若提示 APK 不含资源，请重新从官网下载兼容版本。可用两份原始资源的手动导入方式处理不兼容的 APK 布局。
