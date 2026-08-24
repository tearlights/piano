# ADR-0010：采用保真 MusicXML 修订编译器与最小 ScoreIR

日期：2026-08-12

状态：已接受

## 决策

结构化乐谱编辑采用“MusicXML 保真载体 + ScoreIR 可计算投影 + 受约束修订操作”的组合，而不是从当前有限模型重新生成整份乐谱。

```text
原始 MusicXML
→ MusicXmlScoreParser
→ ScoreIR（小节、声部、音符、休止符、时间位置、稳定元素 ID）
→ CorrectionOperation
→ MusicXmlRevisionCompiler
→ 校验后的新 MusicXML 修订版
→ 重新解析 ScoreIR 并交给 alphaTab 渲染
```

首个可验证编辑范围是音高；随后在保持同一接口的前提下增加时值、休止符和关键元信息校正。

## 保真规则

1. 原始 MusicXML 永不原地覆盖，每次修改产生新的 XML 字符串或修订文件。
2. 编译器只修改操作明确指向的节点；当前 ScoreIR 尚未建模的排版、连线、三连音、方向、力度等 XML 节点原样保留。
3. 编译后必须重新解析并确认目标元素的新值；失败时丢弃新结果，继续使用上一个有效修订版。
4. alphaTab 只消费修订后的 MusicXML，不作为编辑数据源。

## 稳定标识

当前输入缺少可靠的元素 ID，因此第一阶段以 `part 出现顺序 + measure 出现顺序 + note 出现顺序` 生成确定性 ID。该 ID 在修改音高、时值等不改变节点顺序的操作中稳定。

插入、删除或移动音符前，必须引入独立位置映射文件，为新元素分配 UUID，并记录旧 ID 到新 ID 的关系；不得依赖 MusicXML 中可能重复的小节号。

## ScoreIR 最小范围

- 曲名、声部、调号、拍号和当前 `divisions`；
- 内部连续小节索引和原始小节号；
- 音符/休止符、和弦归属、声部、谱表、起始 tick、时长 tick；
- 音高的 step、alter、octave 和可计算 MIDI 音高；
- 左手、右手或未知的练习归属；
- 只读保留三连音、连音等后续校验需要的状态摘要。

## 持久化与备份约束

实现补充（2026-08-25）：任何解析、编辑、派生或恢复得到的 ScoreIR 在持久化前必须拒绝非正时值、负 onset、`onset + duration` 溢出、事件声部/小节索引错位，以及同一 voice/staff 中非和弦起音的时间重叠。`divisions`、拍数和拍号分母必须为正；播放时标称小节 tick 的乘法从第一项开始使用 Long。

`ChangeDuration` 改变推进游标的主音符后，后续同 voice 事件允许按新时值顺延；目标组之后第一个 MusicXML `backup`/`forward` 必须按差值补偿，以保持其他 voice/staff 的绝对 onset。补偿后重新解析和验证，禁止以负控制时值或重叠结果持久化。

本 ADR 首先落地纯内存解析、修改和往返测试，不改变 Room schema。进入持久化时新增 `ScoreStructure`、`ScoreRevision` 与位置映射，并同时完成数据库迁移和 `.gpiano` 新格式；旧格式版本 1 必须继续可导入。

## 原因

- 当前测试 MusicXML 包含多声部、`backup`/`forward`、和弦、三连音与连线，若从不完整 ScoreIR 全量重写，会丢失尚未建模的音乐语义。
- 保真修改可以先完成可信的小范围人工校正，同时为更完整的模型逐步扩展留出空间。
- 确定性元素 ID 使 UI、AI 编辑计划、播放和 MIDI 后续可以引用同一目标。

## 后果

- MusicXML DOM 只存在于编译器内部，不进入 UI 或持久化公共模型。
- 所有修改都以 `CorrectionOperation` 表达，便于预览、撤销、测试和未来 AI `EditPlan` 复用。
- 当前确定性 ID 不支持无映射的结构插入/删除；实现这些操作前必须扩展位置映射与修订模型。
