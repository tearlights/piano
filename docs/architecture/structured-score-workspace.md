# 结构化乐谱工作区：技术方案

更新日期：2026-08-13

状态：单页 OMR、原谱对照、alphaTab 渲染、多类结构校正、主谱分支修订、片段播放、本地/授权 AI 指导、可编辑派生练习版本、MIDI 反馈与导出均已通过。

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

- 正式工作区从曲谱库中的已完成识别任务打开持久化 MusicXML；没有结构时不展示测试占位谱。
- `MusicXmlScoreParser` 已提取最小 `ScoreIR` 与确定性事件标识，并以出现顺序建立 1～24 的页面小节索引。
- alphaTab 1.8.3 已在 Android 设备渲染双谱表、调号、拍号、三连音和连线；顶部小节选择可滚动到 alphaTab 主小节边界。
- 页面已压缩非谱面信息，支持原谱/练习谱切换，并可修改音高、时值、音符/休止符和延音线，重新生成 MusicXML、重刻谱及浏览分支修订。
- Room v11 已持久化结构化乐谱、主谱修订、识别任务、派生版本及独立修订、跟弹尝试和原始 MIDI 事件，`.gpiano` v6 已覆盖这些资料并兼容 v1～v5；OMR 像素位置映射尚未接入。
- 片段试听已支持连续小节、50%/75%/原速、循环、暂停/继续/停止、双手/右手/左手和播放小节/节拍光标；1～2 小节 50% 循环已完成实机边界验证。
- 独立 `omr-service/` 已通过 bearer token 调用 Audiveris 5.11.0；真实单页图片已完成上传、识别、MusicXML/ScoreIR 校验、待校正草稿建立、渲染、试听和修订。
- `ScorePracticeAnalyzer` 已为当前选段生成带小节/事件证据的练习说明与可执行试听参数；`practice-ai-service` 在用户逐次授权后只接收最小化摘要，并双重校验模型回答的证据、试听参数和练习版本类型。
- 派生版本提供右手/左手分手、降低跨距三种白名单方案，独立 MusicXML 候选、事件差异、试听、采纳/拒绝与 MusicXML 导出；候选可继续确定性校正并使用自己的历史、撤销/重做，始终不改变主谱当前修订。
- `PlaybackPlan` 已同时驱动 alphaTab 播放、MIDI 匹配与标准 MIDI 文件导出。屏幕测试输入完成了反馈/持久化流程验证；真实物理电钢琴兼容性属于进阶验证。

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

配套测试 XML 说明了此层的必要性：它将右手与左手写成两个 `part`，每个 `part` 各有 24 个小节，且 XML 中的小节号均为 `0`。适配器按出现顺序建立稳定的内部索引 1～24，不能把原始 XML 的 `number="0"` 当作编辑定位键。真实 Audiveris 输出则使用单个钢琴 `part` 与两个 staff；同一 `ScoreIR` 已兼容两种组织方式。

规范化还须处理：`divisions`、`backup`/`forward`、多声部、和弦、三连音、连音、调号、拍号和缺失的方向信息。Audiveris 的中间延音音符会合法地排列为 `start, stop`，但 alphaTab 1.8.3 会建立循环引用；兼容层只将其语义等价地排序为 `stop, start`，不删除或猜测连线。原始 OMR 修订仍保留，读取和后续修订使用规范化结果。

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

第一版已允许的编辑包括音高、常用/附点/三连音时值、独立音符与休止符互转、相邻同音延音线。左右手归属、拍号、调号及和弦结构是后续校正范围。首期不做制谱软件级的自由拖拽、任意排版和复杂跨谱表图形编辑。

渲染图形不是编辑数据源。用户修改的是 `ScoreIR`，SVG/Canvas/原生视图只反映修改后的结果。

### 4.3 渲染方案

渲染器通过 `ScoreRenderer` 接口隔离：

```text
ScoreIR → MusicXML → ScoreRenderer → RenderedScore
```

候选方案：

| 方案 | 能力 | 结论 |
| --- | --- | --- |
| alphaTab | 可加载 MusicXML，渲染标准记谱和钢琴大谱表，并提供基于 SoundFont 的播放能力 | 1.8.3 已承担当前 Android 刻谱与片段播放；渲染、定位、变速、循环、分手和光标均已实机验证 |
| OSMD | 浏览器/WebView 内的 MusicXML 渲染器，可输出 SVG | 作为纯渲染备选；不作为完整编辑器或稳定播放引擎依赖 |
| Verovio | 高质量记谱排版 | 作为后续渲染备选；需另接播放与编辑层 |

alphaTab 官方说明其支持 MusicXML、钢琴大谱表、Android Canvas 与基于 SoundFont2 的播放。[alphaTab](https://github.com/CoderLine/alphaTab)

OSMD 的定位是 MusicXML 渲染器，不是完整交互编辑器；可作为 WebView 渲染实现，但不应承载产品的数据与编辑逻辑。[OSMD](https://github.com/opensheetmusicdisplay/opensheetmusicdisplay)

### 4.4 可追溯练习指导

当前 `ScorePracticeAnalyzer` 先在本地从选区 ScoreIR 提取三连音、时值、连线、调号、左右手共同落点、和弦跨度与同手跳进。输出不是一段不可核对的泛化文字，而是：

```text
结构事实 → 小节/事件证据 → 解释 → 三步练法 → 可执行试听参数
```

生成式模型后续只能读取这份最小化摘要并做解释、排序或回答用户问题；模型不能把没有证据的音符、指法或音乐结论写回主谱。

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
2. **已完成：** 建立最小 `ScoreIR` 和稳定音符标识，明确测试 XML 两个 `part`、小节和渲染位置之间的映射。
3. **已完成首版：** 已完成“选中事件 → 修改音高/时值/休止符/延音线 → 校验 → 生成 MusicXML → 重新渲染 → Room 保存 → 浏览分支历史/跨进程恢复/撤销/重做”。
4. **已完成：** 选中连续小节，完成慢速循环播放、左右手筛选、小节/节拍光标、暂停、继续和停止。
5. **已完成主链路：** 曲谱库单页图片通过持久任务进入 Audiveris companion，只有 MusicXML/ScoreIR 校验通过后才创建待校正结构；失败、取消、重试、进程恢复、整页原谱对照和 v5 备份均已接入。可验证的小节/事件像素位置映射待完成。

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
