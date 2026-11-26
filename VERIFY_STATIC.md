# FileBridge 源码静态自检

- 执行时间：2026-11-27 04:42:42 CST
- 结果：**9/9 通过**
- 说明：沙盒内无 Android SDK，此处为**静态自检**；Kotlin 实际编译与签名校验由 GitHub Actions 完成，结果以 Release 产物为准。

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| 必需文件齐全 | PASS | 25 个必需文件齐全 |
| 无凭据/私钥泄露 | PASS | 扫描 25 个文件，无 Token / PAT / 私钥泄露 |
| 零配置本机模式 | PASS | 默认本机模式、无默认 IP、本地 CRUD/导入/回收站/分享齐备 |
| DevLog 核心能力 | PASS | 日志采集/脱敏/MD 导出/分享/异常堆栈齐备 |
| 日志接入链路 | PASS | 日志已接入网络层 / 仓库层 / UI / Manifest / FileProvider / Web |
| 固定签名 | PASS | 固定证书来自 Secret、release 签名、CI 校验签名、私钥不入库 |
| V1.1 版本规则 | PASS | V1.1 起包名/tag/版本规则固定，后续可覆盖安装 |
| CI workflow 校验 | PASS | PyYAML 解析通过；产物路径与 Release 配置正确 |
| README 文档校验 | PASS | README 已改为零配置优先，含签名与版本规则 |

> 本文件由 `scripts/check_sources.py` 生成。
