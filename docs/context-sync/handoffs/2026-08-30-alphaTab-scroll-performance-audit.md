# alphaTab 惯性滚动修复性能审计（2026-08-30）

## 结论

当前实现不是多套补丁叠加运行。历史上的延迟重绘、`api.render(null)` 和触底单向 synthetic delta 已被后续提交替换；运行代码只保留布局脏状态检测、累计偏移补发和同步 render surface 布局。

## 本轮清理

- 移除排查阶段的 `renderStarted`、`renderFinished`、解析结果和定位日志，避免每次渲染产生字符串拼接与 Log 调用。
- `requestLayout()` 仅在 alphaTab `_layoutDirty` 时触发；普通滚动仍通过一次 `OnPreDraw` 合并同帧偏移。
- `partialRenderFinished` 仅在布局仍脏或滚动偏移尚未交付时重新调度，避免每个 partial bitmap 都触发无效遍历。

## 验证

- `:app:testDebugUnitTest` 通过。
- `:app:assembleDebug` 通过。
- Debug APK 已安装真机；连续边界滚动 smoke 测试后应用进程和滚动容器仍存活，无应用崩溃日志。
- 像素级最终验收仍以用户在真机上复测“惯性触底后上滑”和“惯性触顶后下滑”为准。
