# Gpiano 协作规则

所有人类与 AI 参与者均应遵守本文件。

## 开始工作前

1. 先阅读 `PROJECT_BRIEF.md`、`比赛资料/基础信息/Gpiano-AI主理人出道计划报名帖.md`、当前周 `比赛资料/<周次>/output/` 的产品材料、`PROJECT_CHARTER.md`、`docs/product/requirements.md` 和相关 ADR。
2. 再阅读 `docs/context-sync/project-context.md` 和 `docs/context-sync/current-status.md` 了解已有实现与当前状态。
3. 先检查已有改动，不覆盖或回退无关文件。
4. 对影响产品边界、数据结构或依赖选型的改动，先补充 ADR。

## 变更要求

- 代码与文档使用相对路径，不写死开发机路径。
- 不提交密钥、证书、私人曲谱、构建目录或 `local.properties`。
- 本地数据模型变化必须兼顾迁移与导入旧备份；涉及结构化乐谱、派生练习版本、MIDI 演奏记录或匹配反馈时，先补充相应数据与备份设计。
- 面向用户的文字使用简体中文；代码标识与技术文档可使用英文。
- 完成一个可验证的工作单元后，更新 `docs/context-sync/project-context.md`、`docs/context-sync/current-status.md` 与 `docs/context-sync/handoffs/` 中对应的交接记录。
- 每周比赛资料使用 `input/` 保存原始材料，使用 `output/` 保存结论、提交物与可验证证据。

## UX 底线

- 阅读页面不允许用常驻控件遮挡大面积琴谱。
- 任何耗时操作必须有明确状态和可恢复路径。
- 导入失败不能损坏已有琴谱库。

## 验证

优先运行与改动匹配的检查。创建 Android 工程后，至少执行构建或对应单元测试；同时更新 `docs/changelog/` 的重要变更记录。
