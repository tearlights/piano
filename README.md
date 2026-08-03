# Gpiano

本地优先的 Android 钢琴谱阅读与管理应用。第一阶段只解决“导入、整理、舒适看谱、节拍器、可迁移备份”，不实现云端、AI 识谱或账号体系。

## 快速开始

1. 阅读 [项目宪章](PROJECT_CHARTER.md) 和 [当前上下文](.codex/project-context.md)。
2. 执行 `bash scripts/bootstrap.sh` 检查开发环境。
3. Android 应用工程将在完成 UI/交互设计确认后创建；当前仓库刻意不包含业务代码。

## 项目资料

- [产品需求](docs/product/requirements.md)
- [阅读体验规范](docs/product/reader-ux.md)
- [本地数据与备份规范](docs/architecture/local-data-and-backup.md)
- [架构决策](docs/decisions/)
- [AI 交接记录](.codex/handoffs/)

## 可移植性

整个目录可通过 Git 或压缩包迁移。不要提交密钥、签名文件、私人琴谱、构建产物和机器相关的 `local.properties`。
