# Hooray 稳定性与体验修复台账

开始日期：2026-08-24  
基线：`f2da0b516b12175e762e31f0eb1c3ce0616f4a15`  
工作分支：`Hooray`

本文记录真机问题和 `GPIANO-ISSUE.md` 代码审查项的原因、具体修复、验证证据与提交。状态只按实际完成情况更新。

## 播放与工作区体验

| 问题 | 原因 | 修复 | 验证 | 状态 |
| --- | --- | --- | --- | --- |
| 前段可试听、后段提示时间轴不一致 | 不规则 MusicXML 小节使 ScoreIR `PlaybackPlan` 与 alphaTab 累计 tick 分叉；控制器错误地要求两者起点完全相等 | 试听范围、跳转和循环只取 alphaTab `masterBars`；MIDI 目标仍使用 `PlaybackPlan`，仅以小节索引关联 | `AlphaTabPlaybackTimelineTest` 3 项通过；完整单测、构建和真机待执行 | 进行中 |
| 全篇播放约 20～30 秒后 App 崩溃 | alphaTab 1.8.3 Android 内部滚动在长横向谱面中计算出负动画时长，`Animation.setDuration` 抛异常；`OffScreen` 模式真机验证仍进入同一缺陷路径 | 关闭 alphaTab 内部滚动；Gpiano 仅在小节变化时通过横/纵 Android ScrollView 安全跟随，保留游标和高亮 | crash buffer 两次确认同一堆栈；第二版自动测试和全篇真机回归待执行 | 进行中 |
| 练习工作区直接打开最近谱且无选谱阶段 | 底部入口直接读取同步持久化的单个 `structureId`，工作区没有选谱、切换和恢复偏好 | 增加选谱/最近练习、切换乐谱、空状态、自动恢复设置 | 待实现 | 待处理 |

## `GPIANO-ISSUE.md` 修复范围

后续按独立可验证提交处理：

1. P0：AI provider SSRF/重定向密钥泄漏、Room 旧版本迁移、SQLite 外键、左右手判定。
2. 并发与性能：编辑防重入、派生版本幂等、书签/页序原子更新、工作区重组和阅读器 Flow 稳定。
3. 数据完整性：备份自导自拒、覆盖恢复语义、文件/数据库补偿、时值编辑与 ScoreIR 校验。
4. 服务和客户端健壮性：socket/线程限制、OMR 任务 TTL、MIDI 状态同步、路径安全、诊断脱敏、导入状态。
5. 低危与测试缺口：安全 XML、算术边界、MIDI 延音/SysEx、错误分类、MXL 限界、状态保存和死代码。

每项完成后在本文件追加修改文件、测试命令/结果和提交 SHA。

### AI provider SSRF 与密钥重定向

- 修复：模型端点启动校验会解析全部地址并拒绝非公网 IP；仅显式开发模式允许 HTTP loopback。provider 请求使用禁止重定向的 opener，授权头不会跟随 30x 发往其他目标。
- 测试：`python -m unittest discover -s practice-ai-service -p 'test_*.py'`，7 项通过，覆盖私网地址、开发 loopback 和禁重定向。
- 提交：本项提交完成后回填 SHA。

### Room v1-v6 升级缺少 `lastOpenedAt`

- 修复：在所有 v1-v6 升级路径必经的 `V6_TO_V7` 中增加 nullable `lastOpenedAt` 列；v7 及以后 schema 已包含该列，不重复修改。
- 测试：新增 `GpianoDatabaseMigrationTest` 直接执行迁移并核对 DDL；Android JVM 全量测试与 Debug 构建通过。
- 提交：本项提交完成后回填 SHA。

### SQLite 外键声明未执行

- 修复：Room 数据库每次打开时显式执行 `PRAGMA foreign_keys=ON`，使结构、识别任务、修订、练习版本和演奏事件的级联/置空约束生效。
- 测试：`GpianoDatabaseMigrationTest` 直接执行数据库 callback 并核对外键启用语句；Android JVM 全量测试与 Debug 构建通过。
- 提交：本项提交完成后回填 SHA。
