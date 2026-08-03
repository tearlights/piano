# 当前项目状态

最后更新：2026-07-29

## 已完成的基础设计

- 产品宪章、协作规则、本地优先 ADR 与备份格式初稿。
- 第一版范围：曲谱库、收藏、文件夹、设置、沉浸式阅读器、节拍器、`.gpiano` 导入导出。
- 信息架构与关键用户流程：`docs/product/information-architecture.md`。
- 低保真页面线框：`docs/product/wireframes.md`。
- UI 持久化与异步任务状态边界：`docs/architecture/ui-state-boundaries.md`。

## 当前未做

- 尚未创建 Android/Gradle 应用工程。
- 尚未确定视觉设计语言与最低 Android SDK。
- 没有业务代码、远端服务、账号、AI 识谱或同步功能。

## 下一项决策

确认视觉方向和目标设备范围，然后创建最小 Compose 工程与 Design System。创建工程时应新增独立 ADR，锁定 Kotlin、AGP、Compose BOM、minimum SDK 和 target SDK 的版本策略。
