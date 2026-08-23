# 2026-08-21：统一后端启动器交接

## 已完成

- `scripts/start-backend.sh` 作为 OMR 与 AI companion 的统一入口。
- `scripts/backend.local.sh.example` 集中列出 Python、Audiveris、OMR、DeepSeek、AI companion 和 Android 端口映射配置，所有分区均使用中文注释。
- 实际 `scripts/backend.local.sh` 被 Git 忽略并限制为当前用户可读写；不得把其中模型 Key 或 companion token 复制到提交物。
- 缺少 Audiveris 时，启动器下载官方 5.11.0 Ubuntu 24.04 x86_64 包，按固定 SHA-256 校验后解压到被忽略的 `.local-tools/`。
- 服务启动后依次检查 OMR/AI `/health`，检测到唯一 Android 设备时自动建立 `8765` 和 `8766` 反向映射；任一服务退出或用户中断时清理两个子进程。

## 验证

1. `bash -n` 检查启动器、示例配置和本地配置通过。
2. `omr-service` 5 项、`practice-ai-service` 4 项 Python 测试通过。
3. 官方 Audiveris 5.11.0 包 SHA-256 校验通过，CLI `-help` 在当前 Ubuntu x86_64 开发机正常执行。
4. OMR 与 AI companion 健康检查均返回成功，当前单台 Android 设备的两个 `adb reverse` 映射建立成功。
5. DeepSeek V4 Flash 使用合成 C4 四分音符请求完成真实 API 调用，companion 输出通过既有选段引用和 JSON 约束校验；没有发送用户谱面。

## 使用方式

```bash
./scripts/start-backend.sh
```

只检查配置和依赖时执行：

```bash
./scripts/start-backend.sh --check
```

Android Debug 设置中填写 `http://127.0.0.1:8765` 和 `http://127.0.0.1:8766`，访问令牌读取本机 `scripts/backend.local.sh`。更换 DeepSeek 模型、Key、端口或 Audiveris 路径也只修改该本地配置。

## 边界

- 当前真实模型验证仅证明接口、认证和结构化输出链路，不证明真实谱面回答的正确性或教学有效性。
- 自动准备只覆盖 Linux x86_64 的 Ubuntu 24.04 安装包；其他系统需手动配置 `AUDIVERIS_BIN`。
- 用户曾通过对话明文提供模型 Key；应在完成当前联调后轮换，并只更新被忽略的本地配置。
