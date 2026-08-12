# 2026-08-12：可追溯练习指导交接

## 已完成

- `PracticeAnalysis`、`PracticeGuidance`、`GuidanceEvidence`、`PracticeInstruction` 和 `RecommendedPlayback` 组成与界面解耦的指导结果。
- `ScorePracticeAnalyzer` 只依赖 ScoreIR，可在 JVM 测试；每条建议都有确定性 ID、范围、事件证据和可执行试听配置。
- 工作区指导面板复用当前选段与 `PlaybackPlanCompiler`，不会建立第二套播放时间轴。

## 验证

- `:app:testDebugUnitTest`：15 项通过。
- `:app:assembleDebug`：通过。
- Android 设备 `DXA800A5LAWBJ509`：真实 OMR 第 1 小节指导生成、滚动阅读、75% 双手循环与关闭面板后播放控制均可用。

## 下一工作单元

新增独立派生练习版本实体和备份格式：由白名单 EditPlan 生成候选 MusicXML，先展示事件差异和音乐取舍，再允许试听、采纳、拒绝或导出；不得移动原结构的当前修订指针。
