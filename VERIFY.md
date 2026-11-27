# FileBridge 实跑验证记录

- 执行时间：2026-11-27 16:12:32 CST
- 服务地址：`http://127.0.0.1:8765`
- 持久目录：`/data/user_persistent_data/filebridge`
- 结果：**19/19 通过**

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| 服务健康检查 | PASS | root=/data/user_persistent_data/filebridge/files, indexed=0 |
| 错误密钥拦截 | PASS | 错误密钥 -> 401 |
| 新建目录 | PASS | 建目录 verify_docs_ea2317 |
| 上传文件 | PASS | 上传 hello_ea2317.txt, sha=65e50f1ad2 |
| 根目录列表 | PASS | 根目录 1 个子目录 / 1 个文件 |
| 子目录上传 | PASS | 子目录上传 rel=verify_docs_ea2317/nested_ea2317.txt |
| 子目录隔离 | PASS | 子目录隔离正确 |
| 下载内容一致 | PASS | 下载 17 字节，内容一致 |
| ETag 冲突 409 | PASS | 旧 ETag 覆盖 -> 409（乐观锁生效） |
| 带 ETag 覆盖写 | PASS | 覆盖成功，新 sha=53152a6462 |
| 预签名打开与过期校验 | PASS | 预签名 URL 免密钥可取；错签/过期 -> 403 |
| 路径穿越与文件名清洗 | PASS | parent=../../etc -> 400；文件名 ../ 被清洗为 evil_ea2317.txt |
| 重命名 | PASS | 重命名为 renamed_ea2317.txt |
| 软删除 | PASS | 软删后主列表消失、回收站可见 |
| 回收站恢复 | PASS | 从回收站恢复成功 |
| 彻底删除 | PASS | 彻底删除 -> 404 |
| 回收站彻底删除 | PASS | 回收站彻底删除接口可用 |
| 工作区同步 /sync | PASS | /sync 登记 _fb_verify_ea2317.txt 到持久区 |
| 跨对话标记文件 | PASS | 标记文件存在：/data/user_persistent_data/filebridge/CROSS_SESSION_MARKER.txt |

> 本文件由 `scripts/verify.py` 真实调用运行中的服务生成，非手写结论。
