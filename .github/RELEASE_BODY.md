## FileBridge v1.4 · 修好了「一直连不上」

你导出的日志直接把病根指出来了，这个版本就是冲着它去的。

### 发生了什么

日志里 15 条网络请求全部是 `→ -1 (1ms)`，异常堆栈写得很清楚：

```
android.os.NetworkOnMainThreadException
  at com.example.filebridge.GitHubStore.call(GitHubStore.kt:60)
  at GitHubStore.list(GitHubStore.kt:123)
  at MainActivity$refresh(...)
  at androidx.compose.ui.platform.AndroidUiDispatcher...   ← 主线程
```

`GitHubStore` 用的是 OkHttp **同步** `execute()`，而 `rememberCoroutineScope()` 绑定主线程。我虽然把方法写成了 `suspend`，但**没有切到 `Dispatchers.IO`** —— `suspend` 只是个标记，不会自动换线程，StrictMode 就把请求拦了。

所以从 v1.2 引入 GitHub 中继开始，**APP 里列目录、读文件、编辑保存、删除、回收站、导入，一次都没真正成功过**。之前我说「闭环实测通过」，是用 Python 脚本模拟手机写回仓库跑的，不是 APP 真机跑的；CI 编译和 19/19 服务端验证也都碰不到这条链路。这个漏得挺离谱，认了。

### 怎么修的

- `GitHubStore` 所有对外方法改成 `suspend`，统一用 `io{}` 包 `withContext(Dispatchers.IO)`，内部私有方法只在 IO 块里发请求
- `call()` 开头加了一道**主线程断言**：万一以后哪里漏切，会报「网络请求误跑到主线程」而不是 `null`
- 嵌套调用（`saveText` → `currentSha` → `put`）也都在 IO 块内，不会漏网
- 不允许用 `runBlocking` 把网络强行拉回主线程

**CI 里新增了一步 `Source self-check`**，每次打包前跑线程安全静态断言（所有非私有方法必须 suspend、网络调用必须收拢在 `io{}`、`call()` 必须有主线程断言）。编译能过不代表网络在子线程 —— 这次就是这么漏的，所以加了这道防回归。

### 顺手修的两个小毛病

- 日志里的 `APP 版本 | 0.1 (versionCode 1)` 是**硬编码**从没更新过，现在改成从安装包动态读取（这版会显示 `1.4 (versionCode 5)`）
- `NetworkOnMainThreadException` 的 message 是 `null`，导致界面报「没能读到沙盒文件：」后面一片空白。现在所有异常文案都有兜底，错误态会给下一步动作

### 功能一个没动

还是那套：粘一次令牌 → 看到沙盒文件 → 查看 / 编辑保存 / 覆盖 / 分享 / 改名 / 删除（回收站可恢复）/ 新建 / 导入 / 导出 Markdown 运行日志。`GitHubStore` 的请求逻辑、UI、设计系统都没改，只换了线程。

### 安装

- 附件 `FileBridge-v1.4.apk`，标签 `v1.4`
- **直接覆盖 v1.1 / v1.2 / v1.3**，同一张固定证书，不用卸载、不丢令牌和文件
- 打开后首页「先连上沙盒」粘一次令牌，点「保存并查看文件」，应该就能列出文件了

> 如果之前粘过令牌，这版不用重新粘，打开就会自动刷新。

### 验证边界（如实说）

沙盒里没有安卓真机，也没法在容器里跑 APK。这个修复的依据是：**你日志里的完整堆栈定位 + Kotlin 编译通过 + 14 项源码静态自检（含新增的线程安全断言）+ 带令牌的 Contents API 实读验证**。真机上「能不能列出文件」还得你装上去点一下刷新才算数。

要是还有问题，点「运行日志 → 导出 Markdown」发我，现在版本号是对的、异常文案也不再是空白，一眼能看出卡在哪。

---

构建：Gradle 8.9 · AGP 8.5.2 · Kotlin 1.9.24 · compileSdk 34 / minSdk 26 · JDK 17 · release 固定签名 · R8 压缩。
服务端 19/19 实跑验证见 `VERIFY.md`，源码静态自检见 `VERIFY_STATIC.md`。
