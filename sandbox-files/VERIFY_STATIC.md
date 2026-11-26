# FileBridge 源码静态自检

- 执行时间：2026-11-27 04:54:50 CST
- 结果：**10/10 通过**
- 说明：沙盒内无 Android SDK，此处为**静态自检**；Kotlin 实际编译与签名校验由 GitHub Actions 完成，结果以 Release 产物为准。

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| 必需文件齐全 | PASS | 28 个必需文件齐全 |
| 无凭据/私钥泄露 | PASS | 扫描 32 个文件，无 Token / PAT / 私钥泄露 |
| 默认沙盒文件模式 | PASS | 默认沙盒模式、仓库/目录预置、只需粘一次令牌 |
| GitHub 中继后端 | PASS | Contents API 列/读/写/删/回收站/导入/下载齐备，请求全量打点 |
| 双向同步脚本 | PASS | 双向同步脚本 + CI paths-ignore 齐备（同步不触发重打包） |
| 开发者日志 | PASS | 日志采集/脱敏/MD 导出/分享齐备，安卓与 Web 都已接入 |
| 固定签名 | PASS | 固定证书来自 Secret、release 签名、CI 校验、私钥不入库 |
| v1.2 版本规则 | PASS | v1.2 包名/tag/版本规则固定，可覆盖 v1.1 |
| CI workflow 校验 | PASS | PyYAML 解析通过；产物路径与 Release 配置正确 |
| README 文档校验 | PASS | README 已纠正目标，含中继原理、隐私边界与闭环 |

> 本文件由 `scripts/check_sources.py` 生成。
