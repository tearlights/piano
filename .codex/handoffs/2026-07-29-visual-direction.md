# 交接：视觉方向确定

## 已确定

- 竖屏优先，手机和平板自适应。
- 琴谱不变形，默认适合宽度；宽或高至少一边填满可用阅读区。
- 简约、低饱和、淡色界面；琴谱内容不做主题染色。

## 参考文档

- `docs/product/visual-design-v0.md`
- `docs/decisions/ADR-0003-portrait-adaptive-minimal-visuals.md`

## 下一步

创建最小 Compose 项目，并根据视觉规范实现主题 token、导航壳和四个页面的静态骨架。暂不接入文件导入或 PDF 渲染。
