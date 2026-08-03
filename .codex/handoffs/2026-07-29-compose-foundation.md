# 交接：Compose 工程与静态 UI 骨架

## 已完成

- 创建标准 Android/Gradle 工程，包名为 `com.gpiano.app`。
- 固定 Gradle、AGP、Kotlin 与 Compose 依赖版本，并加入 Gradle Wrapper。
- 实现低饱和浅色主题与竖屏优先的基础布局。
- 实现曲谱库、收藏、文件夹、设置四个底部导航页面。
- 实现阅读器沉浸式占位页、控制栏显示/隐藏与节拍器底部抽屉占位。

## 有意未实现

- 文件导入、PDF/图片渲染、真实曲谱数据、Room、DataStore、节拍器音频。
- 真实书签、收藏、搜索、翻页、备份恢复逻辑。

## 验证

在本机使用 `./gradlew :app:assembleDebug` 构建成功。产物为 `app/build/outputs/apk/debug/app-debug.apk`。

## 下一步

优先把静态曲谱库改为真实的本地数据模型与导入流程；随后接入 PDF/图片阅读器。阅读器外层 UI 和状态边界已存在，渲染层应替换 `ReaderScreen` 内的 `PlaceholderScore`。
