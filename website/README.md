# WheelPlay 官网（Astro）

独立于 Android 内置浏览器客户端的 Astro 静态产品官网。页面由 Astro 组件在构建时生成，浏览器仅加载全局 CSS 和少量 TypeScript 编译出的主题、语言、标签页交互脚本，无需 React/Vue hydration 或服务端运行时。没有远程字体、分析 SDK 或设备连接请求；深浅主题及手动选择的语言偏好会写入 localStorage。下载按钮通过 `/download` 固定重定向到 GitHub Releases，不再查询 GitHub API 或解析构建产物。

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
npm run site:test     # 构建产物、CSP 与下载入口测试
npm run site:preview  # Astro 预览已构建产物
```

`site:preview` 不会重新构建，请先运行 `site:build`。Astro 开发／预览服务器仅提供静态官网，不运行 `/download`，也不解释 Cloudflare 的 `_headers`。如需验证下载、CSP 和响应头，先构建，再选择一个 Cloudflare 本地运行器：

```sh
npx wrangler@4.152.0 pages dev --cwd website --port 4174
# 或独立 Worker
npx wrangler@4.152.0 dev --config website/wrangler.worker.jsonc --port 4174
```

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `astro.config.mjs` | 静态输出、开发端口、CSS/JS 外部文件策略 |
| `src/layouts/BaseLayout.astro` | HTML 外壳、SEO 元数据、全局样式 |
| `src/pages/index.astro` | 中文首页路由，共用 HomePage 组件 |
| `src/pages/docs/*.md` | 使用指南、输入源与兼容性、Tesla 兼容模式、常见问题正文及页面元数据 |
| `src/pages/en/` | 英文首页和英文 Markdown 文档 |
| `src/i18n/` | 类型检查的英文界面翻译、语言识别与站内链接工具 |
| `src/components/HomePage.astro` | 两种语言共用的首页结构 |
| `src/layouts/DocsLayout.astro` | 文档导航、自动目录和共享页面布局 |
| `src/styles/docs.css` | Markdown 正文排版、深浅主题和移动端样式 |
| `src/pages/404.astro` | 独立 404 页面与 `noindex` 元数据 |
| `src/components/` | 品牌、导航、首屏、交互示意、功能、使用步骤、FAQ、页脚 |
| `src/scripts/` | 类型检查和打包后的主题／标签页交互 |
| `src/styles/global.css` | 既有响应式样式、深浅主题、减少动态效果支持 |
| `src/pages/assets/` | 构建时生成的图标和许可证静态资源路由 |
| `public/_headers` | 原样复制到构建目录的静态资源响应头 |
| `public/_routes.json` | 只让 Pages 的下载路径进入 Functions |
| `worker/download.ts` | `/download` 的固定重定向与请求校验 |
| `worker/index.ts` | Workers 下载入口和 ASSETS 静态资源绑定 |
| `functions/download.ts` | Pages Functions 入口，复用同一下载入口 |
| `test/download.test.mjs` | 重定向目标、请求方法和路径的回归测试 |
| `test/build.test.mjs` | 构建产物和 CSP 回归检查 |
| `wrangler.jsonc` | Pages 默认配置 |
| `wrangler.worker.jsonc` | Workers Static Assets 配置 |

共享图标与许可证由静态资源路由直接导入 `docs/` 中的原文件，保留 `/assets/app-icon.svg` 和 `/assets/Material-Symbols-Apache-2.0.txt` 地址。这两条资源路由构建后是静态文件，不需要运行时 API，也不维护重复素材或额外拷贝脚本。下载入口则由独立 Worker／Pages Function 处理；应保留完整仓库供构建和部署使用。

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

Pages 会部署 `website/functions/download.ts`，并通过 `_routes.json` 只在下载路径调用 Functions。Git 集成或从 `website` 目录执行 Wrangler 可以同时部署静态文件与函数；仅拖拽上传 `dist/` 不包含下载入口。

Pages 读取默认 `wrangler.jsonc` 中的 `pages_build_output_dir`。Pages 不支持自定义配置路径，因此将默认文件名留给 Pages，Workers 使用独立命名的配置。

从仓库根目录手动上传：

```sh
npm ci --prefix website
npm run site:build
npx wrangler@4.152.0 pages deploy --cwd website
```

首次发布需要登录 Cloudflare，并选择或创建 `wheelplay-website` Pages 项目。若项目名称不同，更新 `wrangler.jsonc` 的 `name`。按照自己的发布流程选择 Git 集成或直接上传项目。

## Cloudflare Workers

官网使用 Astro 的 `output: 'static'`，无需 Astro SSR adapter 或数据库。`wrangler.worker.jsonc` 的 `main` 指向 `worker/index.ts`，`ASSETS` 托管 `dist/`，`run_worker_first` 仅为 `/download` 及其子路径启用脚本。其他匹配的静态资源直接交给 Cloudflare，避免为普通页面访问执行下载逻辑。

```sh
npm ci --prefix website
npm run site:build
# 仅校验打包，不发布
npx wrangler@4.152.0 deploy --config website/wrangler.worker.jsonc --dry-run
# 在你的 Cloudflare 账户下发布
npx wrangler@4.152.0 deploy --config website/wrangler.worker.jsonc
```

Workers Git 构建设置：

| 设置 | 值 |
| --- | --- |
| 根目录 | `website` |
| Node.js | `24` |
| 构建命令 | `npm run build` |
| 生产部署命令 | `npx wrangler@4.152.0 deploy --config wrangler.worker.jsonc` |
| PR／非生产分支预览命令 | `npx wrangler@4.152.0 preview --config wrangler.worker.jsonc` |

生产部署和预览命令是 Cloudflare Build 设置中的两个独立字段，两个命令都必须指定 Worker 配置，否则会读取用于 Pages 的默认 `wrangler.jsonc`。按需修改配置中的 `name`。

`wrangler.worker.jsonc` 包含 `previews: {}`，用于启用分支预览。此网站没有需要单独配置的数据绑定；`assets`（包括 `ASSETS`）及兼容性设置保留在顶层，预览上传当前分支构建出的静态文件。不要将生产部署命令用于 PR 预览，否则会发布到生产环境。

Pages Functions 和独立 Workers 共用同一下载入口，是两种独立托管方式，任选其一即可，无需同时部署。若未来增加按需服务端渲染，再引入对应 Astro adapter 并调整输出方式。部署凭据只配置在 Cloudflare 账户或 CI Secrets 中。

官方参考：[Astro 部署到 Cloudflare](https://docs.astro.build/en/guides/deploy/cloudflare/)、[Workers Static Assets](https://developers.cloudflare.com/workers/static-assets/get-started/)。

## 下载入口

点击「下载最新版本」访问 `GET /download`（也支持 `HEAD` 和 `/download/`）：Worker／Pages Function 固定返回无响应体的 `302`，`Location` 为 `https://github.com/fython/wheelplay/releases`。

入口不查询 GitHub API，不解析 Actions 构建产物，也不代理、解压或转发安装包；发布包和历史版本都由 GitHub Releases 托管，公开仓库的发布资源无需登录即可下载。

- **请求校验。** 其他方法返回 `405` 并带 `Allow: GET, HEAD`；`/download` 以外的路径返回 `404`。查询参数（包括旧的 `?lang=en`）、Cookie 和请求缓存头都不影响目标地址。
- **缓存。** 重定向带 `Cache-Control: public, max-age=300`，更换目标后最多 5 分钟内所有访客生效。
- **无需 Secret。** 入口不再读取 `GITHUB_TOKEN`；Cloudflare 项目中若仍保留该 Secret，可以删除。
- **修改目标。** 目标地址集中在 `worker/download.ts` 的 `RELEASES_URL`；更换发布页时同步更新 `test/download.test.mjs` 与页面说明。

## 内容维护

- **中英文路由。** 中文使用 `/`、`/docs/`、`/docs/faq/`，英文使用 `/en/`、`/en/docs/`、`/en/docs/faq/`。导航中的语言链接切换到对应页面，无 JavaScript 时同样可用。脚本记住手动选择的语言；再次访问 `/` 时，已选择英文的用户会进入 `/en/`。明确的文档链接始终以 URL 的语言为准，不根据浏览器语言强制跳转。禁用存储不会影响语言链接。
- **界面翻译。** `src/i18n/en.ts` 以中文原文为键存放英文翻译，共用组件通过 `useTranslations(Astro.url.pathname)` 获取 `t` 和 `local`。修改中文原文时同步更新翻译键及所有调用；缺失键由类型检查发现。品牌名、协议名及装饰性英文标语可共用。页面元数据、无障碍标签、主题按钮也需要翻译。
- **英文文档。** `src/pages/en/docs/*.md` 与中文文档成对维护，英文文档的布局路径为 `../../../layouts/DocsLayout.astro`，站内链接使用 `/en/docs/`。新增页面时同时更新 `DocsLayout.astro` 的导航与 `LanguageSwitch.astro` 的页面映射。高级仓库文档仍可能为中文，英文站内指南已注明。
- **下载与错误页。** 中英文下载链接都指向 `/download`，由固定重定向统一处理，官网不再输出下载错误页。静态 404 包含两种语言，在脚本可用时按实际请求路径选择语言。
- **文档页面使用 Markdown。** 使用指南位于 `src/pages/docs/index.md`，常见问题位于 `src/pages/docs/faq.md`；正文、标题、说明均在 Markdown 中维护，不在 Astro 布局中编写文档正文。首页仍由产品展示组件组成。
- 新增文档时，在 `src/pages/docs/` 添加 `.md` 文件，frontmatter 填写 `layout: ../../layouts/DocsLayout.astro`、`title` 和 `description`；文件名决定路径。在 `DocsLayout.astro` 的 `pages` 中添加导航入口即可。正文从 `##` 开始，页面主标题由布局读取 `title` 生成，二级标题自动生成本页目录。
- 使用标准 Markdown 编写段落、列表、链接、表格和代码块，无需内嵌 HTML。代码块关闭内联样式高亮以兼容 CSP；导航、主题与响应式排版由布局负责。Astro 原生支持 Markdown，无需新增运行时依赖。
- `GettingStarted.astro` 中的下载入口为 `/download`，目标发布页集中定义于 `worker/download.ts` 的 `RELEASES_URL`。更换下载来源时同步修改入口、测试与页面说明。
- `ExperiencePreview.astro` 中的导航、音乐、配对是明确标注的示意，不是真实 CarPlay 截图，不播放音频，也不连接设备。
- 页面默认中文，提供内置英文首页及使用文档。
- 绿色 App 图标与 Material Symbols 许可证沿用仓库文件，来源见 [`docs/APP_ICON.md`](../docs/APP_ICON.md)。
- Astro 的 `site` 使用 `https://wheelplay.gwo.app`，共享布局生成 canonical 及中英文 hreflang。更换正式域名时同步修改此设置和构建检查；分享图片可按需补充。
- 添加分析或外部 API 时，需要同步维护 CSP，不能直接关闭策略。

## 验证

网站 CI 使用 Node.js 24 和锁文件安装依赖，依次执行 Astro 检查、静态构建、产物测试与下载入口测试，然后上传 `dist/`（该产物仅含静态页面，部署下载功能还需要仓库中的 Worker／Functions 源码）。产物测试确认首页、404 和文档页面使用有效的本地外部资源、文档目录指向实际标题、404 带 `noindex`，以及共享图标／许可证／响应头没有丢失。

浏览器回归检查应覆盖桌面和手机布局、三个标签页、方向键与 Home/End、主题保存、禁止存储、FAQ、无 JavaScript 阅读，以及 Cloudflare 本地运行器下的 CSP 和 404 响应。
