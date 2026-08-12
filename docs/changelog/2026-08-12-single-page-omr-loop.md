# 2026-08-12：单页 OMR 与待校正草稿

- 新增自托管 Audiveris companion、认证健康检查与异步识别任务 API。
- Android 新增 Room v8 `RecognitionJob`、WorkManager 可恢复转换、失败/取消/重试和曲谱库入口。
- 设置页新增加密保存的 OMR 服务地址与访问令牌。
- 真机完成真实单页图片到 MusicXML 草稿、刻谱、试听、音高校正、修订和撤销。
- 修复 Debug localhost 通过 `adb reverse` 时被网络约束永久阻塞的问题。
- 修复 Audiveris 合法延音线顺序触发 alphaTab 栈溢出的问题，保留原有音乐语义。
- `.gpiano` 升级到 v3，备份脱敏识别任务并保持 v1/v2 导入兼容。
- Python 5 项测试、Android JVM 9 项测试和 Debug 构建通过；Room 迁移、真实 OMR、备份往返及损坏拒绝完成设备验证。

