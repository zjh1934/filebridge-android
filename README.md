# FileBridge · 打开就能用的手机文件管理器

> **给不折腾的人看这一段就够了**
>
> 装好 APP，**打开直接用**。不需要电脑、不需要路由器设置、不需要知道 `192.168.1.1` 是什么、不需要填任何地址、不需要 Token、不需要联网。
> 文件存在手机自己身上，用「导入文件」把别处的文件放进来，用「分享」发出去。
> 之前截图里那个「服务地址」页面已经默认收起来了，不会配就永远不用碰。

- **源码仓库**：https://github.com/zjh1934/filebridge-android
- **最新安装包**：https://github.com/zjh1934/filebridge-android/releases/tag/v1.1
- **签名**：从 v1.1 起固定同一张证书，之后每个版本直接覆盖安装，不用卸载（仅从旧 debug 版升上来需卸载一次）
- **版本规则**：`FileBridge-v1.1.apk` → v1.2 → v1.3 …，每出一次包 +0.1
- APP 内置 **一键导出 Markdown 开发者日志**，记录运行详细状态，方便排查 bug

---

## 1. 普通用户：只用这三步

1. 到 Release 页面下载 `FileBridge-v1.1.apk`
2. 安装（手机提示「未知来源」时允许一次）
3. 打开 → 直接就是文件列表

界面上这些按钮就够用了：

| 按钮 | 干什么 |
| --- | --- |
| 导入文件 | 从手机里挑一个文件，复制进 APP |
| 新建文件 / 新建目录 | 直接在 APP 里建 |
| 打开 / 编辑 / 分享 / 改名 / 删除 | 对单个文件操作 |
| 回收站 | 删错的东西在这里恢复 |
| 导出开发者日志 | 出问题点它，把生成的 `.md` 发给我 |

顶栏「模式」里还有个**云网关**，是给以后有常开电脑/云主机的人准备的可选功能，现在不用管。

---

## 2. 为什么之前那个地址连不上

上一版填的 `http://10.0.2.2:8765` 是 **Android 模拟器专用地址**，真机上它指向手机自己，所以 15 秒超时。

而「用 GitHub 仓库中继」也行不通：GitHub Pages 只发静态文件，Actions 跑完就销毁，都不能常驻监听端口对外提供 API。仓库已经把能干的活干完了（存代码 + 打包 APK），实时文件读写必须有常驻进程。

所以这一版直接**把网关从必填项里拿掉**：APP 自带本地文件管理，零依赖。云端文件那部分保留代码，等你有服务器了再开。

---

## 3. 目录结构

```
filebridge/
├── .github/
│   ├── workflows/build-apk.yml    # CI：固定证书签名 + 打包 + 发 Release
│   └── RELEASE_BODY.md            # Release 页面正文
├── scripts/
│   ├── start_server.sh           # （可选）云网关服务启动
│   ├── sync_workspace.py         # （可选）工作区 → 持久区
│   ├── verify.py                 # 服务端 19 项端到端验证
│   └── check_sources.py          # 源码静态自检
├── server/app.py                 # （可选）文件网关服务
├── web/index.html                # （可选）Web 控制台，也带导出 MD 日志
├── android/                      # 安卓 APP 工程
│   ├── app/src/main/java/com/example/filebridge/
│   │   ├── LocalStore.kt         # ★ 零配置本机文件管理（默认）
│   │   ├── DevLog.kt             # 日志采集 / 脱敏 / Markdown 导出
│   │   ├── Api.kt / ApiClient.kt / FileRepository.kt  # 可选云网关
│   │   └── MainActivity.kt       # 双模式界面，默认本机
│   └── app/src/main/             # Manifest / FileProvider / 主题
├── VERIFY.md                     # 服务端实跑验证
├── VERIFY_STATIC.md              # 源码静态自检
└── README.md
```

---

## 4. 固定签名是怎么做的

- 生成一张自签名证书（RSA 2048，30 年），**本体不进公开仓库**，用 GitHub API 加密写进仓库的 Actions Secret `FILEBRIDGE_KEYSTORE_B64`。
- CI 每次构建时把 Secret 解码成 `android/app/filebridge.jks`，Gradle `signingConfigs.fixed` 用它签 release 包。
- 因为证书是同一张、applicationId 固定 `com.example.filebridge`、versionCode 递增，所以后续版本可以**直接覆盖安装**，数据和文件都保留。
- CI 里有一步 `apksigner verify --print-certs`，每次都会核对签名有效。

本地想自己打包：把 jks 放到 `android/app/filebridge.jks` 再跑 `gradle :app:assembleRelease`；没有 jks 时会构建出未签名包（仅本地调试用）。

> 唯一一次需要卸载：之前 `v0.1.0-debug` 用的是 CI 临时生成的 debug 证书，和固定证书不同，升到 v1.1 要卸一次。之后永远不用。

---

## 5. 版本与包名规则

CI 文件顶部三行控制一切：

```yaml
APP_VERSION: "1.1"
APK_NAME: "FileBridge-v1.1.apk"
RELEASE_TAG: "v1.1"
```

每出一个新版本：三行一起 +0.1，`build.gradle.kts` 里 `versionName` 同步、`versionCode` +1。产物文件名、Release 标签、页面标题全部跟着变，不会再出现「每回名字都不一样」的情况。

---

## 6. 一键导出 Markdown 开发者日志

**入口**：顶栏「日志」或工具栏「导出开发者日志」→「一键导出 MD」/「另存为…」。

记录内容：生命周期、目录导航、**每个网络请求的路径/状态码/耗时**、文件读写字节数、异常完整堆栈、设备与系统信息、服务地址。

导出 MD 含 6 个区块：导出信息 / 运行摘要 / 最近状态 / 完整时间线 / 网络请求明细 / 异常堆栈。排查先看「运行摘要」里的 ERROR 与 HTTP 失败数，再按时间线定位，最后翻堆栈。

**脱敏**：`sanitize()` 把 `sig=`、`token=`、`x-api-key`、`Bearer`、`ghp_/github_pat_` 一律换成 `***`，导出信息里 Token 固定写 `[REDACTED]`。日志可以放心转发。

磁盘侧还有 `filesDir/devlog/devlog.ndjson` 追加写，跨启动保留，超 1.5 MB 自动轮转。Web 控制台同样有「导出日志」。

---

## 7. 云网关（可选，看不懂可整段跳过）

只有当你有一台**常开**的电脑 / NAS / 云主机时才需要：

1. 把 `server/`、`web/` 拷过去，`pip install fastapi uvicorn python-multipart`
2. 设 `FILEBRIDGE_BASE`（持久目录）、`FILEBRIDGE_TOKEN`、`FILEBRIDGE_SECRET`
3. 起服务，同一 WiFi 下手机填 `http://电脑局域网IP:8765`；要公网就加一层 Cloudflare Tunnel 之类
4. APP 里「模式 → 云网关 → 设置」填地址和 Token

服务端 API 与核心技术点（id 化路径防穿越、sha256 ETag 乐观锁、HMAC 预签名、软删回收站、flock 原子索引）保持不变，详见 `VERIFY.md` 的 19 项实跑记录。

> GitHub Pages 只发静态文件、Actions 是临时任务，**不能**当这个常驻网关用。

---

## 8. 已知限制

- 本机文件存在 APP 私有目录里，卸载 APP 会一起删掉；重要文件请用「分享 / 另存为」备份到手机公共目录。
- 大文件分片、断点续传未实现。
- 云网关仍是单 Token、无多用户隔离。
- 开发者日志只存本地，不自动上传；要排查就用「一键导出 MD」发出来。

---

## 9. 跨对话持久性怎么验证

`scripts/sync_workspace.py --init` 会写 `/data/user_persistent_data/filebridge/CROSS_SESSION_MARKER.txt`。
下次新开对话让我读这个路径，文件还在、创建时间不变，就说明该目录确实跨对话保留。
