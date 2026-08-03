# 2026-07-29：Compose 工程基础

- 创建 Gpiano Android Compose 工程与可移植的 Gradle Wrapper。
- 实现竖屏优先、低饱和浅色主题和四项底部导航。
- 实现静态阅读器与节拍器抽屉交互骨架。
- `:app:assembleDebug` 构建成功。

# 2026-07-30：图片组阅读器崩溃修复

- 阅读器按 `Score.format() == ScoreFormat.Image` 选择图片解码分支，兼容 MIME 类型为 `application/x-gpiano-image-group` 的图片组。
- 为图片解码与 PDF 渲染增加异常保护，格式不匹配或文件损坏时不再导致阅读页崩溃。
- 页面文件路径准备完成后自动重试渲染，修复图片组首次打开空白；图片导入改为系统多选媒体选择器。
