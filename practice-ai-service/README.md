# Gpiano practice AI companion

该服务把 Android 端已经获得用户逐次授权的最小化选段摘要转发给兼容 Chat Completions JSON 协议的模型。模型密钥只放在服务端环境变量中；App 不发送原始图片或完整 MusicXML。

启动示例：

```bash
export GPIANO_AI_SERVICE_TOKEN='replace-with-a-random-service-token'
export GPIANO_MODEL_ENDPOINT='https://provider.example/v1/chat/completions'
export GPIANO_MODEL_API_KEY='provider-key'
export GPIANO_MODEL_NAME='provider-model'
python3 practice-ai-service/server.py
```

可选变量：`GPIANO_AI_HOST`（默认 `127.0.0.1`）、`GPIANO_AI_PORT`（默认 `8766`）、`GPIANO_MODEL_TIMEOUT`（默认 90 秒）。正式部署应使用 HTTPS 反向代理；不要把任何令牌提交到项目目录。仅本机集成测试可显式设置 `GPIANO_AI_ALLOW_INSECURE_LOOPBACK=true`，它只允许模型地址使用 HTTP loopback，不允许远端明文连接。

测试：

```bash
cd practice-ai-service
python3 -m unittest -v
```
