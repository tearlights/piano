# 2026-08-13：核心闭环集成与文档同步交接

## 当前可运行状态

Gpiano 已具备以下端到端路径：单页图片 OMR → MusicXML/ScoreIR 草稿 → 原谱对照与校正 → 片段播放 → 本地/授权 AI 指导 → 独立练习版本 → MIDI 事件反馈 → MusicXML/MIDI/完整资料库导出恢复。

当前数据版本为 Room v10、`.gpiano` v5。已验证真实设备为 `DXA800A5LAWBJ509`；无需清数据的覆盖安装、强制结束进程恢复、v5 备份往返均已完成。

## 本次实现

- `WorkspaceSelectionStore`：保存最近结构化谱 ID，工作区跨进程恢复。
- `StructuredScoreRepository.export`：导出主谱当前修订 MusicXML。
- `StandardMidiFile`：从 `PlaybackPlan` 导出 SMF format 0；新增两项 JVM 测试。
- `PracticeAiSheet`：候选生成失败显示原地错误与恢复说明，成功后再关闭 AI 面板并打开版本审阅。
- AI 证据必须至少绑定一个小节和一个已发送事件；服务端与客户端一致执行。
- ADR-0017 规定下一步为派生版本新增独立修订链，不允许借用主谱修订表。

## 验证

- JVM 测试 24 项、AI companion Python 测试 4 项、Debug 构建通过。
- 主谱 MusicXML 导出为可解析的 125,801 字节文件。
- MIDI 导出文件经独立解析：format 0 / 1 track / 960 PPQ / 正确 tempo / 延音不重复起音。
- `.gpiano` v5 包与 manifest 核验后在 App 恢复；数据库恢复后包含主谱、派生版、1 次屏幕测试跟弹记录和 6 条 MIDI 事件，`foreign_key_check` 无输出。

## 未完成且不可夸大

- 派生版本可生成、审阅、采纳/拒绝、导出，但尚不能在候选内继续人工校正或撤销；按 ADR-0017 实施后升级 v11/v6。
- 屏幕测试输入不代表真实 MIDI 电钢琴。物理设备连接改为进阶兼容性验证。
- AI 测试使用隔离假模型，只证明安全协议和 UI，不证明模型质量。
- OMR 仅验证清晰单页图片；没有多页/PDF 或事件像素坐标。
- 真实学习者完整流程验证尚未做。

## 推荐下一动作

先由项目负责人决定：继续实现 ADR-0017 的派生版编辑闭环，还是先暂停开发并做两位真实学习者体验验证。无论选择哪条，物理 MIDI 设备兼容性不应作为当前阻塞项。
