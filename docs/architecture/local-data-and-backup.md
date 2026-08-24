# 本地数据、结构化乐谱与备份规范（比赛 MVP）

更新日期：2026-08-13

状态：Room v11 与 `.gpiano` v6 已落地并完成真机迁移、导出和恢复验证。

## 当前存储边界

原始琴谱、结构化主谱、派生版本、演奏记录都保存在 App 私有目录或本地 Room。原始图片、完整 MusicXML 和原始 MIDI 按键不会默认上传；AI companion 只在逐次授权后收到当前选段的最小 ScoreIR 摘要和本地证据。

| 层 | 当前实现 | 关键规则 |
| --- | --- | --- |
| 原始谱面 | `scores/` 中的 PDF/图片及图片页 | 导入后独立于外部文件；OMR 或校正绝不覆盖原始文件 |
| 主结构化谱 | `structures/<structure>/revisions/*.musicxml` + `ScoreStructure` / `ScoreRevision` | 每次校正先校验、写临时文件，再事务切换当前修订 |
| 派生练习版 | 基础候选 `practice-versions/*.musicxml`、独立修订 `practice-versions/<version>/revisions/*.musicxml` + `PracticeVersion` / `PracticeVersionRevision` | 派生指针和历史独立于主谱；不改变主谱指针 |
| 跟弹记录 | `PracticeAttempt` + `MidiPerformanceEvent` | 汇总与原始按键同事务保存，屏幕测试输入须明确标识 |
| 任务与组织 | `RecognitionJob`、曲谱/页序/文件夹/书签 | 远端任务 ID 与 token 不导出 |

## 当前 Room 实体

- `Score`、`ScorePage`、`Bookmark`、`Folder`：本地曲谱资料与组织。
- `ScoreStructure`、`ScoreRevision`：OMR 草稿和用户主谱修订树。
- `RecognitionJob`：单页 OMR 的等待、上传、识别、失败、取消、重试与恢复状态。
- `PracticeVersion`、`PracticeVersionRevision`：白名单练习版本的来源、范围、计划、差异、状态、基础候选及独立修订链。
- `PracticeAttempt`、`MidiPerformanceEvent`：选段、手别、速度、输入类型、反馈汇总与原始按键证据。

Room 当前为 v11，v10→v11 迁移已在真实 v10 数据库上验证。新增数据模型必须同时提供 Room migration、旧备份导入、最新备份导出、文件校验和真机恢复验证。

## `.gpiano` v6

`.gpiano` 是有清单校验的 ZIP 包：

```text
manifest.json                              格式版本、每个数据文件大小与 SHA-256
library.json                               所有可恢复的 Room 元数据
scores/...                                 原始 PDF/图片或图片页
structures/<id>/revisions/...musicxml      主谱全部修订
structures/<id>/practice-versions/...xml   派生版本 MusicXML
structures/<id>/practice-versions/<version>/revisions/...musicxml  派生版本修订
```

v6 覆盖原始资料、收藏、文件夹、书签、页序、主谱修订、脱敏 OMR 任务、派生练习版本及独立修订、跟弹尝试和原始 MIDI 事件，并继续读取 v1～v5。

恢复流程为：解压到临时目录 → 校验 archive 路径、清单、大小、SHA-256、MusicXML、外键、范围和反馈统计 → 原子安装文件 → 单个 Room 事务写入 → 成功后删除回滚副本。任何已声明数据失配都会在写入前拒绝整包，避免“恢复成功”但练习关系已损坏。

真机 v6 往返证据：备份包通过 ZIP 和 manifest 校验，包含派生版本基础候选、修订元数据及修订 MusicXML；恢复后 Room v11 表计数一致、修订文件存在，`foreign_key_check` 无输出。

`library.json` 与 `manifest.json` 的导出和导入共享同一个 64 MiB UTF-8 字节上限。导出在打开目标流之前完成序列化并校验大小，因此 App 不会生成一个自身必然拒绝恢复的 `.gpiano` 包；超过上限时保留现有数据并明确报错。单个数据文件与解压总量仍分别受 256 MiB 和 1 GiB 限制。

识别任务进入备份前必须脱敏：清除服务端 `remoteJobId`、自由文本 `errorMessage` 和 `diagnosticsJson`，只保留本地关联、生命周期状态、阶段、重试次数和稳定错误分类 `errorCode`。恢复正在运行的任务时仍按既有规则标记为需要重新转换。

“恢复备份”采用覆盖语义，不是合并：校验与文件暂存成功后，在单个 Room 事务中按子表到父表顺序清空全部业务表，再按父表到子表顺序写入快照。空列表同样会清空对应旧数据。事务失败时数据库整体回滚、已替换文件由回滚副本恢复；事务成功后再尽力删除仅被旧快照引用的文件，删除失败最多留下不可达冗余文件，不影响新数据库引用。

## 当前未持久化状态

阅读页码与缩放、工作区选段、速度、循环、设备偏好和完整播放预设尚未形成持久实体。最近打开的结构化乐谱已由 `WorkspaceSelectionStore` 同步保存，并已验证跨进程恢复。

派生版本基础候选保持不可变；每次校正先生成并校验新文件，再在 Room 事务中插入修订和切换派生指针。历史切换不删除文件，损坏时优先沿父链、基础候选和最近有效修订恢复。
