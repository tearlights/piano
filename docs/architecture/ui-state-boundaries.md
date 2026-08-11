# UI 状态边界（比赛 MVP）

更新日期：2026-08-12

状态：目标状态设计；当前只实现部分 Room 数据与页面内 Compose 状态。

当前尚未引入 DataStore、WorkManager、结构化乐谱/练习版本/反馈实体或稳定的工作区状态恢复。下表用于约束后续实现，不能当作现有能力清单。

## 原则

界面状态与持久化数据分离。页面旋转、短暂后台切换和进程重建不能导致用户失去当前选段、识谱校正、播放进度、练习版本、导入任务或 MIDI 练习状态。

## 状态归属

| 状态 | 保存位置 | 示例 |
| --- | --- | --- |
| 曲谱、结构化乐谱、版本、书签、阅读位置、反馈摘要 | Room 数据库 | `Score`、`ScoreStructure`、`PracticeVersion`、`PerformanceFeedback` |
| 用户全局偏好 | DataStore | 默认阅读模式、主题、播放偏好、MIDI 偏好与授权状态 |
| 仅本次页面交互 | ViewModel/SavedStateHandle | 工具栏、当前选段、练习面板、设备连接面板 |
| 导入、OMR 与缩略图任务 | WorkManager + 数据库状态 | `Pending`、`Running`、`Failed`、`Ready`、`NeedsCorrection` |
| MIDI 演奏事件 | App 私有文件 + 数据库摘要 | 原始事件流、会话、匹配结果 |
| PDF 页图缓存 | App 缓存目录 | 已渲染页面位图 |

## 练习工作区状态机

```text
Loading → OriginalReady → StructureReady ↔ PracticePanelOpen
   │            │                ├→ Playing
   │            │                ├→ EditingVersion
   │            │                ├→ MidiConnecting → MidiReady → Matching
   │            │                └→ Error (可恢复)
   └→ Error
```

页码、缩放、选段、速度、循环、练习版本和稳定的校正结果应防抖保存；离开练习工作区时必须立即落盘。MIDI 事件流应追加写入私有文件，避免高频事件直接阻塞数据库。

## 导入状态机

```text
Selected → Validating → Copying → Indexing → OriginalReady → OMRRunning
                                                     ├→ StructureReady
                                                     ├→ NeedsCorrection
                                                     └→ Failed (保留错误原因和重试入口)
```

`OriginalReady` 的曲谱可进入原谱阅读；只有 `StructureReady` 的内容可进入播放、改编和 MIDI 匹配流程。任何失败路径都不得删除已存在的曲谱或其元数据。
