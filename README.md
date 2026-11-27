# FileBridge · 在手机上查看和编辑跨对话持久区

> 这个 APP 唯一的目标：把 `/data/user_persistent_data`（**真正跨对话保留的目录**，不是 `/data/workspace` 工作区）完整映射到手机，能浏览目录结构、打开、编辑、保存、删除；改动还能同步回沙盒。

## 两个仓库，彻底分开

| 仓库 | 用途 | 可见性 |
| --- | --- | --- |
| **zjh1934/filebridge-android** | APP 源码、CI、打包、Release | 公开 |
| **zjh1934/filebridge-persistent** | **持久区完整镜像**，APP 读写的对象 | **私有** |

- 最新安装包：https://github.com/zjh1934/filebridge-android/releases/tag/v1.5
- 签名固定：v1.1 起同一张证书，后续版本直接覆盖安装
- 版本规则：`FileBridge-v1.5.apk` → v1.6 → v1.7 …
- APP 内置一键导出 Markdown 开发者日志

镜像仓的**仓库根目录 = `/data/user_persistent_data` 根目录**。实测规模：

```
5670 文件 · 690 目录（40 个空目录）· 约 1 GB
_r198_patch.py / filebridge / lovers(793 文件 867.6 MB) / 听本(4614 文件 61.8 MB) / 林晚(255 文件 86.4 MB)
```

空目录在 Git 里存不住，用 `.gitkeep` 占位保留结构，APP 自动隐藏该占位文件。

---

## 1. 为什么这样做

沙盒持久区在云端隔离容器里，手机拿不到路径，平台也没有对外文件服务或入站端口，所以「填 IP 连过去」走不通。

GitHub **不能**当常驻网关（Pages 只发静态页，Actions 跑完就销毁），但它的 **Contents API / Git Data API 是 7×24 在线的文件读写接口**，适合当摆渡镜像。不需要电脑、路由器、光猫 IP、公网服务器。

之前 v1.2～v1.4 只在代码仓里开了个 `sandbox-files/` 小目录塞 5 个文档，方向错了。**v1.5 改成整个持久区全量镜像，并把数据仓独立出来。**

---

## 2. 数据怎么走

```
沙盒持久区 /data/user_persistent_data/
     │  scripts/sync_persistent.py  sync   （我在对话里跑）
     ▼
私有镜像仓 zjh1934/filebridge-persistent  （根目录一一对应）
     │  手机 APP 通过 Contents API 列目录 / 读 / 写 / 删
     ▼
安卓 APP：浏览全部目录、查看、编辑并保存、删除、新建、导入
     │  编辑保存 = PUT 回镜像仓
     ▼
下次说「同步持久区」→ sync_persistent.py 三向比较 → 回灌持久区
```

**不是实时的**：手机改完仓库即时生效，回到持久区要跑一次同步；沙盒里改了也要跑一次才推上去。

---

## 3. 用户怎么用

1. 下载 `FileBridge-v1.5.apk`，安装（v1.1～v1.4 直接覆盖）
2. 打开 → 首页「先连上沙盒」→ 把令牌**粘一次** → 点「保存并查看文件」
3. 之后打开就能看到 `lovers / 听本 / 林晚 / filebridge` 等全部顶层目录，点进去、点「编辑并保存」即可

仓库地址、分支全部预置，**不用填 IP、不用配路由器、不用懂网络**。同一个 PAT 既能读私有仓也能写代码仓，不用第二个令牌。

> 改完想让内容回到持久区，跟我说一句「**同步持久区**」。我改完持久区里的文件也会主动同步一次。

---

## 4. 同步脚本（放两份，跨对话不会丢）

| 位置 | 作用 |
| --- | --- |
| `scripts/sync_persistent.py`（代码仓） | 随源码版本管理 |
| `/data/user_persistent_data/filebridge/sync_persistent.py`（持久区） | **下次开新对话仍在**，且会被同步进镜像仓，APP 里也能看到 |

```bash
export FILEBRIDGE_GH_TOKEN='你的令牌'

python3 scripts/sync_persistent.py groups          # 列出各项目与首次同步进度
python3 scripts/sync_persistent.py sync _root      # 首次：一个项目一个项目来
python3 scripts/sync_persistent.py sync filebridge
python3 scripts/sync_persistent.py sync 听本
python3 scripts/sync_persistent.py sync 林晚
python3 scripts/sync_persistent.py sync lovers

python3 scripts/sync_persistent.py status          # 只看差异，不改动
python3 scripts/sync_persistent.py sync            # 之后：自动检测所有项目的变化
```

**首次按项目分批**（约 1 GB / 5700 文件，别一次硬推），每组独立提交、每批 300 条落一个 commit，
状态每批写盘，中断后重跑自动接着走。项目清单按体积升序，先小后大：

| 项目 | 文件 | 大小 |
| --- | --- | --- |
| `_root` | 1 | 0.0 MB |
| `filebridge` | 10 | 0.0 MB |
| `听本` | 4611 | 61.8 MB |
| `林晚` | 255 | 86.4 MB |
| `lovers` | 816 | 874.0 MB |

**首次跑完后就不用再管分组了**：以后只执行 `python3 scripts/sync_persistent.py sync`，
脚本用三向比较自动检测哪边变了 —— 沙盒里改了就上传，手机上改了就回灌，两边都改以手机为准并备份本地版本。
跟我一句「同步持久区」我就跑；我改完持久区文件也会主动跑一次。

设计要点：

- **三向比较**：`base`（上次同步状态）/ `local`（持久区）/ `remote`（镜像仓），用 **git blob sha1** 当内容指纹，不下载远端内容就能判断谁改了
- 仅本地变 → 上传；仅远端变 → 回灌持久区；两边都变 → **以手机编辑为准**，本地版本备份到 `/data/workspace/conflicts/`
- **断点续传**：分批提交（每批 300 条 tree 条目），中途限流或断网不丢进度，下次接着跑
- 内容去重（相同内容只建一次 blob）、轻微限速、403/429 自动退避
- 空目录用 `.gitkeep` 占位；同步状态存 `.filebridge_sync_state.json`，不进镜像、APP 里隐藏

为什么不用 `git push`：沙盒可用磁盘只剩约 0.4 GB，而持久区约 1 GB，`git clone` + 对象库放不下。脚本全程流式，一次只在内存里拿一个文件，不需要额外磁盘。

---

## 5. 隐私边界（重要）

镜像仓是**私有**的，因为全量映射包含 `lovers`、`听本`、`林晚` 等私人资料。之前公开代码仓里的 `sandbox-files/` 只有 5 个文档，不涉及这些资料。

- 令牌只存在 APP 私有 `SharedPreferences`，不写进 APK、不进任何仓库、导出日志时自动打码
- 同步脚本从环境变量读令牌，**脚本本身不含令牌**
- 那串 PAT 权限很大（`repo`、`workflow`、`delete_repo` 等）又在聊天里明文出现过 → **建议换成只授权这两个仓库的细粒度令牌**，换完重新粘一次即可
- 别把镜像仓改成公开

---

## 6. 版本历史要点

| 版本 | 做了什么 |
| --- | --- |
| v1.1 | 固定签名、版本命名规则（方向曾误做成本机文件管理器） |
| v1.2 | 改回沙盒文件，用代码仓 `sandbox-files/` 做中转 |
| v1.3 | 清残留 + R8 压缩（6.50 MB → 1.58 MB）、按 GitHub `frontend-design` 与 Material 3 重做 UI |
| v1.4 | 真机日志定位并修复 `NetworkOnMainThreadException`，全部网络切 `Dispatchers.IO` |
| **v1.5** | **两仓分离、私有镜像仓全量映射整个持久区、双向增量同步脚本** |

---

## 7. 目录结构

```
filebridge/                          ← 代码仓（公开）
├── .github/workflows/build-apk.yml  # 自检 → 固定签名 → 打包 → Release
├── .github/RELEASE_BODY.md
├── scripts/
│   ├── sync_persistent.py           # ★ 持久区 ↔ 私有镜像仓 双向同步
│   ├── check_sources.py             # 静态自检（含两仓分离、线程安全）
│   ├── verify.py                    # 服务端 19 项端到端验证
│   ├── start_server.sh / sync_workspace.py   # （可选）旧网关/工作区工具
├── server/ · web/                   # （可选）网关服务与 Web 控制台
├── android/app/src/main/java/com/example/filebridge/
│   ├── GitHubStore.kt               # ★ Contents API 后端（根路径、Dispatchers.IO）
│   ├── Models.kt / Theme.kt / DevLog.kt / MainActivity.kt
├── VERIFY.md / VERIFY_STATIC.md
└── README.md

filebridge-persistent/               ← 数据仓（私有），根目录 = /data/user_persistent_data
└── lovers/ 听本/ 林晚/ filebridge/ _r198_patch.py ...
```

---

## 8. 固定签名与版本规矩

- 自签名证书加密存在 Actions Secret `FILEBRIDGE_KEYSTORE_B64`，不进公开仓库；`apksigner verify --print-certs` 每轮校验
- applicationId 固定 `com.example.filebridge`，versionCode 递增 → **v1.5 直接覆盖 v1.1～v1.4**
- CI 顶部三行控制一切：`APP_VERSION` / `APK_NAME` / `RELEASE_TAG`，每版 +0.1，`build.gradle.kts` 同步

---

## 9. 开发者日志

「运行日志」chip →「导出 Markdown」/「另存为…」。记录生命周期、目录导航、每次 GitHub 请求的状态码与耗时、异常完整堆栈、设备信息、真实 APP 版本、镜像仓地址。令牌与签名参数一律打码。

---

## 10. 已知限制

- 首次全量约 1 GB / 5700 文件，走 Git Data API：每个唯一内容要一次 `POST /git/blobs`（实测唯一内容 5453 个，重复只省 260），而 token 的 core 配额是 **5000 次/小时**。所以首次同步会**跨 1～2 个配额窗口**；脚本检测到配额不足会只让一个线程等待到重置并广播给其他线程，自动接着跑，不用人盯着。这也是为什么首次要**按项目分批**：进度可见、每批落盘、中断可续
- 不是实时双向同步，需跑一次 `sync`
- APP 打开大图会先下载到本机；单文件超 100 MB 走不了 Contents API
- 沙盒无安卓真机，UI 与网络行为以真机为准；出问题导日志定位
- 开发者日志只存本地，不自动上传

---

## 11. 跨对话持久性验证

`/data/user_persistent_data/filebridge/CROSS_SESSION_MARKER.txt` 是跨对话标记。下次新开对话让我读它，文件还在、创建时间不变，就说明持久区确实跨对话保留。同步脚本也在持久区里，下次可以直接跑。
