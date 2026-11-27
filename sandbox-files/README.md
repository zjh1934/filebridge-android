# FileBridge · 在手机上查看和编辑沙盒里的文件

> **不是手机本地文件管理器。** 系统自带的比手搓的强 100 倍。
> 它只做一件事：让你在手机上**看到沙盒里的文件、打开、编辑、保存、删除**，改完还能回到沙盒。

- **源码仓库**：https://github.com/zjh1934/filebridge-android
- **最新安装包**：https://github.com/zjh1934/filebridge-android/releases/tag/v1.4
- **签名**：v1.1 起固定同一张证书，后续版本直接覆盖安装
- **版本规则**：`FileBridge-v1.4.apk` → v1.5 → v1.6 …，每出一次包 +0.1
- APP 内置**一键导出 Markdown 开发者日志**

---

## 1. 为什么手机不能直接连沙盒

沙盒是云端隔离环境：`/data/workspace`、`/data/user_persistent_data` 都在容器里，手机拿不到路径，平台也没有对外暴露文件服务或入站端口。所以「填个 IP 连过去」走不通 —— `10.0.2.2:8765` 那次超时就是这个原因（那还是模拟器专用地址）。

GitHub **不能**当常驻网关（Pages 只发静态页，Actions 跑完就销毁，不能监听端口），但它的 **Contents API 本身就是 7×24 在线的文件读写接口**，拿来当**摆渡中转站**刚刚好。不需要电脑、路由器、光猫 IP、公网入口或常驻服务器。

---

## 2. 数据怎么走

```
沙盒持久区  /data/user_persistent_data/filebridge/sandbox-files/
     │  scripts/sync_to_github.py   （我在对话里执行）
     ▼
GitHub 仓库 zjh1934/filebridge-android 的 sandbox-files/ 目录
     │  手机 APP 通过 Contents API 直接列目录 / 读 / 写 / 删
     ▼
安卓 APP（只有「沙盒文件」这一件事）
     │  编辑保存 = PUT 回仓库
     ▼
下次开对话说「同步沙盒文件」→ scripts/pull_from_github.py 回灌持久区
```

查看和编辑是即时的；**回到沙盒**要等下次对话跑一次回灌脚本。不是实时双向同步，这点如实说明。

---

## 3. 用户怎么用（就一步）

1. 到 Release 页下载 `FileBridge-v1.4.apk`，安装
2. 打开 → 首页「**先连上沙盒**」→ 把令牌**粘一次** → 点「保存并查看文件」
3. 之后打开直接看到文件列表，点文件 → 「编辑并保存」→「保存到沙盒」

仓库地址、`sandbox-files` 目录、分支全部预置在包里，**不用填 IP、不用配路由器、不用懂网络**。顶部「已连接」小圆点可以点开改令牌。

> 改完想让内容回到沙盒持久区，跟我说一句「同步沙盒文件」。

---

## 4. 隐私边界（重要）

`filebridge-android` 是**公开仓库**，`sandbox-files/` 里的内容别人也能看到。

- 只同步专门的 `sandbox-files/` 目录
- **不碰** `/data/user_persistent_data` 下其它私人资料（如 `lovers`、`听本`、`林晚`）
- 想编辑沙盒里别的文件，说一句「把 XXX 放进沙盒文件」，我同步过去
- 别往这个目录放敏感内容

令牌只存在 APP 私有 `SharedPreferences`，不写进 APK、不进仓库、导出日志时自动打码。那个 PAT 权限范围很大又在聊天里明文出现过，建议抽空换成**只授权这一个仓库 Contents 读写**的细粒度令牌，重新粘一次即可。

---

## 5. v1.4 修了什么（真机日志定位）

用户导出的开发者日志显示：15 条网络请求全部 `→ -1 (1ms)`，异常是 **`android.os.NetworkOnMainThreadException`**，堆栈落在 `GitHubStore.call()` → `OkHttp execute()` → `AndroidUiDispatcher`（主线程）。

根因：`GitHubStore` 用 OkHttp **同步** `execute()`，方法虽写成 `suspend`，但没切 `Dispatchers.IO`；`rememberCoroutineScope()` 绑主线程，StrictMode 直接拦截。**suspend 只是标记，不会自动换线程**。所以从 v1.2 起，APP 里所有联网操作一次都没成功过 —— 之前声称的"闭环实测"是 Python 脚本模拟的，CI 编译和 19/19 服务端验证都碰不到这条链路。

修复：

| 改动 | 说明 |
| --- | --- |
| 所有对外方法 `suspend` + `io{}` | `withContext(Dispatchers.IO)` 统一切线程 |
| `call()` 加主线程断言 | 漏切时报明确文案，不再是 `null` |
| 嵌套调用收进 IO 块 | `saveText` → `currentSha` → `put` 全程不回主线程 |
| CI 新增 `Source self-check` | 每次打包前跑线程安全静态断言，防回归 |

顺带修了两个小毛病：日志里 `APP 版本 | 0.1 (versionCode 1)` 是**硬编码**，改成从 `PackageInfo` 动态读取；异常 message 为 null 时「没能读到沙盒文件：」后面一片空白，现在有兜底文案。

≥ 验证边界：沙盒无安卓真机，依据是**日志堆栈 + Kotlin 编译 + 14 项静态自检（含线程安全）+ 带令牌的 Contents API 实读**。真机效果需装包点一下刷新确认。

---

## 6. v1.3 做过的事（保留）

**瘦身**：清掉 1.1 残留的 `LocalStore.kt` 本机管理器、云网关客户端和 Retrofit/Gson 依赖；启用 **Android 官方 R8 代码压缩 + 资源收缩**（`proguard-rules.pro` 保留自己的代码、数据模型和 `FileProvider`）。实测 **v1.2 6,503,992 B → v1.3 1,581,843 B，小了 75.7%**。

**UI 重设计**：依据 GitHub 上 `anthropics/skills` 的 **frontend-design** 规约（先定 token 再画界面、避开 AI 默认审美、单点 bold 其余克制、文案用用户语言、错误/空态给下一步），加 Material 3 形状/字阶刻度和 Now in Android `:core:designsystem` 做法。颜色/字阶/圆角集中在 `Theme.kt`；顶部路径面包屑当主视觉、文件卡带类型徽章、操作收进底部动作面板。

---

## 7. 目录结构

```
filebridge/
├── .github/
│   ├── workflows/build-apk.yml    # CI：自检 → 固定签名 → 打包 → 发 Release
│   └── RELEASE_BODY.md            # Release 页面正文
├── scripts/
│   ├── sync_to_github.py          # ★ 沙盒 → 仓库
│   ├── pull_from_github.py        # ★ 仓库 → 沙盒
│   ├── verify.py                  # 服务端 19 项端到端验证
│   ├── check_sources.py           # 源码静态自检（含线程安全断言）
│   ├── start_server.sh            # （可选）云网关
│   └── sync_workspace.py          # （可选）工作区 → 持久区
├── server/app.py                  # （可选）文件网关服务
├── web/index.html                 # （可选）Web 控制台
├── sandbox-files/                 # ★ 中转目录（推到仓库同名目录）
├── android/app/src/main/java/com/example/filebridge/
│   ├── GitHubStore.kt             # ★ Contents API 后端（v1.4 全量切 Dispatchers.IO）
│   ├── Models.kt                  # 共用的纯 Kotlin 数据模型
│   ├── Theme.kt                   # ★ 设计系统：颜色 / 字阶 / 形状 token
│   ├── DevLog.kt                  # 日志采集 / 脱敏 / MD 导出 / 动态版本号
│   └── MainActivity.kt            # 单模式界面
├── VERIFY.md / VERIFY_STATIC.md
└── README.md
```

---

## 8. 沙盒侧同步命令

```bash
export FILEBRIDGE_GH_TOKEN='你的令牌'

python3 scripts/sync_to_github.py      # 沙盒文件推到仓库，手机就能看到
python3 scripts/pull_from_github.py    # 手机改动回灌进沙盒持久区
```

两个脚本都带 `[skip ci]`，CI 也配了 `paths-ignore: sandbox-files/**`，同步文件不会触发重新打包。

---

## 9. 固定签名与版本规矩

- 自签名证书（RSA 2048，30 年）本体**不进公开仓库**，加密存在 Actions Secret `FILEBRIDGE_KEYSTORE_B64`，CI 构建时解码使用；`apksigner verify --print-certs` 每轮校验。
- applicationId 固定 `com.example.filebridge`，versionCode 递增 → **v1.4 直接覆盖 v1.1/v1.2/v1.3**，不卸载、不丢数据。
- CI 顶部三行控制一切：

```yaml
APP_VERSION: "1.4"
APK_NAME: "FileBridge-v1.4.apk"
RELEASE_TAG: "v1.4"
```

每出一个版本三行一起 +0.1，`build.gradle.kts` 的 `versionName` 同步、`versionCode` +1。

---

## 10. 一键导出 Markdown 开发者日志

「运行日志」chip →「导出 Markdown」/「另存为…」。

记录：生命周期、目录导航、**每次 GitHub 请求的路径/状态码/耗时**、文件读写字节数、异常完整堆栈、设备与系统信息、真实 APP 版本。导出 MD 含导出信息 / 运行摘要 / 最近状态 / 完整时间线 / 网络请求明细 / 异常堆栈。

`sanitize()` 把 `sig=`、`token=`、`x-api-key`、`Bearer`、`ghp_/github_pat_` 一律打码，Token 固定写 `[REDACTED]`。

---

## 11. 已知限制

- **不是实时双向**：手机改完仓库即时生效，回灌沙盒要下次对话跑脚本。
- Contents API 单文件上限 100 MB，建议放文本 / 文档 / 代码类。
- 公开仓库 = 中转内容公开，见第 4 节。
- 沙盒无安卓真机，UI 与网络行为以真机为准；出问题导日志即可定位。
- 开发者日志只存本地，不自动上传。

---

## 12. 跨对话持久性验证

`scripts/sync_workspace.py --init` 会写 `/data/user_persistent_data/filebridge/CROSS_SESSION_MARKER.txt`。
下次新开对话让我读这个路径，文件还在、创建时间不变，就说明持久区确实跨对话保留。
