# Hooray 稳定性与体验修复交接

日期：2026-08-25  
分支：`Hooray`  
基线：`f2da0b5`

## 结论

`GPIANO-ISSUE.md` 与临时交接中的可执行修复已完成，全部按问题项独立提交，并同步测试与文档。最新 Debug APK 已安装到设备 2405CRPFDC，启动进程存活且无 AndroidRuntime/libc 致命日志。现在进入用户一次性验收，不再安排分项验收。

## 主要完成项

- 播放：alphaTab 1.8.3 恢复正常声音实现；应用侧隔离负滚动动画崩溃，保留懒加载并修复回滑白屏；播放范围只使用 alphaTab 小节时间轴。
- 工作区：默认选谱、可换谱、直接起止范围、懒列表；编辑/撤销/版本/导出同步原子门控；减少每拍重组；阅读、范围和校正草稿可跨配置恢复。
- 数据：迁移列、SQLite 外键、事务化书签/页序；导入失败补偿和删除路径验证；备份限额、脱敏、真正覆盖恢复；恢复文件使用唯一 generation 后再事务切换 DB。
- 乐谱：双 part 与单 part 手别、严格时值/onset/重叠/溢出校验、多声部时值编辑补偿、音高解析/移调边界、安全 XML。
- 服务与 MIDI：AI SSRF/重定向防护；AI/OMR socket 超时和有界 HTTP worker；OMR 数量/TTL/磁盘清理与 MXL 上限；客户端响应分类和诊断上限；MIDI 状态可见性、延音舍入、变量长度、SysEx/截断/running status。
- UI：导入进度与防重入、配置现场保存、占位死屏删除。

## 语义决定

- 不要求每个 MusicXML 小节必须严格等于标称拍号容量。弱起、隐式小节、自由长度和已支持的 overfull bar 都是合法输入；强制等值会破坏真实谱兼容。仍严格拒绝非正时值、负 onset、Long 溢出和同 voice/staff 重叠。
- `RecognitionWorker` 原本已正确重新抛出 `CancellationException`；`WorkspaceSelectionStore` 原本已使用异步 `apply()`；这两项经审计确认无需代码提交。
- 自动化不能代替声音听感或真实电钢琴兼容结论。

## 最终测试证据

- `./gradlew testDebugUnitTest assembleDebug`：通过，Android JVM 共 60 项。
- `python -m unittest discover -s practice-ai-service -p 'test_*.py'`：9 项通过。
- `python -m unittest discover -s omr-service -p 'test_*.py'`：11 项通过。
- `python -m py_compile practice-ai-service/server.py omr-service/server.py`：通过。
- `git diff --check`：通过。
- 设备：APK 安装成功；包 `com.gpiano.app` PID 存活；启动后致命日志为空。

## 用户一次性验收重点

1. 全篇与任意选段试听：确认没有断续、极短双触发或中途崩溃。
2. 谱面快速下滑再上滑至少三轮：确认不白屏、不消失。
3. 练习工作区：换谱、直接调整起止小节、当前小节/全篇快捷项、旋转后范围与校正草稿。
4. 导入 PDF/多图、取消选择、快速重复点击；备份导出与覆盖恢复。
5. 有真实 USB MIDI 电钢琴时再验证连接、录制、停止、断连和 SysEx 设备兼容性。

## 提交范围

从 `1bdfa58` 到最终归档提交，共 30 余个按问题拆分的提交。完整清单以 `git log --oneline f2da0b5..HEAD` 为准；逐项说明与测试位于 `docs/architecture/hooray-remediation-log.md`，用户可见变化位于 `docs/changelog/2026-08-24-hooray-reliability.md`。
