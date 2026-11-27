# FileBridge 源码静态自检

- 执行时间：2026-11-27 15:24:22 CST
- 结果：**14/14 通过**
- 说明：沙盒内无 Android SDK，此处为**静态自检**；Kotlin 实际编译与签名校验由 GitHub Actions 完成，结果以 Release 产物为准。

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| 必需文件齐全 | PASS | 28 个必需文件齐全 |
| 无凭据/私钥泄露 | PASS | 扫描 32 个文件，无 Token / PAT / 私钥泄露 |
| 1.1 残留已清空 | PASS | 4 个残留文件已删、冗余依赖已清、数据模型独立保留、界面只剩单一沙盒模式 |
| 默认沙盒文件模式 | PASS | 默认沙盒文件、仓库/目录预置、首页连接卡引导 |
| v1.3 UI 设计系统 | PASS | 设计 token 集中、底部动作面板/路径面包屑/空态与错误态齐备，避开模板化审美 |
| GitHub 中继后端 | PASS | Contents API 列/读/写/删/回收站/导入/下载齐备（v1.3 未改动） |
| 核心功能未改坏 | PASS | 列表/读/编辑/覆盖/导入/新建/改名/删除/回收站/打开/分享/日志全部保留 |
| 双向同步脚本 | PASS | 双向同步脚本 + CI paths-ignore 齐备 |
| 网络线程安全 | PASS | 全部网络入口 suspend + Dispatchers.IO，call() 有主线程断言，无 runBlocking 绕过 |
| 日志准确性 | PASS | 版本号动态读取、异常文案兜底、脱敏/MD 导出/分享齐备 |
| 固定签名 | PASS | 固定证书来自 Secret、release 签名、CI 校验、私钥不入库 |
| v1.4 版本规则 | PASS | v1.4 包名/tag/版本规则固定，CI 含线程安全自检，可覆盖旧版 |
| CI workflow 校验 | PASS | PyYAML 解析通过；产物路径与 Release 配置正确 |
| README 文档校验 | PASS | README 已同步 v1.4：线程修复、瘦身、UI 依据与边界 |

> 本文件由 `scripts/check_sources.py` 生成。
