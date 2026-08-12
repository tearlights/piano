# 2026-08-12：单页 OMR 与待校正草稿交接

## 已完成

- Room 升级到 v8，新增持久 `RecognitionJob`；WorkManager 执行上传、轮询、下载、MusicXML/ScoreIR 校验和结构建立。
- 曲谱库图片卡片提供转换、取消、重试与打开练习谱；工作区正式读取识别结果，不再默认加载 Debug 测试谱。
- 新增自托管 `omr-service/`，以 bearer token 调用 Audiveris 5.11.0；输入限制为单页 PNG/JPEG/WebP，任务目录隔离并可在服务重启后恢复。
- OMR endpoint 与 token 在设置中配置；token 使用 Android Keystore AES/GCM 加密，正式地址只允许 HTTPS，Debug 可用 localhost 与 `adb reverse`。
- `.gpiano` 升级到 v3，纳入脱敏识别任务；远端任务 ID与 token 不导出，v1/v2 保持兼容。
- 增加 MusicXML 兼容规范化：将中间延音音符的 `start, stop` 语义等价排序为 `stop, start`，避免 alphaTab 1.8.3 播放自环；旧修订读取时也受保护。

## 验证

1. `omr-service` 5 项 Python 测试通过；Android 9 项 JVM 测试与 `assembleDebug` 通过。
2. Audiveris 5.11.0 将 2977×4208 测试页在约 18 秒内生成 127,544 字节 MusicXML。
3. 真机未清数据覆盖安装，Room v7→v8 成功，原有 1 个结构与 2 个修订保留。
4. 真机完成“导入图片 → 连接服务 → 转换 → 待校正草稿 → 渲染 → 试听 → F♯4 改为 G♯4 → 撤销”。
5. 播放日志显示真实 OMR 第 1 小节使用 tick `0–3840`、75% 速度并正常结束，无栈溢出。
6. `.gpiano` v3 包含 1 份原谱、2 个结构、4 个修订与 1 个脱敏识别任务；v3 往返、v2 导入及损坏包拒绝均通过，损坏导入后数据计数不变。

## 已知边界

- Audiveris 没有可靠整页置信度，草稿统一标为“待校正”，不展示伪造百分比。
- MusicXML 没有足以建立原图像素坐标的完整关系；当前 `sourceMapRelativePath` 保持空值。
- OMR 仅支持图片单页；PDF、多页队列和远端取消尚未实现。
- alphaTab 字节加载仍反射构造 `Uint8Array`，需要后续稳定适配器测试。

## 下一工作单元

完成原图/结构化谱对照、时值/休止/连线等关键校正，以及可浏览的修订历史和重做；所有修改继续走 DOM 局部编译、结构校验与独立修订文件。

