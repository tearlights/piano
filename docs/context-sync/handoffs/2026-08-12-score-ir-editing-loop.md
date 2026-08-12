# 2026-08-12：ScoreIR 与音高校正闭环交接

## 已完成

- 新增 ADR-0010，确定“原 MusicXML DOM 无损承载 + 最小 ScoreIR 计算投影 + 受约束局部编译”的编辑路线。
- 使用完整测试 MusicXML 建立声部、小节、事件、音高、时值、声部时间、手别、和弦、休止、三连音、连音线和圆滑线投影。
- 以声部顺序、小节出现顺序和 note 出现顺序建立确定性事件标识；源文件中重复的小节编号不会造成标识冲突。
- 工作区支持从选中小节选择音符，修改音名、升降号和八度，编译并校验新的 MusicXML，再交给 alphaTab 重刻谱。
- `ScoreEditingSession` 支持进程内修订号和撤销，原始 XML 不会被原地覆盖。

## 验证证据

- `:app:testDebugUnitTest`：5 项测试通过，覆盖真实 XML 解析、单音局部修改、alter 节点增删、无效目标保护和撤销。
- `:app:assembleDebug`：通过。
- Android 设备 `DXA800A5LAWBJ509`：首音 F♯4 改为 G4 后修订号从 0 变为 1，alphaTab 重新载入新 XML；撤销后修订号与首音恢复。
- Android DOM 实现不支持部分 DocumentBuilder 属性，现以兼容性保护设置；外部实体和 DTD 解析仍被禁用。

## 明确边界

- 当前只支持音高校正，未支持时值、休止转换、结构增删或跨声部移动。
- 修订历史只在内存中，退出页面或进程后会丢失；尚未接入正式曲谱实体。
- 测试 XML 仍来自 Debug asset，不能将其表述为 OMR 已完成。

## 下一工作单元

在不破坏 Room v6 与 `.gpiano` v1 导入的前提下，设计并实现 `ScoreStructure` / `ScoreRevision` 持久化、事务恢复和新备份格式；随后补时值校正与片段播放。
