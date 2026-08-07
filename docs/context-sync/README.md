# 项目上下文与交接

本目录是 Gpiano AI 的正式、可写上下文位置。

- `project-context.md`：长期产品方向、已确定决策、已有实现事实与近期下一步。
- `current-status.md`：当前阶段、已完成能力、待验证能力与近期任务。
- `handoffs/`：每个可验证工作单元的交接记录；历史记录不回写，新工作新增文件。

新会话读取顺序由根目录 `AGENTS.md` 和 `PROJECT_BRIEF.md` 规定。发生冲突时，以 `PROJECT_BRIEF.md`、比赛基础愿景、当前周 `output/` 与相关 ADR 为准。
