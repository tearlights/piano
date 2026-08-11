# 2026-08-11：结构化乐谱工作区 Debug Spike 交接

## 已完成

- 在底部导航新增“练习工作区”入口。
- 增加 `MusicXmlSource` 接口和 `AssetMusicXmlSource` Debug 实现；数据直接读取 `test-res/1785910774247300_654.musicxml`，不接 OMR。
- 增加 `MusicXmlInspector`，读取标题、识别声部、小节数、拍号和调号；小节选择使用出现顺序的内部索引，不依赖测试 XML 中重复的 `measure number="0"`。
- 增加 alphaTab Android 依赖；页面通过 `AndroidView` 托管 `AlphaTabView`，通过 `ScoreLoader` 解析测试 MusicXML 并传入两个 track index 渲染。
- 测试资源只配置为 Debug assets；未写入 Room、备份或正式曲谱库。

## 关键文件

- `app/src/main/java/com/gpiano/app/scoreworkspace/MusicXmlSource.kt`
- `app/src/main/java/com/gpiano/app/ui/screens/StructuredScoreWorkspaceScreen.kt`
- `app/src/main/java/com/gpiano/app/ui/navigation/GpianoApp.kt`
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `docs/decisions/ADR-0009-alphatab-debug-spike.md`

## 未完成与下一步

1. 已在连接设备验证 alphaTab 正确显示并可滚动浏览测试 MusicXML。alphaTab 1.6.1 的通用 `load` 入口会返回 `false`，当前以 `ScoreLoader`→`renderScore` 适配绕过该缺陷；后续需要将该适配提取为可测试的渲染器实现。
2. 后续建立 `ScoreIR` 与一次“修改音高 → 重生成 MusicXML → 重渲染”的闭环。
3. 再实现选段播放、循环和高亮；OMR、Room 持久化、备份迁移和 MIDI 跟弹均不在本次 Spike 范围。

## 验证状态

- 已完成 `:app:assembleDebug`，并在连接设备安装验证 MusicXML 数据入口、可见刻谱和谱面滚动可用。
- 已通过 `git diff --check`；构建通过临时可写 Gradle 缓存完成，默认 Kotlin daemon 缓存仍会输出只读警告后回退编译，不影响构建结果。
