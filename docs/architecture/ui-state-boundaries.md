# UI 状态边界（v0）

## 原则

界面状态与持久化数据分离。页面旋转、短暂后台切换和进程重建不能导致用户失去正在看的谱页或导入进度。

## 状态归属

| 状态 | 保存位置 | 示例 |
| --- | --- | --- |
| 曲谱、文件夹、书签、阅读位置 | Room 数据库 | `Score`、`ReadingState` |
| 用户全局偏好 | DataStore | 默认阅读模式、主题、翻页热区 |
| 仅本次页面交互 | ViewModel/SavedStateHandle | 是否展示工具栏、当前抽屉 |
| 文件导入与缩略图任务 | WorkManager + 数据库状态 | `Pending`、`Running`、`Failed`、`Ready` |
| PDF 页图缓存 | App 缓存目录 | 已渲染页面位图 |

## 阅读器状态机

```text
Loading → Ready ↔ ControlsVisible
   │         │
   │         ├→ MetronomeExpanded
   │         └→ Error (可重新加载)
   └→ Error
```

每次页码、缩放或阅读模式的稳定变更应采用防抖保存，避免频繁写数据库；离开阅读页时必须立即落盘。

## 导入状态机

```text
Selected → Validating → Copying → Indexing → Ready
                    └→ Failed (保留错误原因和重试入口)
```

只有 `Ready` 的曲谱才能进入常规列表。任何失败路径都不得删除已存在的曲谱或其元数据。
