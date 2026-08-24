# ADR-0012：以稳定版 alphaTab 承担刻谱与片段播放

日期：2026-08-12  
状态：已接受

## 背景

ADR-0009 使用 alphaTab 1.6.1 完成了 Android 刻谱技术验证。结构化工作区继续接入片段试听后，实机已经能够加载内置 SoundFont、合成 MusicXML 对应的声音并驱动谱面光标，但旧版本在播放范围终点、循环结束和 Android MusicXML 加载上存在已知缺陷。继续在应用层修补会让小节范围、光标和实际发声时间轴出现多套判定。

## 决策

1. 将 alphaTab 更新到 1.8.3，由同一个解析结果承担刻谱、MIDI 时间轴、SoundFont 合成、游标和片段循环。
2. 应用仍以标准 MusicXML 和内部 `ScoreIR` 作为数据边界；alphaTab 模型不进入 Room、备份或 AI 接口。
3. `PlaybackPlan` 负责验证用户选择、估算时长和左右手语义；真正的播放起止 tick 以 alphaTab 已加载乐谱的公开时间轴为准。
4. 左右手试听只在声部能够可靠映射为左右手轨道时开放；不能可靠拆分时明确失败，不猜测静音对象。
5. 升级后必须回归测试 XML 的双谱表、调号、拍号、三连音、连线、小节定位、音高校正重渲染，以及选段、变速、循环、暂停、继续和停止。
6. alphaTab 1.8.3 的 Android 产物使用 Kotlin 2.2 元数据并依赖 AndroidX Core 1.17，因此同步使用 Kotlin 2.2.20、KSP 2.2.20-2.0.4、Android Gradle Plugin 8.12.3、Gradle 8.13 与 `compileSdk 36`；`targetSdk` 暂时保持 35，不在本次播放变更中引入新的运行时行为。

## 原因

- alphaTab 同时提供 Android 原生刻谱和 alphaSynth 播放，能够让可视游标与声音共享时间轴。
- 1.8 系列修复了播放范围终点、循环结束、Android MusicXML 和播放游标相关问题，适合作为从技术验证进入可体验功能的升级基线。
- 保持 MusicXML/ScoreIR 边界后，即使未来更换渲染器或合成器，用户数据和修订历史仍可迁移。

## 后果

- alphaTab 的版本升级成为渲染与播放共同的回归点，不能只验证“能显示”。
- Android 播放仍依赖应用内 SoundFont，首次准备期间必须显示状态；失败后保留重新尝试和停止路径。
- 旧版为 `UiFacade.load` 使用的隔离适配暂时保留，待新版本公开加载入口通过测试后再删除，避免一次升级同时改变两个变量。
- 构建工具链同步升级，需用完整单元测试、Debug 构建和既有安装数据升级验证，防止把播放器升级问题与数据库迁移问题混淆。

## 2026-08-24 实现校正

真机导入的 MusicXML 出现了拍号标称长度与事件实际跨度不一致的不规则小节。`PlaybackPlan` 与 alphaTab 对该输入的累计结果不同，旧控制器在开始播放前比较两者起始 tick，导致前段可播、后段被“播放时间轴与结构化乐谱不一致”主动拒绝。

实现现已恢复本 ADR 第 3 条的边界：

- 手机试听的起点、终点、循环边界和跳转位置只读取 alphaTab 当前乐谱的 `tickCache.masterBars`；
- `PlaybackSelection` 只传递用户选择的小节索引、速度、循环和手别，不再把 `PlaybackPlan.rangeStartTick` 当作 alphaTab 时间轴断言；
- `PlaybackPlan` 继续作为 ScoreIR 驱动的 MIDI 匹配与标准 MIDI 导出时间轴，两条时间轴通过稳定小节索引关联，不混用 tick；
- alphaTab 小节缺失、重复、越界或范围无效时仍明确失败，不以猜测位置继续播放。

Android 依赖继续固定为 alphaTab 1.8.3。1.8.4 的 `AndroidAudioWorker.writeSamples` 会把未填满的固定缓冲尾部补零后整块写入 `AudioTrack`，与升级后出现的全局断续听感时间上吻合；实际生成 MIDI 未发现同通道同键的极短重复起音。崩溃改由自定义 `IScrollHandler` 隔离 alphaTab 缺陷滚动动画，不再通过升级音频实现解决。

谱面仍启用懒加载。Gpiano 转发 alphaTab render surface 的滚动监听，并在向上/向左回滑后补发布局与重绘，避免离屏 bitmap 回收后可见分片未恢复；不得再次用关闭懒加载作为修复，因为该设置已在真机导致整张谱面全白。
