# 2026-08-25 练习工作区体验交接

## 已完成：工作区 MIDI 会话

- `WorkspaceMidiSession` 统一持有连接、录制目标、采集进度、保存结果和最近记录。
- `MidiPracticeSheet` 不再拥有或销毁 `MidiPracticeController`，开始录制后会收起并回到谱面。
- 谱面底部在录制期间显示紧凑控制栏；离开练习工作区前有完成、放弃、留下三种明确选择。
- 针对性 JVM 测试和 Kotlin 编译通过；尚待后续统一真机验收。

## 已完成：实时 MIDI 反馈

- 一小节倒计时后按固定速度时间轴开始；录制时间以选段起点为零，不再等待首个按键才启动。
- `IncrementalPerformanceMatcher` 使用最终 `PerformanceMatcher` 的关联与节奏容差，每 80ms 重算当前采集前缀。
- 工作区按当前小节自动跟随，谱面覆盖层用黄/绿/红标记目标、正确和错误/漏音，多音也显示为红色。
- 针对性匹配测试通过；尚待统一真机验收颜色、滚动和物理 MIDI 延迟。

## 已完成：双头小节选段

- 顶部和试听设置中的横向小节 Chip 已替换为双头 `RangeSlider`。
- 起止数字、两端精调、“当前小节”和“全篇”均已接入统一选段。
- `selectionStartMeasure`、`selectionEndMeasure`、`focusedMeasure` 已拆分；拖动结束后才滚动谱面。
- 长谱范围规则测试和 Debug 构建通过，待真机核对触控精度与纵向占用。

## 后续

1. 将 alphaTab 谱面点击命中的小节回传到统一选段状态。
