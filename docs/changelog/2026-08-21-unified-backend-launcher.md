# 2026-08-21：统一后端启动器与 DeepSeek 接入验证

- 新增 `scripts/start-backend.sh`，单次执行即可准备 Audiveris 并同时启动 OMR、AI companion。
- 新增中文注释的 `scripts/backend.local.sh.example`；实际本地配置由 `.gitignore` 排除，模型 Key 和 companion 访问令牌不进入版本库。
- 启动器支持官方 Audiveris 5.11.0 Ubuntu 24.04 x86_64 包下载、固定 SHA-256 校验、本地解压、配置校验、端口预检、健康检查、进程联动退出和单设备 `adb reverse`。
- DeepSeek 使用 OpenAI Chat Completions 端点与 `deepseek-v4-flash`；已用不含用户谱面的合成单音请求验证真实 API 和受约束 JSON 往返。
- 修正 OMR companion 启动提示的输出刷新，便于统一启动器及时显示状态。
