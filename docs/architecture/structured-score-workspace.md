# 结构化乐谱工作区：技术方案

更新日期：2026-08-12

状态：方案基线；alphaTab 渲染 Spike 已通过，编辑、持久化和播放仍待验证。

## 1. 要实现什么

Gpiano AI 不做图片琴谱的简单查看或一次性识别。目标是将用户自有或已获授权的静态琴谱，转化为可理解、可编辑、可播放并可与真实演奏对齐的个人练习材料。

```text
图片/PDF
  → OMR
  → MusicXML 草稿
  → 内部结构化乐谱（ScoreIR）
  → 新排版的可视乐谱
  → 用户编辑 / AI 编辑计划
  → 校验、重新生成 MusicXML
  → 播放、解释、派生练习版本、MIDI 跟弹
```

渲染后的乐谱不需要像素级复刻原始图片。原图是输入与追溯依据；结构化乐谱是后续练习、编辑和演奏反馈的工作对象。

### 当前已验证边界

- Debug 工作区通过 `MusicXmlSource` 读取配套测试 MusicXML；OMR 尚未接入。
- `MusicXmlInspector` 已提取标题、声部、小节数、调号和拍号摘要，并以出现顺序建立 1～24 的页面小节索引。
- alphaTab 1.6.1 已在 Android 设备渲染双谱表、调号、拍号、三连音和连线；顶部小节选择可滚动到 alphaTab 主小节边界。
- 页面已压缩非谱面信息，但当前选择只负责定位，不代表已建立可编辑的 `ScoreIR`、音符选中或播放选区。
- 当前未保存结构化乐谱、修订版或位置映射；既有 Room 与 `.gpiano` 备份格式未改变。

## 2. 数据与互操作性边界

| 层 | 数据 | 用途 | 是否为用户可带走的格式 |
| --- | --- | --- | --- |
| 原始谱面 | PDF、PNG、JPG 等 | 输入、追溯、重新识别 | 是，原文件保留 |
| 记谱主文件 | MusicXML 4.0（`.musicxml` / `.mxl`） | 编辑、交换、再次导入 | 是，主交换格式 |
| 播放与设备数据 | Standard MIDI File（`.mid`）与 MIDI 消息 | 播放、外接电钢琴、演奏记录 | 是，按需导出 |
| 内部模型 | `ScoreIR`、修订记录、AI `EditPlan` | 可靠编辑、校验、版本管理 | 否；可重新导出为标准格式 |
| 显示缓存 | SVG、Canvas 或原生绘制结果 | 画谱、高亮、点击 | 否；不可作为唯一数据源 |

MusicXML 由 W3C Music Notation Community Group 维护，是跨记谱软件的交换格式；`.mxl` 是其标准压缩封装。[MusicXML 4.0](https://www.w3.org/2021/06/musicxml40/) [MXL 格式](https://www.w3.org/2021/06/musicxml40/tutorial/compressed-mxl-files/)

MIDI 是电子乐器和音乐软件之间的标准通信与文件格式；它用于播放和演奏事件，不替代 MusicXML 的记谱语义。[MIDI 规格](https://midi.org/specs)

### 兼容性规则

1. 任何用户可编辑的乐谱必须能导出 MusicXML；不能把渲染器私有格式作为唯一保存结果。
2. 任何可播放片段应能生成标准 MIDI 事件，并在用户需要时导出 `.mid`。
3. 不保存 SVG 作为乐谱版本；它只是当前 MusicXML 的显示结果。
4. 内部 `ScoreIR` 与 AI 编辑计划可以演进，但不得破坏 MusicXML/MIDI 导入导出。
5. 高级排版与特殊记号在不同软件中可能显示不同；目标是语义、时值和结构可交换，不承诺像素级一致。

## 3. 从 OMR 输出到可编辑乐谱

### 3.1 保留三份内容

```text
SourceScore       原始图片/PDF，永不被覆盖
RecognitionDraft  OMR 原始 MusicXML 与置信度/错误信息
ScoreRevision     用户或 AI 校正后的结构化结果及其 MusicXML 导出
```

用户始终可以查看原谱、回退到识别草稿，并比较不同练习版本。

### 3.2 导入规范化

OMR 输出不一定适合直接编辑。导入适配器负责将其转为统一的 `ScoreIR`：

```text
ScoreIR
 └─ Part（如 Piano）
     ├─ Staff（右手、左手）
     └─ Measure（内部连续索引）
         └─ Event（音符、和弦、休止符、方向、连线、三连音等）
```

当前测试 XML 说明了此层的必要性：它将右手与左手写成两个 `part`，每个 `part` 各有 24 个小节，且 XML 中的小节号均为 `0`。适配器需按出现顺序将同序号小节配对为钢琴双谱表，并赋予稳定的内部索引 1～24；不能把原始 XML 的 `number="0"` 当作编辑定位键。

规范化还须处理：`divisions`、`backup`/`forward`、多声部、和弦、三连音、连音、调号、拍号和缺失的方向信息。原始 XML 不修改；规范化结果作为新的修订版保存。

## 4. 渲染与编辑页面

### 4.1 页面职责

页面不展示 XML 文本。它让用户看到新排版的五线谱，并以小节、拍位和音符为单位编辑结构化数据。

```text
顶部：曲名、修订版本、保存状态、原谱/练习谱切换
主体：可缩放、可滚动的结构化双手五线谱
上下文面板：当前小节、当前拍位、音符/和弦、编辑操作、AI 操作
底部：播放、速度、循环、左右手切换
```

校正面板只在选中小节或音符时出现，不以常驻控件遮挡大面积乐谱。

### 4.2 编辑模型

```text
点击渲染谱中的小节
→ 按内部 measureIndex 选中 ScoreIR 小节
→ 按右手/左手、声部、拍位选择目标音符或和弦
→ 修改 ScoreIR
→ 生成新的 MusicXML 修订版
→ 重新渲染
```

第一版允许的编辑：音高、时值、休止符、左右手归属，以及拍号、调号等关键元信息。首期不做制谱软件级的自由拖拽、任意排版和复杂跨谱表图形编辑。

渲染图形不是编辑数据源。用户修改的是 `ScoreIR`，SVG/Canvas/原生视图只反映修改后的结果。

### 4.3 渲染方案

渲染器通过 `ScoreRenderer` 接口隔离：

```text
ScoreIR → MusicXML → ScoreRenderer → RenderedScore
```

候选方案：

| 方案 | 能力 | 结论 |
| --- | --- | --- |
| alphaTab | 可加载 MusicXML，渲染标准记谱和钢琴大谱表，并提供基于 SoundFont 的播放能力 | 已选为当前 Android Debug 刻谱实现；渲染与小节定位已验证，播放仍须独立验收 |
| OSMD | 浏览器/WebView 内的 MusicXML 渲染器，可输出 SVG | 作为纯渲染备选；不作为完整编辑器或稳定播放引擎依赖 |
| Verovio | 高质量记谱排版 | 作为后续渲染备选；需另接播放与编辑层 |

alphaTab 官方说明其支持 MusicXML、钢琴大谱表、Android Canvas 与基于 SoundFont2 的播放。[alphaTab](https://github.com/CoderLine/alphaTab)

OSMD 的定位是 MusicXML 渲染器，不是完整交互编辑器；可作为 WebView 渲染实现，但不应承载产品的数据与编辑逻辑。[OSMD](https://github.com/opensheetmusicdisplay/opensheetmusicdisplay)

## 5. AI 如何编辑乐谱

AI 不直接输出或改写整段 MusicXML。它读取选中范围的结构摘要，并返回受约束、可校验的 `EditPlan`。

```text
用户："将第 5～8 小节右手简化，保留旋律"
→ 提取该范围 ScoreIR
→ AI 返回 EditPlan JSON
→ 规则编译器执行计划
→ 校验拍号、时值、音域、声部、连音与乐谱结构
→ 生成新的 ScoreRevision 与 MusicXML
→ 渲染差异预览
→ 用户采纳、继续修改或撤销
```

示例：

```json
{
  "range": { "fromMeasure": 5, "toMeasure": 8, "staff": "right" },
  "operations": [
    { "type": "keepMelody" },
    { "type": "removeInnerVoice" },
    { "type": "reduceChord", "maxNotes": 3 }
  ]
}
```

规则编译器是安全边界：AI 的计划不通过校验就不能写入乐谱。所有 AI 修改都必须生成新修订版、展示差异并可撤销。

## 6. 播放、循环与 MIDI

### 6.1 统一时间轴

`ScorePlayer` 将选中乐谱范围编译成 `PlaybackPlan`：

```kotlin
data class NoteEvent(
    val startTick: Long,
    val durationTick: Long,
    val midiPitch: Int,
    val velocity: Int,
    val staff: Staff,
    val measureIndex: Int,
    val noteId: String,
)
```

它负责音高、调号、时值、三连音、和弦、连音、速度、循环和左右手筛选。播放时间轴同时驱动声音、谱面高亮和未来的 MIDI 跟弹对齐。

### 6.2 输出方式

| 输出 | 用途 | 技术边界 |
| --- | --- | --- |
| App 内钢琴音色 | 手机直接播放 | 可使用 SoundFont 合成；音色许可、包体积与延迟需验证 |
| USB/蓝牙 MIDI 电钢琴 | 电钢琴发声、设备演示 | Android MIDI API 发送带时间戳的 MIDI 消息 |
| MIDI 文件导出 | 外部播放器、DAW、其他音乐软件 | 输出标准 `.mid`，不替代 MusicXML |

Android MIDI API 支持 USB、Bluetooth LE 和软件路由；可用于连接键盘、发送 MIDI 事件和接收跟弹输入。[Android MIDI API](https://developer.android.com/reference/android/media/midi/package-summary)

FluidSynth 是可选的跨平台 SoundFont 合成引擎；引入前须评估 Android 集成、音色包许可、体积与 LGPL 合规。[FluidSynth](https://github.com/FluidSynth/fluidsynth)

## 7. MVP 实施顺序与验收

### 技术 Spike 与后续顺序

使用当前测试 MusicXML 完成以下验证，先不接 OMR：

1. **已完成：** 用 alphaTab 渲染测试 MusicXML，核对双手、和弦、三连音和连音；按内部小节序号完成滚动定位。
2. **下一步：** 建立最小 `ScoreIR` 和稳定音符标识，明确测试 XML 两个 `part`、小节和渲染位置之间的映射。
3. **下一步：** 选中一个小节或音符，完成一次“改音高/时值 → 校验 → 生成 MusicXML → 重新渲染 → 保存修订版”。
4. **随后：** 选中一段，完成慢速循环播放、左右手筛选与小节/音符高亮。
5. **工作区闭环稳定后：** 通过 `MusicXmlSource` 的替换实现接入 OMR，并补充原谱位置映射、置信度和失败恢复。

### 通过标准

- 原始 XML、规范化修订版与用户修改版可独立保存；
- 用户不需要面对 XML 文本，即可定位并修改一个音符或时值；
- 修改后 MusicXML 可再次导入并通过格式校验；
- 选段能按用户设置的速度、左右手与循环方式播放；
- 失败时原谱阅读能力不受影响，并有明确重试或回退路径。

## 8. 当前不做

- 不做任意图片与复杂多页谱的泛化识别承诺；
- 不做制谱软件级的自由图形编辑和像素级原图复刻；
- 不让 AI 直接修改 XML 或在未校正草稿上生成确定性结论；
- 不依赖某个渲染/播放库的私有文件格式；
- 不把用户原谱、演奏记录或授权不明素材默认上传。
