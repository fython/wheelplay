# WheelPlay 官网（Astro）

独立于 Android 内置浏览器客户端的 Astro 静态产品官网。页面由 Astro 组件在构建时生成，浏览器仅加载全局 CSS 和少量 TypeScript 编译出的主题、标签页交互脚本，无需 React/Vue hydration 或服务端运行时。没有远程字体、分析 SDK 或设备连接请求；只有深浅主题偏好会写入 localStorage。

设计参考 [Lovable JSON Formatter 模板](https://lovable.dev/templates/apps/developer-tools/json-formatter) 的蓝色强调、双栏工作区和标签页布局。保留 WheelPlay 的既有图标、产品文案、响应式样式和交互示意。

## 本地开发

推荐 Node.js 24 LTS（Astro 7.4.2 要求至少 Node.js 22.23.4）。官网依赖与仓库根目录的 Android 开发工具独立，不需要 Android SDK。

从仓库根目录运行：

```sh
npm ci --prefix website
npm run site:dev
```

开发地址为 `http://127.0.0.1:4173`，修改 Astro 组件、样式和脚本会自动更新。自定义端口可运行 `npm --prefix website run dev -- --port 4180`。

```sh
npm run site:check    # Astro 模板和 TypeScript 检查
npm run site:build    # 输出 website/dist/
npm run site:test     # 检查已构建产物、CSP 兼容性和共享资源
npm run site:preview  # Astro 预览已构建产物
```

`site:preview` 不会重新构建，请先运行 `site:build`。Astro 开发服务器和预览服务器不解释 Cloudflare 的 `_headers`；如需验证生产 CSP、响应头和路由，先构建，再运行：

```sh
npx wrangler@4.152.0 pages dev --cwd website --port 4174
```

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `astro.config.mjs` | 静态输出、开发端口、CSS/JS 外部文件策略 |
| `src/layouts/BaseLayout.astro` | HTML 外壳、SEO 元数据、全局样式 |
| `src/pages/index.astro` | 组合首页各功能区 |
| `src/pages/404.astro` | 独立 404 页面与 `noindex` 元数据 |
| `src/components/` | 品牌、导航、首屏、交互示意、功能、使用步骤、FAQ、页脚 |
| `src/scripts/` | 类型检查和打包后的主题／标签页交互 |
| `src/styles/global.css` | 既有响应式样式、深浅主题、减少动态效果支持 |
| `src/pages/assets/` | 构建时生成的图标和许可证静态资源路由 |
| `public/_headers` | 原样复制到构建目录的 Cloudflare 响应头 |
| `test/build.test.mjs` | 构建产物和 CSP 回归检查 |
| `wrangler.jsonc` | Pages 默认配置 |
| `wrangler.worker.jsonc` | Workers Static Assets 配置 |

共享图标与许可证由静态资源路由直接导入 `docs/` 中的原文件，保留 `/assets/app-icon.svg` 和 `/assets/Material-Symbols-Apache-2.0.txt` 地址。构建后只输出静态文件，不需要 API 服务，也不维护重复素材或额外拷贝脚本。应保留完整仓库供构建使用。

Astro 组件中的 `<script>` 由框架打包为带哈希的外部文件。配置禁用小脚本及 CSS 内联，以满足 `_headers` 中的 `script-src 'self'` 和 `style-src 'self'`；不要为迁就构建输出加入 `unsafe-inline`。构建回归检查会验证这一点。

## Cloudflare Pages

连接本仓库后使用以下构建设置：

| 设置 | 值 |
| --- | --- |
| 框架预设 | Astro |
| 根目录 | `website` |
| 依赖安装 | 使用提交的 `package-lock.json`；本地和 CI 使用 `npm ci` |
| 构建命令 | `npm run build` |
| 输出目录 | `dist` |
| 环境变量 | `NODE_VERSION=24` |
| 生产分支 | 官网代码所在分支；合并后可设为 `main` |

Pages 读取默认 `wrangler.jsonc` 中的 `pages_build_output_dir`。Pages 不支持自定义配置路径，因此将默认文件名留给 Pages，Workers 使用独立命名的配置。

从仓库根目录手动上传：

```sh
npm ci --prefix website
npm run site:build
npx wrangler@4.152.0 pages deploy --cwd website
```

首次发布需要登录 Cloudflare，并选择或创建 `wheelplay-website` Pages 项目。若项目名称不同，更新 `wrangler.jsonc` 的 `name`。按照自己的发布流程选择 Git 集成或直接上传项目。

## Cloudflare Workers

官网使用 Astro 的 `output: 'static'`，无需 `@astrojs/cloudflare` SSR adapter、Worker handler、数据库或 Secrets，现有 Workers Static Assets 配置直接托管 `dist/`。

```sh
npm ci --prefix website
npm run site:build
# 仅校验打包，不发布
npx wrangler@4.152.0 deploy --config website/wrangler.worker.jsonc --dry-run
# 在你的 Cloudflare 账户下发布
npx wrangler@4.152.0 deploy --config website/wrangler.worker.jsonc
```

Workers Git 构建设置根目录 `website`、Node.js 24、构建命令 `npm run build`、部署命令 `npx wrangler@4.152.0 deploy --config wrangler.worker.jsonc`。按需修改配置中的 `name`。

Pages 和 Workers 是两种独立托管方式，任选其一即可，无需同时部署。若未来增加按需服务端渲染，再引入对应 Astro adapter 并调整输出方式。部署凭据只配置在 Cloudflare 账户或 CI Secrets 中。

官方参考：[Astro 部署到 Cloudflare](https://docs.astro.build/en/guides/deploy/cloudflare/)、[Workers Static Assets](https://developers.cloudflare.com/workers/static-assets/get-started/)。

## 内容维护

- `GettingStarted.astro` 中的下载入口使用现有 GitHub Actions 工作流与产物名 `wheelplay-debug-apk`。下载需要登录 GitHub，产物可能过期；发布稳定版后在此组件中替换入口和相邻说明。
- `ExperiencePreview.astro` 中的导航、音乐、配对是明确标注的示意，不是真实 CarPlay 截图，不播放音频，也不连接设备。
- 页面默认中文，并提供英文项目文档入口。
- 绿色 App 图标与 Material Symbols 许可证沿用仓库文件，来源见 [`docs/APP_ICON.md`](../docs/APP_ICON.md)。
- 部署域名确定后，可在 Astro 配置中增加 `site`，并在共享布局中补充 canonical 和分享图片；目前不写入虚构域名。
- 添加分析或外部 API 时，需要同步维护 CSP，不能直接关闭策略。

## 验证

网站 CI 使用 Node.js 24 和锁文件安装依赖，依次执行 Astro 检查、静态构建和产物测试，然后上传 `dist/`。产物测试确认首页交互脚本和两条页面路由的 CSS 均是有效的本地外部文件、404 带 `noindex`、共享图标／许可证／响应头没有丢失。

浏览器回归检查应覆盖桌面和手机布局、三个标签页、方向键与 Home/End、主题保存、禁止存储、FAQ、无 JavaScript 阅读，以及 Cloudflare 本地运行器下的 CSP 和 404 响应。
