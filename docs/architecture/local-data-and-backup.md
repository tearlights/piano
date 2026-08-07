# 本地数据、结构化乐谱与备份规范（比赛 MVP）

## 存储边界

原始琴谱、结构化乐谱、派生练习版本和演奏记录均保存在 App 私有目录；数据库只保存稳定 UUID、相对路径、关系和元数据。用户移动外部原文件后，练习资料仍可用。

## 逻辑实体

- `Score`：稳定 UUID、标题、作者、格式、相对文件路径、创建/更新时间。
- `Folder`、`Tag`：组织曲谱。
- `ReadingState`：曲谱 UUID、页码、缩放、偏移、阅读模式、更新时间。
- `Bookmark`：曲谱 UUID、页码、名称、创建时间。
- `MetronomePreset`：名称、BPM、拍号、音量。
- `ScoreStructure`：原谱 UUID、结构化格式版本、MusicXML/MIDI 相对路径、OMR 置信度、校正状态、位置映射版本。
- `PracticeVersion`：稳定 UUID、原谱 UUID、派生来源、改编参数、相对文件路径、创建/更新时间、撤销关系。
- `PracticeSegment`：结构化乐谱 UUID、小节范围、左右手、速度、循环和练习目标。
- `PerformanceSession`：练习版本/选段 UUID、MIDI 设备摘要、速度、开始/结束时间和本地演奏事件路径。
- `PerformanceFeedback`：会话 UUID、匹配算法版本、小节/拍点定位、音高/节奏/漏音/多音结果与可执行建议。

UUID 是所有关系和恢复匹配的唯一依据，文件名不是标识。`ScoreStructure` 必须记录它对应的原谱版本；派生版本不得覆盖原谱。演奏反馈仅能关联到已校正或满足置信度门槛的结构化乐谱。

## .gpiano 备份包

`.gpiano` 是 ZIP 容器：

```text
manifest.json          格式版本、创建时间、校验信息
library.json           曲谱元数据、文件夹、标签、阅读状态、书签、预设
scores/<uuid>.<ext>    原始琴谱文件
structures/<uuid>/     MusicXML/MIDI、位置映射、校正信息
versions/<uuid>/       派生练习版本及其差异元数据
performance/<uuid>/    本地演奏事件与匹配反馈（用户选择导出时包含）
annotations/           批注与用户标记
```

导入采用“先校验、后写入、最后提交数据库”的事务式流程。格式版本必须可迁移，未知版本要提示用户而非强行导入。恢复时按原谱、结构化乐谱、派生版本、练习状态和演奏记录的依赖顺序恢复；任一可选项损坏不得破坏原谱及既有资料。

## 未来同步

未来的同步或 WebDAV 必须保持上述实体与 UUID 语义。模型服务传输与跨设备同步属于独立授权能力；冲突处理必须保留用户副本，默认不静默覆盖。
