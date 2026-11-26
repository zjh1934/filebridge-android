# FileBridge：把对话里的文件接到安卓 APP

一套最小可跑的「持久目录 ↔ REST 网关 ↔ 手机端」实现，含服务端、安卓源码、Web 控制台、同步脚本与实跑验证。

- **源码仓库**：https://github.com/zjh1934/filebridge-android
- **安装包（Release 页面）**：https://github.com/zjh1934/filebridge-android/releases/tag/v0.1.0-debug
- **CI**：push 到 `main` 自动用 GitHub Actions 编译 debug APK 并更新 Release，附件 `FileBridge-debug.apk`。
- APP 内置 **一键导出 Markdown 开发者日志**，记录运行详细状态，方便排查 bug。

---

## 0. 探查结论（为什么要这么设计）

- 沙盒里确实有跨对话持久区：`/data/user_persistent_data`，里面有历史条目，可写。
- `/data/workspace`（对话工作区）与它挂在**同一个可读写卷**上，所以对话产物能直接 `cp` 过去。
- 但这两个目录都在云端隔离环境里，**手机拿不到路径，也没有对外暴露的文件服务**。
- 结论：不能做「目录映射」，只能做一层 **id 化的文件网关**。客户端永远只传 `file_id`，不传绝对路径。

```
对话里生成文件 (/data/workspace)
   │  POST /sync 或 scripts/sync_workspace.py
   ▼
/data/user_persistent_data/filebridge/files   ← 跨对话持久区
   │  index.json（id → 相对路径映射）
   ▼
FastAPI 文件网关 (HTTPS + x-api-key)
   │  REST / 预签名 URL
   ▼
安卓 APP（Kotlin + Compose） / Web 控制台
```

---

## 1. 目录结构

```
filebridge/
├── server/app.py                 # 文件网关服务（全部核心逻辑）
├── .github/
│   ├── workflows/build-apk.yml    # CI：编译 debug APK + 发布 Release
│   └── RELEASE_BODY.md            # Release 页面正文
├── scripts/
│   ├── start_server.sh           # 启动
│   ├── sync_workspace.py         # 工作区 → 持久区（不依赖服务）
│   ├── verify.py                 # 服务端端到端验证，生成 VERIFY.md
│   └── check_sources.py          # 源码静态自检，生成 VERIFY_STATIC.md
├── web/index.html                # 单文件 Web 控制台（含导出 MD 日志）
├── android/                      # 原生 APP 完整源码工程
│   ├── app/src/main/java/com/example/filebridge/
│   │   ├── Api.kt                # Retrofit 接口 + 数据模型
│   │   ├── ApiClient.kt          # OkHttp + 全量 HTTP 日志拦截器
│   │   ├── DevLog.kt             # 日志采集 / 脱敏 / Markdown 导出
│   │   ├── FileRepository.kt     # 下载 / FileProvider / 乐观锁回写
│   │   └── MainActivity.kt       # Compose 界面 + 日志面板
│   └── app/src/main/             # Manifest / FileProvider 路径 / 主题
├── VERIFY.md                     # 服务端实跑验证记录
├── VERIFY_STATIC.md              # 源码静态自检记录
└── README.md
```

---

## 2. 在沙盒里跑起来

```bash
cd /data/workspace/filebridge

# ① 初始化持久目录 + 跨对话标记文件
python3 scripts/sync_workspace.py --init

# ② 启动服务（依赖：fastapi uvicorn python-multipart）
bash scripts/start_server.sh                 # http://0.0.0.0:8765

# ③ 另开一个终端跑验证
python3 scripts/verify.py                     # 服务端实跑，结果写入 VERIFY.md
python3 scripts/check_sources.py              # 源码静态自检，结果写入 VERIFY_STATIC.md
```

浏览器打开 `http://<host>:8765`，在顶部填服务地址和 Token，就能用 Web 控制台列目录、上传、打开、在线编辑、改名、软删/恢复。

默认 Token：`demo-token-change-me`，**生产必须改**（环境变量 `FILEBRIDGE_TOKEN` / `FILEBRIDGE_SECRET`）。

---

## 3. 让安卓 APP 真正能连上（必做）

沙盒里的服务**不常驻、没有公网固定入口**，所以源码现在还连不到沙盒。要真机使用：

1. 把 `server/`、`web/` 拷到你有固定地址的云主机 / NAS / 家里服务器；
2. `pip install fastapi uvicorn python-multipart`；
3. 设好 `FILEBRIDGE_BASE`（持久目录）、`FILEBRIDGE_TOKEN`、`FILEBRIDGE_SECRET`；
4. 用 Caddy / Nginx 套一层 HTTPS（安卓默认禁明文，Manifest 里 `usesCleartextTraffic` 只给本地调试）；
5. APP 设置页填 `https://你的域名`，Token 填同一个值；
6. 对话侧：生成文件后调 `POST /sync`，或把持久区同步一份到对象存储做长期兜底。

安卓工程用 **Android Studio（AGP 8.5.2 / Kotlin 1.9.24）** 打开 `android/` 即可编译。
模拟器默认地址 `http://10.0.2.2:8765`，真机填局域网 IP 或公网域名。

### CI 与 Release

沙盒里没有 Android SDK，所以编译交给 GitHub Actions：`.github/workflows/build-apk.yml` 在 `ubuntu-latest` 上装 JDK 17 / Android SDK 34 / Gradle 8.9，跑 `gradle :app:assembleDebug`，把 `app-debug.apk` 重命名为 `FileBridge-debug.apk`，同时上传为构建产物并发布到 Release `v0.1.0-debug`。

- Release 页面：https://github.com/zjh1934/filebridge-android/releases/tag/v0.1.0-debug
- APK 直链：https://github.com/zjh1934/filebridge-android/releases/download/v0.1.0-debug/FileBridge-debug.apk
- 也可在 Actions 页面手动 `Run workflow` 重新打包。

> 服务端与 Web 控制台对应的接口已全部实跑通过（见 `VERIFY.md`）；客户端源码由 CI 实际编译，结果以 Release 产物为准，另有静态自检记录 `VERIFY_STATIC.md`。

---

## 4. API 一览

| 方法 / 路径 | 作用 | 关键设计 |
| --- | --- | --- |
| `GET /files?parent=` | 列目录 | 返回 dirs + files，parent 是相对路径 |
| `POST /files` | 上传 | multipart，文件名清洗，同名自动加后缀 |
| `POST /dirs` | 建目录 | 目录也登记进索引，空目录可见 |
| `GET /files/{id}` | 元信息 | 只认 id |
| `GET /files/{id}/content` | 下载 | 支持预签名免密钥 |
| `GET /files/{id}/open` | 取短时效 URL | HMAC 签名，默认 300 秒 |
| `PUT /files/{id}` | 覆盖写 | `If-Match: sha256`，冲突返回 409 |
| `PATCH /files/{id}` | 改名 / 移动 | JSON `{name, parent}` |
| `DELETE /files/{id}?purge=` | 删除 | 默认进回收站，purge=1 才真删 |
| `GET /trash` · `POST /trash/{id}/restore` · `DELETE /trash/{id}` | 回收站 | 恢复 / 彻底删 |
| `POST /sync` | 同步工作区产物 | 对话产物 → 持久区 |

---

## 5. 核心技术点（对应你问的「怎么实现最好」）

1. **id 化路径 + 防穿越**：`index.json` 存 `id → rel`，所有落盘前 `_safe(rel).resolve()` 并校验仍在 ROOT 内；`parent` 显式拒绝 `..`，文件名用 `Path(name).name` 清洗。这是最容易被打穿的一层。
2. **并发安全**：`index.json` 用「临时文件 + `os.replace` 原子替换」，配合同进程 `RLock` 与跨进程 `flock`，服务和同步脚本可同时写。
3. **编辑一致性**：sha256 当 ETag，覆盖写带 `If-Match`，多端同时改不会静默互相覆盖（409 让客户端刷新重试）。
4. **手机端「打开」**：Android 10+ 分区存储下不能碰外部路径 → 下载到 `filesDir/downloads` → `FileProvider` 生成 `content://` URI → `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION` 交给系统应用；回写用「覆盖」按钮或 APP 内置文本编辑。
5. **密钥不下放**：预签名 URL 用 HMAC + 过期时间，系统应用 / 浏览器拿的是短时效链接，不是长期 Token。
6. **删除可后悔**：默认软删进 `.trash`，另存回收站索引，可恢复。
7. **生产端与消费端解耦**：对话只负责把文件推进持久区，APP 只跟网关对话，两边不共享文件系统。

---

## 6. 一键导出 Markdown 开发者日志

**入口**：安卓 APP 顶栏「日志」或工具栏「导出日志」→「一键导出 MD」；Web 控制台工具栏「导出日志」。

### 记录什么

| 类别 | 具体内容 |
| --- | --- |
| 生命周期 | 冷启动、界面创建/销毁、连接配置变更（Token 不记） |
| 导航 | 进入目录、返回上级、手动刷新 |
| 网络 | 每个请求的 method / 路径 / 状态码 / 耗时 / 异常（OkHttp 拦截器自动采） |
| 文件 | 读取 Uri、字节数、下载落盘路径、覆盖写、编辑回写字符数 |
| 异常 | 完整 `stackTraceToString`，单独成块，不用截图 |
| 环境 | 制造商、型号、Android 版本、SDK、语言、时区、ABI、服务地址 |

### 怎么导出

- **一键导出 MD**：生成 `filesDir/exports/FileBridge_DevLog_yyyyMMdd_HHmmss.md`，直接拉起系统分享（`ACTION_SEND` + `content://` 临时授权）。
- **另存为…**：`ACTION_CREATE_DOCUMENT`（SAF），用系统文件管理器保存到任意位置。
- **清空日志**：一键清空内存缓冲与磁盘文件。
- 磁盘侧还有 `filesDir/devlog/devlog.ndjson` 追加写，跨 APP 启动保留；超过 1.5 MB 自动轮转。

导出的 Markdown 含 6 个区块：导出信息 / 运行摘要 / 最近状态 / 完整时间线 / 网络请求明细 / 异常堆栈。排查时先看「运行摘要」里 ERROR 与 HTTP 失败数，再按时间线定位，最后翻堆栈。

### 脱敏（重要）

`sanitize()` 会把 `sig=`、`token=`、`x-api-key`、`Bearer`、`ghp_/github_pat_` 等一律替换为 `***`；导出信息里 API Token 固定写 `[REDACTED]`。**日志可以放心转发**，不会把密钥带出去。

---

## 7. 已知限制 / 生产化待补

- 大文件分片、断点续传未实现，目前是整体上传 / 整体 PUT。
- 单 Token、无多用户租户隔离；要多人用需加账号体系与命名空间。
- 目录暂不支持移动（子项 `rel` 需级联更新）。
- 沙盒持久区仍在平台容器内，长期稳定建议双写到自己的对象存储 + 元数据 DB。
- 感知新文件靠 `POST /sync` 或 APP 下拉刷新，未做 WebSocket / 推送。
- 沙盒内无 Android SDK，客户端编译由 GitHub Actions 完成；本地要编译请用 Android Studio 打开 `android/`。
- 开发者日志目前只存本地、不自动上传；要远程排查就用「一键导出 MD」把文件发出来。

---

## 8. 跨对话持久性怎么验证

`scripts/sync_workspace.py --init` 会写：

```
/data/user_persistent_data/filebridge/CROSS_SESSION_MARKER.txt
```

**下次新开一个对话，让我读取这个路径**：文件还在、创建时间不变，就证明该目录确实跨对话保留。也可以直接让我列 `/data/user_persistent_data/filebridge/files`，看本次上传/验证留下的文件是否还在。
