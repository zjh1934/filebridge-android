# 沙盒管理器 · 在手机上查看和编辑跨对话持久区

> **为什么做这个软件**：`/data/user_persistent_data` 里的文件在云端沙盒里，AI（糖糖、听本等活跃项目）不停在写，
> 但在对话里不好翻、不好看、不好改。这个 APP 就是给这块持久区装一个**手机上的文件管理界面**：
> 能浏览目录结构、打开、编辑、保存、新建、改名、删除；改动即时提交到私有镜像仓，再同步回沙盒。
>
> **不是手机本地文件管理器**，系统自带的比手搓的强 100 倍。

- 代码仓（公开）：https://github.com/zjh1934/filebridge-android
- 数据镜像仓（**私有**）：https://github.com/zjh1934/filebridge-persistent
- 最新安装包：https://github.com/zjh1934/filebridge-android/releases/tag/v1.7
- 签名固定：v1.1 起同一张证书，后续版本直接覆盖安装
- 版本规则：`FileBridge-v1.7.apk` → v1.8 → v1.9 …

---

## 1. 两个仓库，各干各的

| 仓库 | 用途 | 可见性 |
| --- | --- | --- |
| **zjh1934/filebridge-android** | APP 源码、CI、打包、Release | 公开 |
| **zjh1934/filebridge-persistent** | **持久区完整镜像**，APP 读写的对象 | **私有** |

镜像仓的**仓库根目录 = `/data/user_persistent_data` 根目录**。实测约 **2200 文件 / 1 GB**（会随其它对话持续写入而变化），
`lovers`（糖糖）、`听本`、`林晚`、`filebridge`、`_r198_patch.py` 全在，目录结构一一对应。

之前 v1.2～v1.4 只在代码仓里开了个 `sandbox-files/` 小目录塞 5 个文档，方向错了。
v1.5 改成整个持久区全量镜像并独立仓库；v1.6 补齐云盘式交互；v1.7 内嵌 MD 编辑器 + 文件/文件夹重命名。

---

## 2. 数据怎么走（以及"实时"的真实边界）

```
沙盒持久区 /data/user_persistent_data/   ← AI 不停在写
     │  scripts/sync_persistent.py sync
     ▼
私有镜像仓 zjh1934/filebridge-persistent
     │  APP 通过 Contents API 列目录 / 读 / 写 / 删
     ▼
手机「沙盒管理器」：浏览、编辑、保存、新建、改名、删除
```

| 方向 | 是否实时 | 说明 |
| --- | --- | --- |
| **手机 → 镜像仓** | **是，点了就生效** | 保存 / 新建 / 改名 / 删除 / 导入都是直接 `PUT`/`DELETE` commit 到仓库，没有中间缓存 |
| 镜像仓 → 手机 | **是** | 点「刷新」立刻重新列目录，看到的就是仓库最新状态 |
| **沙盒 → 镜像仓 / 镜像仓 → 沙盒** | **需要触发一次同步** | 沙盒不是常驻服务，我也只在对话被调用时运行；说一句「同步持久区」就跑 |

**为什么要说清楚**：GitHub Actions 是跑完就销毁的临时容器，没有持久区挂载，写不进沙盒；
沙盒也不提供对外入站端口。所以「仓库改动自动秒回沙盒」在当前架构下做不到常驻实时，
能做的是**每次对话开头/结尾我主动跑一次同步**，或者你嫌不够就说一声，我用定时任务到点自动跑。
但**手机上看和改是即时的** —— 这才是这个软件要解决的核心痛点。

---

## 3. 界面借鉴了哪些开源项目

参考了 OpenCloud / ownCloud iOS 客户端的界面元素清单（面包屑、搜索、排序、视图切换、多选开关、三点菜单）、
Davio / Material Files / Voyager 的文件管理交互，以及 Material 3 关于 **Back（返回历史）与 Up（向上层级）** 的区分。

| 功能 | 之前 | v1.6 |
| --- | --- | --- |
| **系统返回键** | 直接退出 APP ❌ | 逐级返回上一级目录；根目录才弹确认。多选/搜索时先退出多选/搜索 |
| 面包屑 | 有 | 保留，可点任意一级跳转 |
| 多选批量 | 无 | 顶部「多选」→ 点条目勾选 → 底栏显示已选数、全选、批量删除 |
| 搜索 | 无 | 当前目录内搜索 + **全仓搜索**（一次读整棵树，2200 文件里按名字找） |
| 排序 | 无 | 名称 / 大小 / 类型三种循环切换，文件夹始终在前 |
| 视图 | 只有列表 | 列表 / 网格切换 |
| 详情 | 无 | 完整路径、类型、大小、仓库 sha，一键复制路径 |
| 文件夹操作 | 只能进 | 「⋯」面板：进入 / 详情 / **删除整个目录**（打包成一条回收站记录，可整体恢复） |
| 底部状态 | 无 | 显示「N 个文件夹 · M 个文件 · 已选 K」 |
| 操作确认 | 部分 | 批量删除、目录删除、退出都先确认 |
| 同步提示 | 无 | 首页明确写「改动会立刻提交到仓库」，不让你猜 |
| 轻反馈 | 无 | Toast 提示「已移到回收站（仓库已更新）」「路径已复制」 |

已有的功能一个没动：编辑并保存、覆盖、导入、新建文件/目录、改名、删除（回收站可恢复）、打开、分享、一键导出 Markdown 运行日志、v1.4 的 `Dispatchers.IO` 线程修复。

### 3.1 v1.7：内嵌 Markdown 编辑器

借鉴对象与许可证：

| 项目 | License | 借鉴内容 | 处理方式 |
| --- | --- | --- | --- |
| **Markor** `gsantner/markor` | GPL-3.0 | 编辑器结构：顶栏文件名+保存、底部横滚格式化工具栏、编辑/预览双模式、列表自动续行、离开提示保存、切后台自动落盘防丢字；「原生 EditText + Spannable 上色，不用 WebView/CodeMirror」的决策（中文输入/长文滚动/内存更好） | **只借鉴架构与界面，未复制源码**，用 Jetpack Compose 重写，避免 GPL 传染 |
| **Markwon** `noties/Markwon` | Apache-2.0 | 确认「Markdown → 原生 Spannable、无需 WebView」可行 | 未引入库（会拖进 commonmark-java 撑大包体），高亮/预览自写 |
| **Material Files** `zhanghai/MaterialFiles` | GPL-3.0+ | 面包屑地址栏、每行右侧 ⋯ 菜单（打开/编辑/**重命名**/详情/删除）、顶部搜索排序、右下 FAB | 只抄结构，代码自己写 |

`MdEditor.kt` 文件头注明了出处。实现要点：

- `VisualTransformation` + `OffsetMapping.Identity` 做语法高亮：**只上色、不改文本长度**，光标选区不错位
- 高亮覆盖标题 / 引用 / 列表 / 分割线 / 代码块 / 行内代码 / 粗斜体 / 链接
- 预览用 `mdPreview()` 转 `AnnotatedString`，无 WebView、无第三方解析库
- 底部工具栏：H1–H3、粗体、斜体、删除线、行内代码、代码块、引用、列表、链接、分割线、表格；支持包裹选区与再点取消
- **查找替换**（全文高亮 + 匹配数 + 全部替换）、撤销/重做 120 步、列表自动续行
- **自动草稿**：每次改动异步落到 `filesDir/drafts/`，切后台被杀不丢字，回来顶部可「恢复草稿」
- 顶部实时字数/行数/未保存圆点；返回键有未保存改动弹「保存并退出 / 放弃 / 继续编辑」
- 保存到沙盒 = 直接 commit 私有镜像仓

### 3.2 v1.7：重命名（文件 + 文件夹）

之前文件改名藏在动作面板里、文件夹完全没有。现在：

- 文件：动作面板「重命名」；多选选中 1 项时底栏也有「重命名」
- 文件夹：⋯ 菜单「重命名文件夹」

**为什么文件夹改名要单独设计**：GitHub Contents API 没有目录改名，逐文件 PUT/DELETE 会产生几十次 commit，断网会留半个目录。这里走 Git Data API，一次 `POST /git/trees` 把旧前缀下的 blob **复用 sha 映射到新路径**、旧路径置 `sha:null`，再一个 commit + PATCH ref —— 内容零重传、单次原子提交。配套同名冲突检测、非法字符校验、改名后自动修正当前所在路径。

**同步闭环**：三向比较会把它识别成「删旧 + 加新」，旧路径本地删除、新路径回灌创建。已用临时目录实测：镜像仓改名 → `sync` → 沙盒出现新目录 → 清理 → `verify` 字节一致。

### 应用名与 Logo

- 名字改成**「沙盒管理器」**（`strings.xml` 的 `app_name`）
- 自绘 Logo：深墨蓝圆角底 + 青绿打开的盒子 + 上下双向箭头（表示与沙盒双向同步）
- 输出 5 个密度的 `ic_launcher` / `ic_launcher_round` / foreground / background，
  以及 Android 8+ **自适应图标**（含 monochrome），不是一张图硬塞进去

---

## 4. 同步脚本：能不能抓到 AI 在改的文件

**能，而且有字节级证据。**

`verify` 子命令会把本地每个文件按 **git blob sha1** 重新算一遍，跟远端 tree 的 sha 逐项比对，
出具报告 `/data/user_persistent_data/filebridge/SYNC_VERIFY.md`。

实测（2026-11-27，`FB_FORCE_RESCAN=1` 强制重读全部文件）：

```
本地 2229 文件 / 远端 2221 文件 → ❌ 存在差异
  内容一致 2219 | 内容不同 2 | 仅沙盒有 8
  内容不同：filebridge/sync_persistent.py、lovers/WARDROBE.md
  仅沙盒有：lovers/media/round_20261127_cafe_table/ 下 8 个新文件（AI 刚写的）
```

跑一次 `sync` 后重验：

```
本地 2229 / 远端 2229 → ✅ 完全一致（2229 项内容一致，0 差异）
```

那 8 个新文件就是**糖糖项目在另一边被 AI 写出来、脚本自动抓到并推上去的**。

### 针对活跃项目的保障

| 机制 | 解决什么 |
| --- | --- |
| 每次 `sync` 重新全量扫描 | 糖糖、听本这类不停写的项目，新文件/改动不会漏 |
| `FB_FORCE_RESCAN=1` | 强制重算所有 sha，防 AI 原子写但保留 mtime 的场景 |
| **上传前二次校验** | 读文件前后各 stat 一次，发现还在被写就本轮跳过、下一轮再捡，绝不把写一半的字节提交 |
| 三向比较（base / local / remote） | 只本地变→上传；只远端变→回灌；两边都变→以手机为准，本地版本备份到 `/data/workspace/conflicts/` 并写 `SYNC_CONFLICTS.md` |
| 逐项目分批 + 每批 60 条独立 commit | 1 GB 全量不会一次压垮 GitHub；每批落盘，中断可续 |
| `.git` 内部目录跳过 | `听本/tingben/.git` 是另一个 Git 仓库的元数据，GitHub 不接受该路径组件 |
| 空目录 `.gitkeep` 占位 | 42 个空目录的结构照样保留，APP 自动隐藏占位文件 |
| 内容去重 + blob 缓存 + 限速退避 | 避开 GitHub 5000 次/小时的 core 配额与写限流 |

### 命令

```bash
export FILEBRIDGE_GH_TOKEN='你的令牌'

python3 scripts/sync_persistent.py groups          # 项目清单与首次同步进度
python3 scripts/sync_persistent.py status          # 只看差异，不改动
python3 scripts/sync_persistent.py sync            # ★ 平时只用这一句：自动检测所有变化
python3 scripts/sync_persistent.py verify          # 字节级一致性报告
FB_FORCE_RESCAN=1 python3 scripts/sync_persistent.py verify   # 强制重读全部文件
python3 scripts/sync_persistent.py sync 听本        # 只同步某个项目
```

首次全量按项目分批（`_root` → `filebridge` → `听本` → `林晚` → `lovers`）已经跑完 **5/5**，
以后不用再分组。脚本在**持久区 `filebridge/` 里也放了一份**，还有 `SYNC_HOWTO.md`，下次开新对话直接能跑。

---

## 5. 维护分工（我这边要承担的）

1. **APP**：交互、功能、UI（含内嵌 MD 编辑器）、打包、固定签名、版本递增、Release 发布。
2. **同步**：每次对话我改完持久区里的文件会主动跑一次 `sync`；你说「同步持久区」我立刻跑；
   需要更接近实时就用定时任务到点跑。
3. **校验**：定期跑 `verify`，把「两边是否字节一致」用报告说清楚，不靠嘴说。
4. **排障**：APP 里「运行日志 → 导出 Markdown」发我，日志带每次请求的状态码/耗时、完整堆栈、
   真实版本号和镜像仓地址，令牌自动打码。
5. **边界**：镜像仓始终私有；同步报告 `SYNC_VERIFY.md` / `SYNC_CONFLICTS.md` 与状态文件不进镜像；
   令牌只从环境变量读，不写进任何文件或仓库。

---

## 6. 隐私边界（重要）

镜像仓是**私有**的，因为全量映射包含 `lovers`（糖糖）、`听本`、`林晚` 等私人资料。

- 令牌只存在 APP 私有 `SharedPreferences`，不写进 APK、不进仓库、导出日志时自动打码
- 那串 PAT 权限很大（`repo`、`workflow`、`delete_repo` 等）又在聊天里明文出现过 →
  **建议换成只授权这两个仓库的细粒度令牌**，换完在 APP 里重新粘一次即可
- 别把镜像仓改成公开

---

## 7. 用户怎么用

1. 下载 `FileBridge-v1.6.apk`，安装（v1.1～v1.5 直接覆盖，不用卸载）
2. 打开 → 首页「先连上沙盒」→ 把令牌**粘一次** → 点「保存并查看文件」
3. 之后打开就能看到持久区全部目录；点文件 → 「编辑并保存」→「保存到沙盒」
4. 改完想让内容回到持久区，说一句「**同步持久区**」

仓库地址、分支全部预置，**不用填 IP、不用配路由器、不用懂网络**。

---

## 8. 目录结构

```
filebridge/                          ← 代码仓（公开）
├── .github/workflows/build-apk.yml  # 自检 → 固定签名 → 打包 → Release
├── .github/RELEASE_BODY.md
├── scripts/
│   ├── sync_persistent.py           # ★ 持久区 ↔ 私有镜像仓（含 verify）
│   ├── check_sources.py             # 静态自检
│   ├── verify.py / start_server.sh / sync_workspace.py
├── server/ · web/                   # （可选）旧网关服务与 Web 控制台
├── android/app/src/main/
│   ├── res/mipmap-*/                 # ★ 5 密度 Logo + adaptive icon
│   ├── res/values/strings.xml        # 应用名「沙盒管理器」
│   └── java/com/example/filebridge/
│       ├── GitHubStore.kt            # Contents API（含 allPaths / deleteFolder）
│       ├── Models.kt / Theme.kt / DevLog.kt / MainActivity.kt
├── VERIFY.md / VERIFY_STATIC.md
└── README.md

filebridge-persistent/               ← 数据仓（私有），根目录 = 持久区根目录
└── lovers/ 听本/ 林晚/ filebridge/ _r198_patch.py ...
```

---

## 9. 已知限制

- **不是常驻实时双向**：手机→仓库实时，仓库→沙盒需触发同步，见第 2 节
- Contents API 单文件上限 100 MB；APP 打开大图会先下载到本机
- GitHub 会提示「仓库建议保持 1 GB 以下」，不影响读写
- 沙盒无安卓真机，UI 与网络行为以真机为准；出问题导日志定位
- 开发者日志只存本地，不自动上传

---

## 10. 跨对话持久性验证

`/data/user_persistent_data/filebridge/CROSS_SESSION_MARKER.txt` 是跨对话标记。
下次新开对话让我读它，文件还在、创建时间不变，就说明持久区确实跨对话保留。
同步脚本与 `SYNC_HOWTO.md` 也在持久区里，下次可以直接跑。
