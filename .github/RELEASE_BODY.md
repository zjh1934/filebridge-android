## FileBridge · Debug 安装包

由 GitHub Actions 在 `ubuntu-latest` 上自动编译，产物为 **debug 签名 APK**，可直接安装到安卓手机试用。

### 这个 APP 做什么

把云端沙盒的跨对话持久目录 `/data/user_persistent_data/filebridge/files` 通过一层 **id 化的 REST 文件网关** 接到手机上：

- 列目录 / 进入子目录 / 新建目录
- 上传、下载、用系统应用打开（`FileProvider` + `content://` 临时授权）
- APP 内置纯文本编辑并回写（sha256 ETag 乐观锁，冲突返回 409）
- 覆盖上传、改名、软删除进回收站、恢复、彻底删除
- 连接设置里填服务地址与 API Token

### 新增：一键导出 Markdown 开发者日志

顶栏「日志」或工具栏「导出日志」→「一键导出 MD」：

- 记录 **APP 生命周期、目录导航、每次接口的路径/状态码/耗时、文件读写字节数、异常完整堆栈、设备与系统信息、服务地址**
- 内存环形缓冲 + 磁盘 ndjson 追加，跨启动保留
- 导出为 `.md`：导出信息 / 运行摘要 / 完整时间线 / 网络请求明细 / 异常堆栈
- **Token、预签名 `sig`、`Bearer` 全部自动打码**，日志可安全转发给开发者排查
- 支持「一键导出 MD」直接分享，或「另存为…」用系统文件管理器保存到任意位置，也可一键清空

Web 控制台同样提供「导出日志」，会把前端请求与错误导出成 Markdown。

### 安装

1. 下载本页附件 `FileBridge-debug.apk`
2. 手机上允许「安装未知来源应用」
3. 安装后打开 APP，在「设置」里填 **HTTPS 服务地址** 与 **API Token**

> 沙盒里的服务不常驻、没有公网入口。要让手机真正连上，请把仓库里的 `server/` 部署到你有固定地址的云主机 / NAS，用 Nginx 或 Caddy 套一层 HTTPS，再在 APP 设置里填该地址。详见仓库 README 第 3 节。

### 构建信息

- Gradle 8.9 · AGP 8.5.2 · Kotlin 1.9.24 · Compose BOM 2024.06.00
- compileSdk 34 / minSdk 26 / JDK 17
- debug 签名，未做代码混淆，仅用于试用与排错

服务端与 Web 控制台对应的接口在本地已做 **19/19 端到端实跑验证**（见 `VERIFY.md`）；客户端源码另附静态自检结果（`VERIFY_STATIC.md`）。
