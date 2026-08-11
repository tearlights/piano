# ADR-0009：以 alphaTab 完成结构化乐谱工作区 Debug 技术 Spike

日期：2026-08-11  
状态：已接受（仅限 Debug 技术验证）

## 决策

在不接入 OMR、Room 持久化、备份或 MIDI 设备的前提下，使用 alphaTab Android 依赖加载 `test-res` 中的 MusicXML，验证最小工作区。

1. 本地读取测试 MusicXML；
2. 解析标题、声部数、小节数、拍号与调号等结构摘要；
3. 在 Compose 页面中托管 alphaTab 原生 View，渲染两个识别声部；
4. 以稳定内部小节索引选择当前小节；
5. 通过 `MusicXmlSource` 接口预留未来 OMR 数据源。

测试资源仅加入 Debug 构建，不进入正式曲谱库、发布包或备份。

## 原因

- alphaTab 提供 Android 原生的 MusicXML 加载、标准记谱和播放能力，适合快速验证真实钢琴谱。
- 不修改现有 Room 数据库与备份格式，避免在未验证渲染器前锁定数据模型。

## 后果

- 本次工作区不承诺用户编辑、OMR、播放、MIDI 或正式版本保存已经完成。
- 已在连接设备验证测试 XML 可完成双轨解析和可见刻谱，包含钢琴双谱表、调号、拍号、三连音和连线；谱面区域可滚动。
- 工作区已隐藏重复谱名与音轨标签，并用紧凑标题、小节选择和单行状态保留主要谱面区域；第 2、3 小节已验证可按 alphaTab 主小节边界滚动定位。
- alphaTab 1.6.1 Android 的 `UiFacade.load` 对 `Score`、`ByteArray` 和 `InputStream` 均返回 `false`。工作区通过 alphaTab 的 `ScoreLoader` 直接解析原始 MusicXML，并调用 `renderScore` 绕过该加载器缺陷；原始 MusicXML 不被修改。
- 此适配仅隔离在 Debug 渲染入口，不将 alphaTab 私有对象作为用户数据。主交换格式仍是 MusicXML/MIDI；正式播放与编辑能力仍须在后续完成验证。
- 当前 `Uint8Array` 构造依赖反射，且主导航入口依赖 Debug-only 测试资源；正式发布前必须以可测试适配封装并接入正式数据源或隐藏入口。
