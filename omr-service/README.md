# Gpiano OMR companion

该服务在用户自己的电脑上调用 Audiveris，将一张 PNG/JPEG/WebP 琴谱转换为 MusicXML。Android App 不嵌入 Audiveris，也不会在未点击转换时上传原谱。

需要同时启动 OMR 与 AI companion 时，复制并修改 `scripts/backend.local.sh.example`，然后执行：

```bash
./scripts/start-backend.sh
```

统一启动器可在本机缺少 Audiveris 时下载、校验并解压官方 5.11.0 Ubuntu 24.04 x86_64 包；本文件下方命令仍适合单独调试 OMR companion。

## 依赖

- Python 3.11 或更高版本；
- Audiveris 5.11.0；
- 一个自行生成的随机访问令牌。

Audiveris 是 GNU Affero GPL v3 软件。本目录的 companion 源码以 `AGPL-3.0-or-later` 提供；部署者需要同时遵守 Audiveris 的许可并向网络用户提供对应源码。

## 启动

```bash
export GPIANO_OMR_TOKEN='替换为随机长令牌'
export AUDIVERIS_BIN='/path/to/audiveris'
python3 omr-service/server.py
```

默认只监听 `127.0.0.1:8765`，任务保存在 `omr-service/var/`。实机 Debug 演示建议使用：

```bash
adb reverse tcp:8765 tcp:8765
```

然后在 App 设置中填写 `http://127.0.0.1:8765` 和相同令牌。正式构建只接受 HTTPS 地址。

可选环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `GPIANO_OMR_HOST` | `127.0.0.1` | 监听地址 |
| `GPIANO_OMR_PORT` | `8765` | 监听端口 |
| `GPIANO_OMR_DATA` | `omr-service/var` | 隔离任务目录 |
| `GPIANO_OMR_WORKERS` | `1` | 并行 Audiveris 进程数 |
| `GPIANO_OMR_TIMEOUT` | `900` | 单任务超时秒数 |
| `GPIANO_OMR_SOCKET_TIMEOUT` | `15` | 单个 HTTP 连接读写超时秒数 |
| `GPIANO_OMR_HTTP_WORKERS` | `16` | 同时处理的 HTTP 连接上限；与 Audiveris 进程数独立 |
| `GPIANO_OMR_MAX_JOBS` | `500` | 磁盘中允许保留的任务目录总数 |
| `GPIANO_OMR_JOB_TTL` | `604800` | 成功、失败或残缺任务的保留秒数，最小 60 秒 |

服务启动及每次创建任务前都会清理超过 TTL 的 `ready`、`failed` 和残缺任务目录；`queued`、`running` 任务不会按 TTL 清除。清理后仍达到总数上限时，新建任务返回 `503 job_capacity_reached`。

Audiveris 产出的 MXL 只读取声明的安全相对 rootfile；`META-INF/container.xml` 解压上限为 64 KiB，MusicXML 解压上限为 20 MiB，超限归档不会进入 XML 解析。

运行服务测试：

```bash
python3 -m unittest discover -s omr-service -p 'test_*.py'
```

## 安全与数据边界

- 请求必须携带 bearer token；服务日志不输出令牌、请求头或图片内容。
- 单文件限制为 25 MB；每个任务使用独立目录，子进程不通过 shell 启动。
- 结果只在 Audiveris 成功导出且 XML 根节点有效时标记 ready。
- 服务不声称存在 Audiveris 未提供的统一置信度，Android 端始终把首版结果标记为待校正。
