# Hooray 稳定性与体验修复台账

开始日期：2026-08-24  
基线：`f2da0b516b12175e762e31f0eb1c3ce0616f4a15`  
工作分支：`Hooray`

本文记录真机问题和 `GPIANO-ISSUE.md` 代码审查项的原因、具体修复、验证证据与提交。状态只按实际完成情况更新。

## 播放与工作区体验

| 问题 | 原因 | 修复 | 验证 | 状态 |
| --- | --- | --- | --- | --- |
| 前段可试听、后段提示时间轴不一致 | 不规则 MusicXML 小节使 ScoreIR `PlaybackPlan` 与 alphaTab 累计 tick 分叉；控制器错误地要求两者起点完全相等 | 试听范围、跳转和循环只取 alphaTab `masterBars`；导入后将实际跨度超过标称拍号的小节标记为不规则小节；MIDI 目标仍使用 `PlaybackPlan`，仅以小节索引关联 | 时间轴与真实 MusicXML/MIDI 回归通过；Android 全量测试和构建通过 | 已修复 |
| 全篇播放约 20～30 秒后 App 崩溃 | alphaTab Android 内部滚动在长谱面中计算出负动画时长，`Animation.setDuration` 抛异常 | 使用自定义空滚动处理器阻断缺陷动画；Gpiano 仅在小节真正变化时无动画定位，保留游标和高亮 | 真机全篇播放完成且进程未崩溃；Android 全量测试和构建通过 | 已修复 |
| 所有音听起来断续、疑似极短双触发 | alphaTab 1.8.4 Android 音频 worker 将每次不足固定 buffer 的尾部补零后整块写入 AudioTrack；问题出现在为修崩溃升级之后，生成 MIDI 本身无快速同键重触发 | 回到声音正常的 alphaTab 1.8.3，并以应用侧滚动处理修复崩溃，不再以音频实现变化换取稳定性 | 问题谱 MIDI 事件测试确认同通道同键 120 tick 内无重触发；真机最终听感待用户一次性验收 | 已修复，待验收 |
| 下滑后上滑谱面消失 | alphaTab 懒加载回收离屏 bitmap 后，回滑路径偶发未完成可见分片重排/重绘 | 保留懒加载并转发原始滚动监听；回滑后对 render surface 补发延迟布局与重绘 | 真机滚到底部再回顶部 1 轮 + 快速往返 3 轮，谱面完整且无应用异常 | 已修复 |
| 练习工作区直接打开最近谱且无选谱阶段 | 底部入口直接读取同步持久化的单个 `structureId`，工作区没有选谱、切换和恢复偏好 | 增加最近优先选谱、工作区换谱、空状态、曲谱库入口和默认关闭的显式自动恢复设置；导航/选择状态可跨配置变更保存 | 真机核对选谱、自动恢复开关、换谱与范围面板；Android 全量测试和构建通过 | 已修复 |

## `GPIANO-ISSUE.md` 修复范围

后续按独立可验证提交处理：

1. P0：AI provider SSRF/重定向密钥泄漏、Room 旧版本迁移、SQLite 外键、左右手判定。
2. 并发与性能：编辑防重入、派生版本幂等、书签/页序原子更新、工作区重组和阅读器 Flow 稳定。
3. 数据完整性：备份自导自拒、覆盖恢复语义、文件/数据库补偿、时值编辑与 ScoreIR 校验。
4. 服务和客户端健壮性：socket/线程限制、OMR 任务 TTL、MIDI 状态同步、路径安全、诊断脱敏、导入状态。
5. 低危与测试缺口：安全 XML、算术边界、MIDI 延音/SysEx、错误分类、MXL 限界、状态保存和死代码。

每项完成后在本文件追加修改文件、测试命令/结果和提交 SHA。

### 编辑与练习版本操作防重入

- 修复：校正、撤销/重做、主谱/派生修订切换，以及练习版本创建、读取、采纳、拒绝和导出，均在启动协程或系统文件选择器前同步获取原子门闩；第二次操作立即忽略。所有完成、失败与取消路径都在 `finally` 或取消回调中释放门闩。
- 测试：新增 `OperationGateTest`，8 个线程同时竞争只允许一个进入，并验证释放后可再次进入；Android 全量 JVM 测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### 书签/页序读改写竞态与 Reader 重订阅

- 修复：书签 toggle 下沉到 `BookmarkDao` 的 Room 事务；页序移动在 `ScorePageDao` 同一事务内重新读取当前顺序并交换，拒绝非 ±1 方向。Reader 与页序面板按 score id `remember` 同一个页面 Flow，页面减少时把当前位置收敛到有效范围。
- 测试：新增 `DaoAtomicOperationsTest`，覆盖书签插入/删除切换、基于当前顺序的相邻页交换和非法方向；Android 全量 JVM 测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### 工作区每拍重算与状态稳定性

- 修复：播放状态标记为 Compose `@Immutable`；工作区直接复用 `MusicXmlDocument.summary`，不再每次重组遍历整谱生成摘要；当前小节事件数量与校正事件列表按谱面和小节缓存。此前的播放回调已只在小节实际变化时发布状态，谱面定位也只在目标小节变化时执行。
- 测试：Android 全量 JVM 测试与 Debug 构建通过；真机播放/滚动回归已覆盖全篇与快速往返。
- 提交：见包含本节的独立提交。

### 备份导出/导入 JSON 限额不对称

- 修复：`library.json` 和 `manifest.json` 在导出、导入两侧统一使用 64 MiB UTF-8 字节上限；导出先序列化并校验，再打开目标文件写入，避免生成 App 自己无法恢复的包或留下半写入目标。
- 测试：新增 `GpianoBackupLimitsTest`，覆盖边界值接受和超 1 字节拒绝；Android 全量 JVM 测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### 识别诊断信息明文进入备份

- 修复：识别任务导出副本清除 `remoteJobId`、`errorMessage` 与 `diagnosticsJson`，保留稳定 `errorCode` 和状态以支持本地恢复提示。
- 测试：`GpianoBackupLimitsTest` 使用含内部 URL/token 和响应片段的任务验证三项敏感字段清空、分类与状态保留；Debug 构建通过。
- 提交：见包含本节的独立提交。

### “覆盖恢复”实际合并旧数据

- 修复：新增专用 `BackupRestoreDao.replaceWith` Room 事务，按外键顺序清空 11 张业务表并按依赖顺序写入快照；空快照也执行清空。恢复前记录旧文件引用，数据库提交后清理不再引用的旧文件；异常沿用已有文件回滚。
- 测试：新增 `BackupRestoreDaoTest`，覆盖空快照仍完整清表、子到父清理顺序和父到子插入顺序；Android 全量 JVM 测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### 恢复进程死亡导致文件/数据库跨介质不一致

- 修复：恢复快照的六类文件路径统一重映射到 UUID `restore-generations`；目标路径保证全新，全部文件落盘并 fsync 后才用单个 Room 事务替换 DB。切换前崩溃不覆盖旧文件，切换后崩溃时新文件已存在。下一次恢复会按当前 DB 引用清理孤儿 generation。
- 测试：`ScoreRepositoryPathTest` 新增 generation 映射、非法 generation 与 `..` 路径拒绝；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### ScoreIR 弱校验与标称小节 tick 溢出

- 修复：ScoreIR 校验新增声部/小节索引一致性、正 divisions/拍号、严格正时值、结束位置 Long 溢出和同 voice/staff 事件重叠检查；播放计划的标称小节长度从运算第一步即使用 Long，并拒绝非正拍号。
- 测试：新增 `ScoreIrValidatorTest`，覆盖零时值、同声部重叠、结束位置溢出和 `Int.MAX_VALUE` 拍数的 Long 计算；包括真实 24 小节谱在内的 Android 全量 JVM 测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### `changeDuration` 破坏多声部 `backup/forward`

- 修复：和弦组时值变化后，同 voice 的后续事件按新时值自然移动；编译器同步调整目标组之后第一个 `backup`/`forward` 的 duration 抵消游标差值，使其他 voice/staff 的既有 onset 不漂移。补偿值不能为负，变为 0 时移除控制节点；重解析后继续由 ScoreIR 重叠校验把关。
- 测试：新增双 voice MusicXML 用例，将第一音从四分音符改为二分音符，验证同 voice 后一音后移、另一 voice onset 不变、`backup` 从 2 调整为 3 且结果通过 ScoreIR 校验；Android 全量 JVM 测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### 导入孤儿文件与删除路径未校验

- 修复：单文件和图片组导入把曲谱/页面写入同一 Room 事务，复制、PDF 分页或 DB 失败时删除本次 UUID 文件；扩展名只由受信 MIME 映射。删除前验证曲谱、页面和分组目录的 canonical 路径均位于 `filesDir`，再删 DB 与文件；备份安装也复用同一解析器。
- 测试：新增 `ScoreRepositoryPathTest`，覆盖合法嵌套路径、`..` 越界和绝对路径拒绝；Android 全量 JVM 测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### AI provider SSRF 与密钥重定向

- 修复：模型端点启动校验会解析全部地址并拒绝非公网 IP；仅显式开发模式允许 HTTP loopback。provider 请求使用禁止重定向的 opener，授权头不会跟随 30x 发往其他目标。
- 测试：`python -m unittest discover -s practice-ai-service -p 'test_*.py'`，7 项通过，覆盖私网地址、开发 loopback 和禁重定向。
- 提交：本项提交完成后回填 SHA。

### AI/OMR HTTP 慢连接与无界线程

- 修复：两个伴随服务在接受 socket 后设置可配置的读写超时，并在创建请求线程前以有界信号量占用容量；容量耗尽时关闭新连接。请求线程无论正常完成或抛出异常都会释放容量。网络请求并发与 OMR 的 Audiveris 进程池分别配置。
- 测试：`python -m unittest discover -s practice-ai-service -p 'test_*.py'` 9 项通过；`python -m unittest discover -s omr-service -p 'test_*.py'` 7 项通过。新增用例核对 accepted socket 超时及超出 worker 上限时不再派生线程。
- 提交：见包含本节的独立提交。

### OMR 任务目录无上限且没有 TTL

- 修复：`JobStore` 在同一锁内先清理再检查任务目录总数，容量检查与目录创建不可竞态穿透。启动和每次提交前清理超过 TTL 的 `ready`、`failed` 任务，以及没有有效元数据的残缺目录；`queued`、`running` 始终保留。写入失败会删除刚创建的目录，容量耗尽返回稳定错误码。
- 测试：`python -m unittest discover -s omr-service -p 'test_*.py'` 10 项通过，覆盖活动任务容量、终态 TTL、运行任务保留和残缺目录清理。
- 提交：见包含本节的独立提交。

### MIDI 接收线程读取非同步状态

- 修复：`MidiPracticeController.state` 声明为 JVM volatile；主线程发布录制状态后，MIDI 接收线程读取 `connection` 时具有明确的 happens-before 可见性。
- 测试：新增 `MidiPracticeControllerVisibilityTest`，通过字段修饰符回归检查锁定跨线程可见性契约；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### 曲谱导入无进度且允许重复触发

- 修复：PDF 与多图导入在启动协程前通过 `MutableStateFlow.compareAndSet` 原子占用同一个门闩；第二次请求立即忽略，所有终止路径在 `finally` 中释放。曲谱库同步禁用入口并显示复制、校验进度提示。
- 测试：新增 `LibraryImportGateTest`，8 线程同时竞争只允许一次进入，并验证释放后可再次导入；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### 服务响应超限错误分类与诊断边界

- 修复：AI/OMR 有界读取器改用专用超限异常，并在客户端边界转换成 `response_too_large`；MusicXML 超限转换成 `result_too_large`。OMR 错误响应超限不会再被吞掉并退化成 `http_状态码`。任务诊断 JSON 在构造持久化模型前限制为 4 KiB。
- 测试：新增 `PracticeAiClientResponseTest` 与 `OmrClientResponseTest`，覆盖成功/错误响应超限的稳定分类及诊断截断；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### MusicXML 安全 feature 被静默忽略

- 修复：解析器强制启用 JAXP secure processing，并尝试关闭所有外部 DTD/schema 协议；已有空实体解析器继续作为平台兼容兜底。为兼容常见 MusicXML 外部 DOCTYPE，不一刀切拒绝 DOCTYPE，但在进入 DOM 前明确拒绝任何 `ENTITY` 声明和内联 DTD 子集。
- 测试：`MusicXmlScoreParserTest` 新增内部外部实体载荷拒绝，以及指向本地恶意 DTD 仍不读取且可安全解析的用例；真实带 MusicXML 4.0 DOCTYPE 的 24 小节谱继续通过。Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### `ScorePitch.transpose` 越界静默夹值

- 修复：移调先以 Long 计算目标 MIDI，再要求结果位于 MusicXML 音高模型可表示的 12..127；不再用 `coerceIn` 把不同的越界编辑全部变成边界音。
- 测试：新增最高音上移、最低可表示音下移和 `Int.MAX_VALUE` 半音三类拒绝用例；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### `toPitch` 越界抛裸异常

- 修复：音高元素逐项严格解析；存在但非整数的 `alter` 不再回退为 0，缺失/非法 step、octave 与 `ScorePitch` 范围错误统一包裹为“无法解析 MusicXML 音高”。
- 测试：新增非法 alter 文本、alter=3、octave=10 与多字符 step 四类用例，全部核对统一错误边界；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### MIDI 延音合并依赖精确 tick

- 修复：同音高、同手别且带对应 tie 标记的相邻事件，在连接点相差不超过 1 tick 时合并；容差只吸收 divisions 到 MIDI tick 的舍入误差，不覆盖 2 tick 以上的真实间隔。
- 测试：`StandardMidiFileTest` 新增 1 tick 仍为一次 note-on、2 tick 保持两次 note-on 的成对边界用例；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### MIDI 变量长度编码边界缺测

- 修复：delta-time 变量长度写入器提取为可直接验证的内部函数，导出路径仍复用同一实现并保留格式上限检查。
- 测试：`StandardMidiFileTest` 精确核对 0、0x7F、0x80、0x3FFF、0x4000、0x0FFFFFFF 的字节序列，并验证 0x10000000 被拒绝；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### MIDI 解析器不跟踪 SysEx 状态

- 修复：解析器增加跨 `feed` 调用保存的 SysEx 状态；`F0` 后的全部非 real-time 字节隔离到 `F7`，因此畸形载荷中的 `0x90` 不能注入按键。截断 SysEx 只可由结束字节或 `reset` 恢复，real-time 字节不改变 SysEx、running status 或部分消息状态。
- 测试：`MidiMessageParserTest` 新增跨分片 SysEx 伪 note-on、截断后 reset，以及插入 timing clock 的 running-status 分片三类用例；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### `extract_mxl` 无界读取 container.xml

- 修复：读取 `META-INF/container.xml` 前先检查 ZipInfo 解压大小，超过 64 KiB 立即拒绝；rootfile 继续要求安全相对路径并限制 MusicXML 为 20 MiB。
- 测试：OMR 服务新增高压缩比、解压后 64 KiB+1 的 container 用例，确认在 XML 解析前返回大小错误；`python -m unittest discover -s omr-service -p 'test_*.py'` 11 项通过。
- 提交：见包含本节的独立提交。

### 未引用的假数据占位屏残留

- 修复：删除 `BackupSettingsScreen.kt`、`LibraryScreen.kt`、`SecondaryScreens.kt`；当前导航只保留 `ImportedLibraryScreen`、`RealFavoritesScreen`、`RealFoldersScreen` 与 `RestoreSettingsScreen` 的真实数据路径。
- 测试：`rg` 确认被删 composable 无调用方；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。

### 配置变更丢失阅读与校正现场

- 修复：导航只保存 `openedScoreId` 并从 ViewModel 的 score flow 重新解析对象；阅读器页码和面板、工作区小节与试听参数均使用 `rememberSaveable`。`ScorePitch`/`MusicalDuration` 草稿使用严格重建的自定义 Saver，避免保存 Repository 会话或 Android 对象。
- 测试：Android JVM 全量测试与 Debug 构建通过；Saver 恢复路径会重新触发 `ScorePitch`/`MusicalDuration` 构造校验，无效 Bundle 值返回 null 并使用初始值。
- 提交：见包含本节的独立提交。

### Room v1-v6 升级缺少 `lastOpenedAt`

- 修复：在所有 v1-v6 升级路径必经的 `V6_TO_V7` 中增加 nullable `lastOpenedAt` 列；v7 及以后 schema 已包含该列，不重复修改。
- 测试：新增 `GpianoDatabaseMigrationTest` 直接执行迁移并核对 DDL；Android JVM 全量测试与 Debug 构建通过。
- 提交：本项提交完成后回填 SHA。

### SQLite 外键声明未执行

- 修复：Room 数据库每次打开时显式执行 `PRAGMA foreign_keys=ON`，使结构、识别任务、修订、练习版本和演奏事件的级联/置空约束生效。
- 测试：`GpianoDatabaseMigrationTest` 直接执行数据库 callback 并核对外键启用语句；Android JVM 全量测试与 Debug 构建通过。
- 提交：本项提交完成后回填 SHA。

### 双 part 乐谱左手被误判为右手

- 修复：双 part 乐谱优先按 part 索引判定左右手，`staff` 只用于单 part 的 grand staff；避免左手 part 内部同样从 staff 1 编号时被误判。
- 测试：新增带两个 part、且两边均声明 `staff=1` 的 MusicXML 回归用例；`MusicXmlScoreParserTest` 与 Android JVM 全量测试通过。
- 提交：本项提交完成后回填 SHA。

### 单 part 无 staff 乐谱分手播放静音

- 修复：单 part 事件在没有 staff 1/2 信息时默认归为右手；staff 信息仍优先，因此单 part grand staff 不受影响，多 part 的未知映射也不会被擅自猜测。
- 测试：`MusicXmlScoreParserTest` 新增无 staff 单旋律谱，核对事件为 Right、右手计划含音符且左手计划为空；Android JVM 全量测试与 Debug 构建通过。
- 提交：见包含本节的独立提交。
