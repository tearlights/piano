# UI 状态边界（比赛 MVP）

更新日期：2026-08-13

状态：当前实现与后续状态边界。

已落地 Room 结构化实体、WorkManager OMR 任务、派生版本、MIDI 尝试与工作区最近选择恢复。DataStore 和完整的选段/播放偏好恢复尚未引入；下表同时描述已实现归属与后续要求。

## 原则

界面状态与持久化数据分离。页面旋转、短暂后台切换和进程重建不能导致用户失去当前选段、识谱校正、播放进度、练习版本、导入任务或 MIDI 练习状态。

## 状态归属

| 状态 | 保存位置 | 示例 |
| --- | --- | --- |
| 曲谱、结构化乐谱、主谱修订、派生版本及其独立修订、书签、反馈摘要 | Room 数据库 | `Score`、`ScoreStructure`、`ScoreRevision`、`PracticeVersion`、`PracticeVersionRevision`、`PracticeAttempt` |
| 用户全局偏好与最近工作区 | SharedPreferences / 后续 DataStore | `WorkspaceSelectionStore` 已同步保存最近结构 ID；其余阅读/播放偏好待迁移到 DataStore |
| 仅本次页面交互 | Compose 状态 / 后续 SavedStateHandle | `selectionStartMeasure`、`selectionEndMeasure`、`focusedMeasure`、工具面板和播放参数；MIDI 连接由工作区会话持有 |
| 导入、OMR 与缩略图任务 | WorkManager + 数据库状态 | `Pending`、`Running`、`Failed`、`Ready`、`NeedsCorrection` |
| MIDI 演奏事件 | Room | `MidiPerformanceEvent` 与 `PracticeAttempt` 同事务保存，保证尝试汇总与原始按键对应 |
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

主谱校正、派生版本、尝试和原始 MIDI 按键已在提交时落盘；最近工作区已验证跨进程恢复。页码、缩放、选段、速度、循环与设备偏好尚未持久化。当前每次练习完成后批量写入 MIDI 事件，避免录制过程中频繁写数据库。

工作区选段与查看焦点互相独立：所有试听、指导、AI、练习版本、跟弹和 MIDI 导出只读取 `selectionStartMeasure..selectionEndMeasure`；谱面滚动与校正读取 `focusedMeasure`。双头滑块拖动时只更新范围，拖动结束或精调按钮点击后才更新焦点并滚动。

## 导入状态机

```text
Selected → Validating → Copying → Indexing → OriginalReady → OMRRunning
                                                     ├→ StructureReady
                                                     ├→ NeedsCorrection
                                                     └→ Failed (保留错误原因和重试入口)
```

`OriginalReady` 的曲谱可进入原谱阅读；只有 `StructureReady` 的内容可进入播放、改编和 MIDI 匹配流程。任何失败路径都不得删除已存在的曲谱或其元数据。
