# FileBridge · 在手机上查看和编辑沙盒里的文件

> **这个软件不是手机本地文件管理器。** 系统自带的比手搓的强 100 倍，没必要。
> 它唯一的目标是：让你在手机上**看到沙盒里的文件、打开、编辑、保存、删除**，改完还能回到沙盒。

- **源码仓库**：https://github.com/zjh1934/filebridge-android
- **最新安装包**：https://github.com/zjh1934/filebridge-android/releases/tag/v1.2
- **签名**：v1.1 起固定同一张证书，后续版本直接覆盖安装
- **版本规则**：`FileBridge-v1.2.apk` → v1.3 → v1.4 …，每出一次包 +0.1
- APP 内置**一键导出 Markdown 开发者日志**

---

## 1. 为什么手机不能直接连沙盒

沙盒是云端隔离环境：`/data/workspace`、`/data/user_persistent_data` 都在容器里，手机拿不到路径，平台也没有对外暴露文件服务或入站端口。所以「填个 IP 连过去」这条路走不通 —— 上一版截图里 `10.0.2.2:8765` 超时就是这个原因（那还是模拟器专用地址）。

你问「能不能用 GitHub 仓库中继」，**这个直觉是对的**。准确说法是：

- GitHub **不能**当常驻网关（Pages 只发静态页，Actions 跑完就销毁，不能监听端口）
- 但 GitHub 的 **Contents API 本身就是 7×24 在线的文件读写接口**，可以当**摆渡中继 / 中转仓库**

所以这版用「仓库当中转站」，不需要电脑、路由器、光猫 IP、公网入口或常驻服务器。

---

## 2. 数据怎么走

```
沙盒持久区  /data/user_persistent_data/filebridge/sandbox-files/
     │  scripts/sync_to_github.py   （我在对话里执行）
     ▼
GitHub 仓库 zjh1934/filebridge-android 的 sandbox-files/ 目录
     │  手机 APP 通过 Contents API 直接列目录 / 读 / 写 / 删
     ▼
安卓 APP「沙盒文件」模式（默认，打开就是它）
     │  编辑保存 = PUT 回仓库
     ▼
下次开对话说「同步沙盒文件」→ scripts/pull_from_github.py 回灌持久区
```

**查看和编辑是即时的**（写完仓库立刻有）；**回到沙盒**要等下次对话跑一次回灌脚本。对个人使用够用，但不是实时双向同步，这点如实说明。

---

## 3. 用户怎么用（就三步）

1. 到 Release 页下载 `FileBridge-v1.2.apk`，安装
2. 打开 → 顶栏**「设置」** → 把令牌**粘一次** → 点「保存，然后就能看到沙盒文件」
3. 之后打开就能看到沙盒文件列表，点「编辑」改完点「保存到沙盒」

仓库地址、目录、分支全部预置在 APP 里，**不用填 IP、不用配路由器、不用懂网络、不用再填第二次令牌**。顶栏「模式」里还有「本机文件」「云网关」两个备用项，默认那个「沙盒文件」才是对的。

---

## 4. 隐私边界（重要）

`filebridge-android` 是**公开仓库**，`sandbox-files/` 里的内容别人也能看到。

- 只同步专门的 `sandbox-files/` 目录
- **不碰** `/data/user_persistent_data` 下其它私人资料（如 `lovers`、`听本`、`林晚`）
- 想把沙盒里某个文件放到手机上编辑，跟我说一句「把 XXX 放进沙盒文件」，我同步过去
- 别往这个目录放敏感内容

手机上的令牌只存在 APP 私有 `SharedPreferences` 里，不写进 APK、不进仓库、导出日志时自动打码。

> 那个 PAT 权限范围很大（`repo`、`workflow`、`delete_repo` …），又在聊天里明文出现过。建议抽空换成**只授权这一个仓库 Contents 读写**的细粒度令牌，然后去设置里重新粘一次即可。

---

## 5. 目录结构

```
filebridge/
├── .github/
│   ├── workflows/build-apk.yml    # CI：固定签名 + 打包 + 发 Release
│   └── RELEASE_BODY.md            # Release 页面正文
├── scripts/
│   ├── sync_to_github.py          # ★ 沙盒 → 仓库（摆渡出去）
│   ├── pull_from_github.py        # ★ 仓库 → 沙盒（回灌回来）
│   ├── verify.py                  # 服务端 19 项端到端验证
│   ├── check_sources.py           # 源码静态自检
│   ├── start_server.sh            # （可选）云网关
│   └── sync_workspace.py          # （可选）工作区 → 持久区
├── server/app.py                  # （可选）文件网关服务
├── web/index.html                 # （可选）Web 控制台
├── sandbox-files/                 # ★ 中转目录（推到仓库同名目录）
├── android/app/src/main/java/com/example/filebridge/
│   ├── GitHubStore.kt             # ★ GitHub Contents API 后端（默认）
│   ├── LocalStore.kt              # 本机文件（备用）
│   ├── DevLog.kt                  # 日志采集 / 脱敏 / MD 导出
│   ├── Api.kt / ApiClient.kt / FileRepository.kt   # 云网关（备用）
│   └── MainActivity.kt            # 三模式界面，默认「沙盒文件」
├── VERIFY.md / VERIFY_STATIC.md
└── README.md
```

---

## 6. 沙盒侧同步命令

在对话里让我跑，或你自己有 Python 环境时：

```bash
export FILEBRIDGE_GH_TOKEN='你的令牌'

# 把沙盒文件推到仓库，手机就能看到
python3 scripts/sync_to_github.py

# 把手机上改过的文件拉回沙盒持久区
python3 scripts/pull_from_github.py
```

两个脚本都带 `[skip ci]`，CI 也配了 `paths-ignore: sandbox-files/**`，所以同步文件不会触发重新打包。

---

## 7. 固定签名与版本规矩

- 自签名证书（RSA 2048，30 年）本体**不进公开仓库**，加密存在 Actions Secret `FILEBRIDGE_KEYSTORE_B64`，CI 构建时解码使用；`apksigner verify --print-certs` 每轮校验。
- applicationId 固定 `com.example.filebridge`，versionCode 递增 → **v1.2 直接覆盖 v1.1**，不卸载、不丢数据。
- CI 顶部三行控制一切：

```yaml
APP_VERSION: "1.2"
APK_NAME: "FileBridge-v1.2.apk"
RELEASE_TAG: "v1.2"
```

每出一个版本三行一起 +0.1，`build.gradle.kts` 的 `versionName` 同步、`versionCode` +1。

---

## 8. 一键导出 Markdown 开发者日志

顶栏「日志」或「导出开发者日志」→「一键导出 MD」/「另存为…」。

记录：生命周期、目录导航、**每次 GitHub 请求的路径/状态码/耗时**、文件读写字节数、异常完整堆栈、设备与系统信息。导出 MD 含导出信息 / 运行摘要 / 最近状态 / 完整时间线 / 网络请求明细 / 异常堆栈。

`sanitize()` 把 `sig=`、`token=`、`x-api-key`、`Bearer`、`ghp_/github_pat_` 一律打码，Token 固定写 `[REDACTED]`，日志可放心转发排查。

---

## 9. 已知限制

- **不是实时双向**：手机改完仓库即时生效，回灌沙盒要下次对话跑脚本。
- Contents API 单文件上限 100 MB，实际建议放文本 / 文档 / 代码类；超大二进制不建议走这条路。
- 公开仓库 = 中转内容公开，见第 4 节。
- 云网关仍是单 Token、无多用户隔离；本机模式只是备用。
- v1.1 曾把默认做成本机文件管理器，方向错误，v1.2 已纠正为默认「沙盒文件」。

---

## 10. 跨对话持久性验证

`scripts/sync_workspace.py --init` 会写 `/data/user_persistent_data/filebridge/CROSS_SESSION_MARKER.txt`。
下次新开对话让我读这个路径，文件还在、创建时间不变，就说明持久区确实跨对话保留。
