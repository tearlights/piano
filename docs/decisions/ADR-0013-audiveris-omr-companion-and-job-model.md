# ADR-0013：Audiveris OMR companion 与可恢复转换任务

日期：2026-08-12  
状态：已接受

## 背景

结构化乐谱工作区已经能可靠消费、校正、保存和播放 MusicXML，但正式数据仍来自 Debug 测试资源。单页 OMR 往往持续数十秒到数分钟，可能因网络、进程回收、输入质量或引擎失败而中断；它不能作为一次 Compose 点击后的临时内存操作。

Audiveris 能以批处理方式识别图片并导出 MusicXML 4.0，但其运行环境包含桌面级 Java、Tesseract 与原生组件，不适合嵌入 Android 应用进程。Audiveris 使用 GNU Affero GPL v3，网络服务部署也必须满足对应源码提供义务。

## 决策

### 1. 引擎与部署边界

- 仓库提供独立的 `omr-service/` companion 源码，通过受限 HTTP API 调用 Audiveris 5.11.0 CLI。
- Android 只依赖自有 `OmrClient` 接口和标准 MusicXML 响应，不链接 Audiveris 类，也不保存其私有对象作为唯一成果。
- companion 默认只监听本机地址并要求 bearer token；比赛设备可通过 `adb reverse` 访问。正式构建只接受 HTTPS，Debug 才允许 localhost 明文连接。
- 原图默认留在手机本地。只有用户明确点击“转换为练习谱”后，当前单页才发送到用户配置的 companion；端点和发送状态必须可见。

### 2. 异步协议

```text
POST /v1/jobs                  上传一张图片，返回 jobId
GET  /v1/jobs/{jobId}          查询 queued/running/ready/failed
GET  /v1/jobs/{jobId}/musicxml 仅 ready 时下载标准 MusicXML
GET  /health                   检查引擎与服务状态
```

服务不伪造细粒度识别百分比：只报告排队、识别中、可校正或失败。Audiveris 没有提供可信的整页统一置信度，因此首版 `recognitionConfidence` 保持空值，结果状态为 `needs-correction`；不能把它包装成高置信度答案。

### 3. Android 任务模型

Room 从 v7 升级到 v8，新增 `recognition_jobs`：

- 稳定任务 UUID、原谱 UUID、页序号和输入 SHA-256；
- provider、远端任务 ID、状态、阶段和尝试次数；
- 可选结果结构 UUID、错误码、用户可读错误和脱敏诊断；
- 创建与更新时间。

WorkManager 2.11.2 在网络约束下执行上传、轮询、下载和校验。每个阶段先更新 Room；进程被系统终止后由同一任务继续，不重新覆盖原谱。下载结果必须先通过 MusicXML 解析和 `ScoreIR` 校验，再用 `StructuredScoreRepository` 原子创建 `omr-draft` 修订；失败只更新任务，不创建半成品结构。

### 4. 原谱、草稿与位置映射

- 原谱文件永不覆盖；OMR MusicXML 保存为 `ScoreRevision 0`，人工和 AI 修改继续创建后续修订。
- 首版工作区提供原图与结构化谱切换，便于人工对照。
- Audiveris 的 MusicXML 导出不包含足以可靠还原原始像素坐标的完整识别图关系，因此首版不制造伪精确映射；`sourceMapRelativePath` 保持空值，并在后续通过 Audiveris `.omr`/annotation 适配器建立可验证映射。

### 5. 备份

`.gpiano` 升级为 v3，加入转换任务元数据。导出时不包含 bearer token；远端任务 ID不作为可迁移数据。正在排队或运行的任务恢复后标为“需要重新转换”，已完成任务只有在对应结构化结果同时存在时才保持 ready。

## 后果

- 比赛演示需要同时启动 companion 和 Audiveris；手机离线阅读、校正、播放及已有结构化乐谱不受 companion 不可用影响。
- 服务端属于产品交付的一部分，需提供启动检查、输入大小限制、超时、任务目录隔离、错误脱敏和 AGPL 源码/许可说明。
- 首版只接收单页 PNG/JPEG/WebP；PDF 与多页批量转换在单页流程稳定后扩展。
- 在真实 Audiveris 输出通过 MusicXML/ScoreIR 校验、Android 任务恢复和失败保护之前，不得把 OMR 标记为完成。

## 实施验证

- Audiveris 5.11.0 已将 2977×4208 的单页测试图片在约 18 秒内转换为 127,544 字节 MusicXML；Android 真机完成任务创建、上传、轮询、下载、校验与结构建立。
- Room v7→v8 在不清除设备数据的情况下迁移成功；`.gpiano` v3 已验证导出、恢复、v2 向后兼容和损坏包拒绝，token 与远端任务 ID未进入备份。
- `adb reverse` 不会让 Android 报告网络已连接，因此 Debug localhost 任务不设置 WorkManager 网络约束；该例外只适用于可调试构建的回环 HTTP，正式 HTTPS 任务仍要求 `CONNECTED`。
- Audiveris 输出中延音链中间音的 `<tie>`/`<tied>` 顺序为 `start, stop`；alphaTab 1.8.3 会因此产生播放自环。导入兼容层在不改变音乐语义的前提下规范化为 `stop, start`，并同时保护已有旧修订。
