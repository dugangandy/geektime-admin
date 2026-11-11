# 极客时间离线资料库

把本地已下载的极客时间课程，变成一个带口令门禁的网站：PC / 手机浏览器可浏览分类总目录、离线阅读器、正文、音频与 PDF；另配一个原生 Android 客户端。

核心能力：

- **动态首页**：按课程子目录自动收集分类、作者、封面、讲数，与 115 门权威清单合并；本地缺失的课程显示「敬请期待」。
- **按需构建阅读器**：首次点击「待生成」课程时后台构建该门 `reader/`，期间显示「生成中」占位页，完成后自动跳转。
- **静态副本**：服务端动态渲染，同时节流落盘一份 `index.html`，可直接 `file://` 双击浏览。
- **口令门禁**：HMAC 签名会话 Cookie、防爆破锁定、敏感文件屏蔽、审计日志；纯 Python 标准库，无 Web 框架。
- **手机端**：`/api/*` JSON API + Kotlin 客户端（后台播放、进度、统计）。
- **课程目录可外置**：各门课程文件夹可放在任意磁盘/目录，启动时用 `--data-dir` 指定；网页与手机端的访问地址不变。

> 本仓库**不包含任何课程数据**，只有程序代码、模板、清单与文档。课程正文、音频、PDF、封面、官网存档 `catalog.html` 均需自备，见「数据准备」。
>
> 课程数据目录默认就是本仓库根目录；用 `--data-dir` 或环境变量 `GK_DATA_DIR` 指向外部目录时，那里**只放各门课程的子目录**，`catalog.html`、封面、清单、模板、`auth.json`、静态首页仍留在本仓库。未指定时行为与旧版完全一致。

## 目录结构

```
极客时间/
├── serve.py                    # 静态服务 + 口令门禁 + 路由（纯标准库）
├── site_generator.py           # 首页数据/缓存/静态副本 + 按需构建队列
├── mobile_api.py               # 手机端 /api/* JSON API（纯标准库）
├── gen_html.py                 # 历史正文下载/生成脚本，服务运行时不依赖
├── serve.bat                   # Windows 一键：本地服务 + SSH 反向隧道（公网入口）
├── serve-lan.bat               # Windows 一键：监听 0.0.0.0 供局域网访问
├── serve.command               # macOS 一键：本地服务 + SSH 隧道（断线自动重连）
├── package.json                # Android 打包 npm scripts
├── test_autobuild.py           # 首页生成 / 构建队列 单测
├── test_serve_auth.py          # 鉴权 / 路由 单测
├── test_mobile_api.py          # 手机端 API 单测
├── .gitignore                  # 白名单：只放行程序代码/文档/模板/清单/测试
├── _导航模版/
│   ├── build_catalog.py        # 首页数据层 + 渲染层（服务端复用，也可 CLI 直接跑）
│   ├── catalog_template.html   # 首页模板（样式 + 筛选 JS）
│   ├── course_manifest.json    # 115 门权威清单（作者/头衔/封面/讲数/学习人数）
│   ├── build_nav.py            # 导航页生成器
│   └── template.html           # 页面模板
├── _阅读器模板/
│   ├── build.py                # 单门课程 → reader/ 构建器
│   ├── build_all.sh            # 批量构建（可传课程名过滤）
│   ├── index.html, assets/     # 阅读器外壳与样式脚本
│   └── migrate_pages.py, relink_catalog.py, sync_ui.py   # 页面迁移 / 目录重链 / UI 同步
├── android/                    # Kotlin 客户端与 Gradle 工程
│   ├── app/src/main/           # Kotlin 源码、资源、article.css
│   ├── tools/                  # build-apk.js、bump-version.js
│   └── version.properties      # 版本号唯一来源
│
├── <课程名>/                   # ← 课程数据，不入库（默认在此，可用 --data-dir 外置）
│   ├── html/ 或 章节目录/       # 正文 HTML / Markdown
│   ├── mp3/、音频/、pdf/        # 附件（目录名自动识别）
│   ├── course.json             # 可选：覆写标题/作者/分类/方向/封面
│   └── reader/                 # 构建产物，不入库
├── assets/covers/              # 课程封面，不入库（CLI 可从官网下载）
├── catalog.html                # 官网课程列表存档，可选、不入库
├── index.html                  # 服务自动落盘的静态首页副本，不入库
├── auth.json                   # 口令哈希与会话密钥，运行时生成、绝不入库
├── auth.log                    # 安全审计日志，不入库
└── .mobile/progress.json       # 手机端学习进度，不入库
```

课程目录支持三种布局：分章节目录、扁平 `html/` + 音频/PDF、纯 Markdown（含 `images/` 图片）。详见 `_阅读器模板/build.py` 头部说明。

## 环境依赖

| 组件 | 依赖 |
| --- | --- |
| `serve.py`、`site_generator.py`、`mobile_api.py`、`_导航模版/build_catalog.py` | Python 3，纯标准库 |
| `_阅读器模板/build.py` | `beautifulsoup4` 必需；`mutagen`（音频时长）、`markdown`（md 正文）、`pymupdf`（PDF 页数）可选 |
| `gen_html.py` | `requests` / `loguru` / `jinja2`（仅历史脚本，服务不依赖） |
| Android 客户端 | JDK 17+、Gradle 8.7+、Android SDK platform 34 / build-tools 34.0.0、Node.js、adb |

```bash
pip install beautifulsoup4              # 按需构建阅读器时必需
pip install mutagen markdown pymupdf    # 可选
```

## 数据准备

1. 把课程放到**课程数据目录**下的一级子目录，一个目录一门课，目录名即课程名。默认该目录就是本仓库根目录；也可以放到别的磁盘/目录，启动时指定即可。以 `_` / `.` 开头、`_old` 结尾，以及名为 `assets` 的目录会被自动跳过。

   ```bash
   python serve.py 8888 . --data-dir "D:/极客时间课程"
   ```

   外部目录里**只放各门课程的子目录**（含各自的 `html/`、`mp3/`、`pdf/`、`reader/` 等）；`catalog.html`、`assets/covers/`、`_导航模版/`、`_阅读器模板/`、`auth.json`、`index.html` 等仍在仓库根目录。课程访问地址始终是 `/<课程名>/...`，服务端会把该前缀映射到数据目录，前端与 Android 客户端无需改动。首次点击「待生成」课程时，`reader/` 会构建到数据目录里对应的课程下。目录不存在时启动会明确报错；`--set-password` 等口令操作不依赖课程目录。

2. 可选：放一份 `catalog.html`（官网课程列表存档），用于补作者/头衔/讲数/学习人数等元数据，并为 CLI 提供封面下载地址。缺失时程序仍能运行，只是元数据与封面信息变少。
3. 可选：给单门课加 `course.json` 覆写元数据：

   ```json
   {
     "title": "显示名", "author": "作者", "role": "头衔",
     "category": "分类", "dir": "方向", "cover": "封面.webp"
   }
   ```

4. 可选：跑一次首页生成器，下载封面并生成静态首页：

   ```bash
   python _导航模版/build_catalog.py --dry-run   # 只打印统计，不写文件
   python _导航模版/build_catalog.py            # 刷新 index.html + 下载封面到 assets/covers/
   ```

## 部署

### 1. 首次：设置访问口令

```bash
python serve.py --set-password        # 交互式设置，生成 auth.json
python serve.py --gen-password        # 只打印一个强随机口令
python serve.py --revoke-sessions     # 作废所有会话（不改口令）
```

`auth.json` 含 PBKDF2 哈希与会话密钥，被 `.gitignore` 和 HTTP 门禁双重屏蔽，不要提交。

### 2. 本机访问

```bash
python serve.py 8888 . --bind 127.0.0.1
```

浏览器打开 `http://127.0.0.1:8888/`，登录页为 `/__auth/login`；会话默认空闲 7 天、绝对 30 天。启动时会打印 `home: dynamic (N 门课程) autobuild=on`。若 `_导航模版/build_catalog.py` 不存在，首页会自动回退到静态 `index.html`。

课程数据目录的优先级：`--data-dir` > 环境变量 `GK_DATA_DIR` > 第二个位置参数 `root`（服务根）。不指定时课程与代码同目录，行为不变；两者不同时启动日志会多打印一行 `courses: <数据目录> (external data dir)`。Windows cmd 下可用 `set GK_DATA_DIR=D:\极客时间课程` 设置环境变量。

常用参数：

```bash
python serve.py 8888 . --data-dir "D:/极客时间课程"       # 课程文件夹外置
python serve.py 8888 . --no-autobuild                     # 关闭按需构建
python serve.py 8888 . --build-jobs 8 --build-timeout 3600
python serve.py 8888 . --manifest /path/to/course_manifest.json
python serve.py 8888 . --trust-proxy                     # 前置 nginx 时信任 X-Forwarded-For
python serve.py 8888 . --no-auth                        # 仅调试：关闭全部鉴权
```

### 3. 局域网 / 手机浏览器

双击 `serve-lan.bat`（等价于 `python serve.py 8888 . --bind 0.0.0.0`）。脚本会打印本机 IPv4，手机在同一 Wi-Fi 下访问 `http://<PC-IP>:8888`。需放行 Windows 防火墙的 TCP 8888。课程目录外置时，编辑脚本顶部的 `DATA_DIR` 变量（或设置环境变量 `GK_DATA_DIR`）即可，双击同样生效。

### 4. 公网（SSH 反向隧道）

Windows 双击 `serve.bat`；macOS 首次先执行 `chmod +x serve.command`（若被 Gatekeeper 拦截，再执行 `xattr -d com.apple.quarantine serve.command`），之后双击 `serve.command`。两者都先启动本地 `127.0.0.1:8888`，再用 `ssh -R 8888:127.0.0.1:8888 root@139.198.29.121` 把端口映射到公网，访问 `http://139.198.29.121:8888/`。

- 远端 sshd 需开启 `GatewayPorts yes`；脚本会清理僵死的 8888 监听（`fuser -k`）并复核远端绑定，失败最多重试 3 次，带 `ExitOnForwardFailure=yes` 与 `ServerAliveInterval=30`。
- 两个脚本都复用已有的本地 8888 服务，且公网已可达时不动健康隧道。macOS 版额外支持隧道断线自动重连：`MAX_RETRIES=0 ./serve.command` 不限次数，`RETRY_BASE=5 RETRY_MAX_WAIT=60 ./serve.command` 可调退避；按 Ctrl-C 停止。
- 课程目录外置：`serve.bat` / `serve-lan.bat` 顶部改 `set "DATA_DIR=D:\..."`（纯 ASCII，勿写中文注释）；`serve.command` 顶部改 `COURSE_DIR="/Volumes/.../课程"`，或运行时 `GK_DATA_DIR="..." ./serve.command`。三个脚本都支持 `GK_DATA_DIR` 环境变量，留空则使用脚本所在目录；路径不存在时启动前即报错。
- 课程文件**不上传**到远端，全部由本机服务提供；远端 80 端口是 nginx 默认站，不做反代。
- 隧道下客户端 IP 恒为 `127.0.0.1`，按 IP 的限流退化为全局限流，切勿对回环地址做免登录豁免。
- 明文 HTTP、无 TLS，仅建议个人使用；若前置 nginx 反代，可加 `--trust-proxy`。

### 5. Android 客户端

在项目根目录执行 `npm run dist:apk`，产物为 `out/GeekTimeReader-<版本>-debug.apk`；`adb install -r` 安装后，在 App 里填服务器地址与口令（可先用「连接测试」验证 `/api/ping`）。客户端不内置课程文件，只通过 `mobile_api.py` 的 `/api/*` 接口访问 PC 上的 `serve.py`。构建环境：JDK 17+、Gradle 8.7+、Android SDK platform 34 / build-tools 34.0.0；版本号源为 `android/version.properties`，`npm run bump` 可自增版本。

### 6. 纯离线双击

服务运行时会节流（默认 30 秒）把无构建状态的静态首页写到根目录 `index.html`，用 `file://` 双击即可浏览；该副本不含 `/__build` 链接。

课程目录外置时，静态副本里的课程链接会自动加上指向数据目录的相对前缀（同一盘符）；跨盘符（如 `D:` 数据 + `E:` 代码）会退化为 `file:///` 绝对地址。这类链接绑定了本机目录结构，移动数据目录或换机器后需启动一次服务重新生成 `index.html`；在线访问（含手机端）不受此限制。

## 首页与按需构建

- 数据合并：本地一级课程目录 ∪ `_导航模版/course_manifest.json` 的 115 门清单；本地没有的显示「敬请期待」。
- 元数据优先级：`course.json` > `CATEGORY_MAP` / `catalog.html` > manifest 补空。
- 课程状态：`reader`（直接打开）/ `nav`（旧导航页）/ `pending`（待生成）/ `building`（生成中）/ `failed`（可重试）/ `none`（清单占位）。
- 首次点击「待生成」课程 → `/__build/<课程名>` 触发后台构建 → 占位页轮询 `/__build/status/<课程名>` → 完成后跳转 `reader/index.html`。构建成功后首页缓存自动失效。

## 命令行工具

```bash
# 首页：统计 / 生成
python _导航模版/build_catalog.py --dry-run
python _导航模版/build_catalog.py "E:/.../极客时间" --title "极客时间 · 课程总目录" --out index.html
python _导航模版/build_catalog.py --data-dir "D:/极客时间课程"   # 课程外置，首页仍写到仓库根目录
python _导航模版/build_catalog.py --no-manifest        # 只扫描本地课程目录

# 单门课程构建离线阅读器
python _阅读器模板/build.py "<课程目录>" --title "课程名" --author "作者" --jobs 8

# 批量构建（可传名字过滤）
bash _阅读器模板/build_all.sh
bash _阅读器模板/build_all.sh Redis
```

`build.py` 产物为 `<课程>/reader/` 下的 `index.html`、`assets/`、`data/catalog.js`、`pages/NNN.html`；重复执行幂等，不改动课程正文。

## 测试

```bash
python -m unittest test_autobuild test_serve_auth test_mobile_api test_data_dir -q
```

## 版本控制

代码仓库：`git@github.com:dugangandy/geektime-admin.git`，工作分支 `dev`。

`.gitignore` 采用白名单：`/*` 默认忽略一切，再显式放行程序代码、文档、模板、清单与测试。因此课程目录、`catalog.html`、`index.html`、`assets/covers/`、`auth.json`、`auth.log`、`login.yaml`、`.mobile/`、`.codebuddy/`、Gradle `build/` 产物、备份与截图都不会入库。

> 从仓库 clone 得到的是「空壳」：需要自备课程数据，并执行 `python serve.py --set-password` 生成 `auth.json` 才能运行。
