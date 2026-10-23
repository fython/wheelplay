# WheelPlay 官网

独立于 Android 内置浏览器客户端的静态产品官网。采用原生 HTML、CSS 和 JavaScript，无运行时第三方依赖、远程字体、分析 SDK 或设备连接请求。只有深浅主题偏好会写入浏览器 localStorage；导航／音乐／配对面板均为明确标注的界面示意。

参考 [Lovable JSON Formatter 模板](https://lovable.dev/templates/apps/developer-tools/json-formatter) 的蓝色强调、双栏工作区和标签式输出布局，重新设计为产品介绍网站。模板说明页可访问，交互预览未能访问；未复制模板源码或声称逐像素复刻。产品功能以本仓库 README 和文档为依据。

## 本地开发

需要 Node.js 22+，不需要运行 `npm install`，也不需要 Android SDK。

从仓库根目录运行：

```sh
npm run site:dev
npm run site:check
npm run site:build
```

预览地址为 `http://127.0.0.1:4173`。可通过 `PORT=4180 npm run site:dev` 修改端口。预览服务器启动时构建一次，修改文件后需重新运行构建或重启预览；不含自动刷新。构建只清理并重建 `website/dist/`，不会修改 Android 资源。

| 路径 | 用途 |
| --- | --- |
| `public/index.html` | 官网结构、产品文案、GitHub 和文档链接 |
| `public/style.css` | 响应式样式、深浅主题、减少动态效果支持 |
| `public/app.js` | 主题切换、键盘可操作的预览标签页 |
| `public/_headers` | Cloudflare 静态资源响应头，本地预览也会应用 |
| `public/404.html` | 独立 404 页面，避免将未知路径当作 SPA 首页 |
| `scripts/build.mjs` | 拷贝静态文件及仓库现有 App 图标和图标许可证 |
| `wrangler.worker.jsonc` | Workers Static Assets 配置，通过 `--config` 显式选择 |
| `wrangler.jsonc` | Pages Git 集成和 CLI 的默认配置 |

## Cloudflare Pages

在 Cloudflare 中连接本仓库，选择 Pages 项目：

| 设置 | 值 |
| --- | --- |
| 框架预设 | None |
| 根目录 | `website` |
| 构建命令 | `npm run build` |
| 输出目录 | `dist` |
| Node.js 版本 | `22` 或更新版本，可设置 `NODE_VERSION=22` |
| 生产分支 | 选择官网代码所在分支；合并后可设为 `main` |

构建需要完整仓库中的 `docs/assets/app-icon.svg` 和 `docs/licenses/Material-Symbols-Apache-2.0.txt`，请勿只上传未构建的 `website/` 源文件。Pages 会读取默认 `wrangler.jsonc` 中的 `pages_build_output_dir`。Pages 不支持自定义配置路径，因此保留默认文件名；Workers 使用独立命名的配置。

也可以先在本地构建，再通过 Wrangler 上传（以下命令从仓库根目录运行）：

```sh
npm run site:build
npx wrangler@4.152.0 pages deploy --cwd website
```

首次使用需登录 Cloudflare，并选择或创建 `wheelplay-website` Pages 项目。若项目名不同，更新 `wrangler.jsonc` 中的 `name`。Git 集成和直接上传的项目创建方式请按自己的发布流程选择。

## Cloudflare Workers

同一构建也支持 Workers Static Assets。官网目前没有后端业务，因此无需自定义 Worker handler、数据库或 Secrets。静态资源配置本身即可作为 Worker 部署，不必把 CarPlay 局域网服务迁到云端。

从仓库根目录运行：

```sh
npm run site:build
# 仅校验打包，不发布
npx wrangler@4.152.0 deploy --config website/wrangler.worker.jsonc --dry-run
# 在你的 Cloudflare 账户下发布
npx wrangler@4.152.0 deploy --config website/wrangler.worker.jsonc
```

使用 Workers Git 构建时，设置根目录 `website`、构建命令 `npm run build`、部署命令 `npx wrangler@4.152.0 deploy --config wrangler.worker.jsonc`。按需修改配置中的 `name`。后续若需要真正的 API，可在此配置中增加 `main` 和 Assets binding，而不影响 Android 应用。

Pages 和 Workers 是两种独立托管方式，任选其一即可运行官网；无需同时部署。部署凭据只配置在你的 Cloudflare 账户或 CI Secrets 中，不写入仓库。

官方参考：[Pages 静态 HTML](https://developers.cloudflare.com/pages/framework-guides/deploy-anything/)、[Workers Static Assets](https://developers.cloudflare.com/workers/static-assets/get-started/)。

## 内容维护

- 下载入口使用现有 GitHub Actions 工作流与实际产物名 `wheelplay-debug-apk`。没有假设存在稳定版 Release；获取开发产物需要登录 GitHub，且过期产物可能不可下载。发布稳定版后可在 `index.html` 替换入口及相邻说明。
- 页面默认中文，提供英文项目文档入口；未声称官网支持双语切换。
- 导航图和音乐图案是本站 CSS/SVG 示意，不是真实 CarPlay 截图；预览不播放音频，也不连接设备。
- 沿用仓库现有绿色 App 图标。Material Symbols 图标许可证随构建输出到 `/assets/Material-Symbols-Apache-2.0.txt`，来源见 [`docs/APP_ICON.md`](../docs/APP_ICON.md)。其余简单线条图标和场景由本站绘制。
- 官网部署地址确定前，不写入虚构的 canonical、站点地图域名或分享图 URL。确定域名后可补充这些 SEO 元数据。
- CSP 限制外部脚本和网络请求。若后续添加分析或外部 API，需同步维护 `_headers`，而不是直接关闭 CSP。

## 验证

`npm run site:check` 检查脚本语法，`npm run site:build` 生成完整静态产物；网站 CI 执行这两项并上传构建产物。发布前可用浏览器确认以下行为：

- 桌面和手机上无横向溢出；三个预览标签均可切换。
- 标签页支持方向键、Home/End；深浅主题刷新后保留，禁用存储时仍可切换。
- FAQ 可展开；无 JavaScript 时仍能阅读正文、使用文档／构建链接和展开 FAQ。
- 未知路径返回 404，图标、CSS、JS 加载成功；控制台无 CSP 错误。
