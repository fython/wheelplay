# WheelPlay 官网（Astro）

独立于 Android 内置浏览器客户端的 Astro 静态产品官网。页面由 Astro 组件在构建时生成，浏览器仅加载全局 CSS 和少量 TypeScript 编译出的主题、标签页交互脚本，无需 React/Vue hydration 或服务端运行时。没有远程字体、分析 SDK 或设备连接请求；只有深浅主题偏好会写入 localStorage。下载按钮通过 `/download` 的 Cloudflare Worker 查询 GitHub Actions 元数据，再重定向到 GitHub 产物页面。

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
npm run site:test     # 构建产物、CSP、下载与缓存行为测试
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
| `src/pages/index.astro` | 组合首页各功能区 |
| `src/pages/docs/*.md` | 使用指南、常见问题正文及页面元数据 |
| `src/layouts/DocsLayout.astro` | 文档导航、自动目录和共享页面布局 |
| `src/styles/docs.css` | Markdown 正文排版、深浅主题和移动端样式 |
| `src/pages/404.astro` | 独立 404 页面与 `noindex` 元数据 |
| `src/components/` | 品牌、导航、首屏、交互示意、功能、使用步骤、FAQ、页脚 |
| `src/scripts/` | 类型检查和打包后的主题／标签页交互 |
| `src/styles/global.css` | 既有响应式样式、深浅主题、减少动态效果支持 |
| `src/pages/assets/` | 构建时生成的图标和许可证静态资源路由 |
| `public/_headers` | 原样复制到构建目录的静态资源响应头 |
| `public/_routes.json` | 只让 Pages 的下载路径进入 Functions |
| `worker/download.ts` | GitHub 构建解析、内存／边缘缓存、302 重定向 |
| `worker/index.ts` | Workers 下载入口和 ASSETS 静态资源绑定 |
| `functions/download.ts` | Pages Functions 入口，复用同一解析器 |
| `test/download.test.mjs` | 下载选择、缓存、并发、限流和错误回归测试 |
| `test/build.test.mjs` | 构建产物和 CSP 回归检查 |
| `wrangler.jsonc` | Pages 默认配置 |
| `wrangler.worker.jsonc` | Workers Static Assets 配置 |

共享图标与许可证由静态资源路由直接导入 `docs/` 中的原文件，保留 `/assets/app-icon.svg` 和 `/assets/Material-Symbols-Apache-2.0.txt` 地址。这两条资源路由构建后是静态文件，不需要运行时 API，也不维护重复素材或额外拷贝脚本。下载解析则由独立 Worker／Pages Function 处理；应保留完整仓库供构建和部署使用。

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

Pages 会部署 `website/functions/download.ts`，并通过 `_routes.json` 只在下载路径调用 Functions。Git 集成或从 `website` 目录执行 Wrangler 可以同时部署静态文件与函数；仅拖拽上传 `dist/` 不包含下载解析器。

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

Workers Git 构建设置根目录 `website`、Node.js 24、构建命令 `npm run build`、部署命令 `npx wrangler@4.152.0 deploy --config wrangler.worker.jsonc`。按需修改配置中的 `name`。

Pages Functions 和独立 Workers 共用同一个下载解析器，是两种独立托管方式，任选其一即可，无需同时部署。若未来增加按需服务端渲染，再引入对应 Astro adapter 并调整输出方式。部署凭据只配置在 Cloudflare 账户或 CI Secrets 中。

官方参考：[Astro 部署到 Cloudflare](https://docs.astro.build/en/guides/deploy/cloudflare/)、[Workers Static Assets](https://developers.cloudflare.com/workers/static-assets/get-started/)。

## 自动下载入口与缓存

点击「下载最新开发构建」访问 `GET /download`（也支持 `HEAD`）：

1. 调用 `GET /repos/fython/wheelplay/actions/workflows/debug-build.yml/runs?branch=main&status=success&per_page=1`，选择该工作流 main 分支最新成功运行。
2. 在该 run 的 artifacts API 中按 `name=wheelplay-debug-apk` 查询，最多读取 100 条返回记录，并检查精确名称、ID、`expired` 和 `expires_at`。
3. 返回无响应体的 `302`，例如 `Location: https://github.com/fython/wheelplay/actions/runs/39951112470/artifacts/12501440441`。只查询元数据，不请求 `archive_download_url`，不代理、解压或转发 ZIP/APK 文件。GitHub 下载仍可能要求用户登录。

最新成功运行没有有效产物时返回 `404` 和工作流列表地址，不回退到旧运行。GitHub API 超时、限流或返回异常时返回 `503` 和 `Retry-After`；单次 API 请求超时为 5 秒，不进行即时重试。错误响应不暴露 GitHub 原始错误或 token。

缓存策略：

- **成功结果：5 分钟。** 缓存有效期不会超过产物的 `expires_at`，因此新成功构建可能在 5 分钟后被发现，提前删除的产物也可能暂时仍被缓存。
- **两层缓存。** 优先返回同一 isolate 的内存记录，其次读取 `caches.default` 的小型元数据结果。缓存命中不请求 GitHub，也不重复解析大体积 API 响应。
- **合并并发。** 同一 isolate 同一时刻只执行一次解析。普通冷查询最多请求 GitHub 两次，不扫描历史构建，不循环分页。
- **失败缓存。** 无构建／无产物缓存 60 秒；API 错误至少缓存 60 秒，并按 `Retry-After` 或已耗尽的速率限制重置时间退避，最长 1 小时。失败也缓存到边缘，减少不同 isolate 对同一错误的重复查询。
- **统一缓存键。** `/download` 的查询参数、Cookie、请求缓存头和 GET／HEAD 不影响缓存键，避免通过随机参数反复查询 API。浏览器收到 `Cache-Control: no-store`，缓存有效期由 Worker 控制。
- **范围限制。** Cloudflare Cache API 按数据中心保存，内存和并发合并按 isolate 生效；不是全球共享锁，也不保证所有节点总共只查询两次。不同边缘节点首次访问、缓存淘汰或实例重建仍可能回源。

网络等待一般不计入 Worker CPU time；这里主要减少脚本执行、重复 JSON 解析和 API 请求次数。没有做线上 CPU 基准测试，也不承诺固定 CPU 数值。

公开仓库可匿名查询。生产环境建议配置只对 `fython/wheelplay` 授予 **Actions: Read-only** 的 GitHub fine-grained token，以提高 API 限额：

```sh
# 独立 Worker
npx wrangler@4.152.0 secret put GITHUB_TOKEN --config website/wrangler.worker.jsonc
# Pages 项目
npx wrangler@4.152.0 pages secret put GITHUB_TOKEN --project-name wheelplay-website
```

也可在 Cloudflare 项目设置中添加加密的 `GITHUB_TOKEN`。该 secret 只发送到固定 GitHub API 地址，不进入 HTML、重定向 URL 或缓存。本地测试可在忽略的 `website/.dev.vars` 中配置；不要提交 token。静态构建不需要此 token。

缓存与 API 参考：[Cloudflare Cache API](https://developers.cloudflare.com/workers/runtime-apis/cache/)、[GitHub workflow runs](https://docs.github.com/en/rest/actions/workflow-runs#list-workflow-runs-for-a-workflow)、[GitHub run artifacts](https://docs.github.com/en/rest/actions/artifacts#list-workflow-run-artifacts)。

## 内容维护

- **文档页面使用 Markdown。** 使用指南位于 `src/pages/docs/index.md`，常见问题位于 `src/pages/docs/faq.md`；正文、标题、说明均在 Markdown 中维护，不在 Astro 布局中编写文档正文。首页仍由产品展示组件组成。
- 新增文档时，在 `src/pages/docs/` 添加 `.md` 文件，frontmatter 填写 `layout: ../../layouts/DocsLayout.astro`、`title` 和 `description`；文件名决定路径。在 `DocsLayout.astro` 的 `pages` 中添加导航入口即可。正文从 `##` 开始，页面主标题由布局读取 `title` 生成，二级标题自动生成本页目录。
- 使用标准 Markdown 编写段落、列表、链接、表格和代码块，无需内嵌 HTML。代码块关闭内联样式高亮以兼容 CSP；导航、主题与响应式排版由布局负责。Astro 原生支持 Markdown，无需新增运行时依赖。
- `GettingStarted.astro` 中的下载入口为 `/download`，仓库、工作流和产物名集中定义于 `worker/download.ts`。更换下载来源时同步修改解析器、缓存键版本、测试与页面说明。
- `ExperiencePreview.astro` 中的导航、音乐、配对是明确标注的示意，不是真实 CarPlay 截图，不播放音频，也不连接设备。
- 页面默认中文，并提供英文项目文档入口。
- 绿色 App 图标与 Material Symbols 许可证沿用仓库文件，来源见 [`docs/APP_ICON.md`](../docs/APP_ICON.md)。
- 部署域名确定后，可在 Astro 配置中增加 `site`，并在共享布局中补充 canonical 和分享图片；目前不写入虚构域名。
- 添加分析或外部 API 时，需要同步维护 CSP，不能直接关闭策略。

## 验证

网站 CI 使用 Node.js 24 和锁文件安装依赖，依次执行 Astro 检查、静态构建、产物测试与下载解析器测试，然后上传 `dist/`（该产物仅含静态页面，部署下载功能还需要仓库中的 Worker／Functions 源码）。产物测试确认首页、404 和文档页面使用有效的本地外部资源、文档目录指向实际标题、404 带 `noindex`，以及共享图标／许可证／响应头没有丢失。

浏览器回归检查应覆盖桌面和手机布局、三个标签页、方向键与 Home/End、主题保存、禁止存储、FAQ、无 JavaScript 阅读，以及 Cloudflare 本地运行器下的 CSP 和 404 响应。
