# 2026-08-24 Hooray 稳定性与体验修复

## alphaTab 播放时间轴

- 修复不规则 MusicXML 在后段小节无法试听的问题。
- 播放器不再比较 ScoreIR 与 alphaTab 的绝对 tick；手机试听的选段、跳转和循环边界统一使用 alphaTab 当前乐谱时间轴。
- 保留小节映射的越界、重复索引和无效范围检查。
- MIDI 跟弹目标与标准 MIDI 导出继续使用确定性的 `PlaybackPlan`，不把 alphaTab 私有时间轴写入持久化数据。
- 修复全篇播放时 alphaTab Android 内部滚动产生负动画时长并导致 App 崩溃的问题；关闭缺陷动画，由 Gpiano 在小节变化时安全跟随横/纵位置，保留播放游标和音符高亮。

## AI 服务安全

- 模型 provider 地址只允许解析到公网的 HTTPS 端点；HTTP loopback 仅能在显式开发开关下使用。
- 禁止 provider HTTP 重定向，避免跨主机重定向携带模型 API 密钥，并消除重定向后的内网 SSRF 路径。

## 本地数据库升级

- 修复 v1-v6 存量数据库升级时缺少 `scores.lastOpenedAt`、进入曲谱库后查询崩溃的问题。
- 数据库打开时显式启用 SQLite 外键，删除曲谱后关联结构、识别任务与练习数据会按 Room 声明级联或置空。
