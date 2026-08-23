#!/usr/bin/env bash

set -Eeuo pipefail

# ==================== 路径与本地配置 ====================

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
LOCAL_CONFIG="${GPIANO_BACKEND_CONFIG:-${SCRIPT_DIR}/backend.local.sh}"
LOCAL_TOOLS_DIR="${PROJECT_ROOT}/.local-tools"

if [[ ! -f "${LOCAL_CONFIG}" ]]; then
    echo "错误：缺少本地配置 ${LOCAL_CONFIG}" >&2
    echo "请先复制 scripts/backend.local.sh.example 并填写本地令牌与模型 Key。" >&2
    exit 1
fi

# shellcheck source=/dev/null
source "${LOCAL_CONFIG}"

# ==================== 默认值与派生配置 ====================

PYTHON_BIN="${PYTHON_BIN:-python3}"
AUDIVERIS_BIN="${AUDIVERIS_BIN:-}"
AUTO_INSTALL_AUDIVERIS="${AUTO_INSTALL_AUDIVERIS:-true}"
AUDIVERIS_VERSION="${AUDIVERIS_VERSION:-5.11.0}"
AUDIVERIS_PACKAGE_URL="${AUDIVERIS_PACKAGE_URL:-https://github.com/Audiveris/audiveris/releases/download/5.11.0/Audiveris-5.11.0-ubuntu24.04-x86_64.deb}"
AUDIVERIS_PACKAGE_SHA256="${AUDIVERIS_PACKAGE_SHA256:-f20113aaa33b3149ec8d6a09b2a7963360e65fafd92d69389987a85bbc3ec7a3}"

GPIANO_OMR_HOST="${GPIANO_OMR_HOST:-127.0.0.1}"
GPIANO_OMR_PORT="${GPIANO_OMR_PORT:-8765}"
GPIANO_OMR_WORKERS="${GPIANO_OMR_WORKERS:-1}"
GPIANO_OMR_TIMEOUT="${GPIANO_OMR_TIMEOUT:-900}"
GPIANO_OMR_DATA="${GPIANO_OMR_DATA:-${PROJECT_ROOT}/omr-service/var}"

DEEPSEEK_OPENAI_BASE_URL="${DEEPSEEK_OPENAI_BASE_URL:-https://api.deepseek.com}"
DEEPSEEK_ANTHROPIC_BASE_URL="${DEEPSEEK_ANTHROPIC_BASE_URL:-https://api.deepseek.com/anthropic}"
GPIANO_MODEL_ENDPOINT="${GPIANO_MODEL_ENDPOINT:-${DEEPSEEK_OPENAI_BASE_URL%/}/chat/completions}"
GPIANO_MODEL_NAME="${GPIANO_MODEL_NAME:-deepseek-v4-flash}"
GPIANO_MODEL_TIMEOUT="${GPIANO_MODEL_TIMEOUT:-90}"
GPIANO_AI_HOST="${GPIANO_AI_HOST:-127.0.0.1}"
GPIANO_AI_PORT="${GPIANO_AI_PORT:-8766}"
ENABLE_ADB_REVERSE="${ENABLE_ADB_REVERSE:-true}"

CHECK_ONLY="false"
if [[ "${1:-}" == "--check" ]]; then
    CHECK_ONLY="true"
elif [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    echo "用法：scripts/start-backend.sh [--check]"
    echo "  无参数    准备 Audiveris，启动 OMR 与 AI companion，并等待退出"
    echo "  --check   只检查配置和依赖，不启动服务"
    exit 0
elif [[ $# -gt 0 ]]; then
    echo "错误：未知参数 $1" >&2
    exit 2
fi

# ==================== 通用检查函数 ====================

fail() {
    echo "错误：$*" >&2
    exit 1
}

require_value() {
    local name="$1"
    local value="${!name:-}"
    [[ -n "${value}" ]] || fail "配置 ${name} 不能为空"
    [[ "${value}" != 请* ]] || fail "配置 ${name} 仍是占位内容，请编辑 ${LOCAL_CONFIG}"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "找不到命令：$1"
}

check_python_version() {
    "${PYTHON_BIN}" - <<'PY'
import sys
if sys.version_info < (3, 11):
    raise SystemExit("错误：后端要求 Python 3.11 或更高版本")
PY
}

check_port_available() {
    local host="$1"
    local port="$2"
    CHECK_HOST="${host}" CHECK_PORT="${port}" "${PYTHON_BIN}" - <<'PY'
import os
import socket

host = os.environ["CHECK_HOST"]
port = int(os.environ["CHECK_PORT"])
family = socket.AF_INET6 if ":" in host else socket.AF_INET
with socket.socket(family, socket.SOCK_STREAM) as probe:
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        probe.bind((host, port))
    except OSError as error:
        raise SystemExit(f"错误：{host}:{port} 无法监听：{error}")
PY
}

# ==================== Audiveris 自动准备 ====================

download_file() {
    local url="$1"
    local destination="$2"
    DOWNLOAD_URL="${url}" DOWNLOAD_DESTINATION="${destination}" "${PYTHON_BIN}" - <<'PY'
import os
import pathlib
import urllib.request

url = os.environ["DOWNLOAD_URL"]
destination = pathlib.Path(os.environ["DOWNLOAD_DESTINATION"])
temporary = destination.with_suffix(destination.suffix + ".part")
print(f"正在下载 {url}", flush=True)
try:
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
    temporary.replace(destination)
except BaseException:
    temporary.unlink(missing_ok=True)
    raise
PY
}

prepare_audiveris() {
    local local_install_root="${LOCAL_TOOLS_DIR}/audiveris-${AUDIVERIS_VERSION}"
    local local_binary="${local_install_root}/opt/audiveris/bin/Audiveris"
    local system_binary="/opt/audiveris/bin/Audiveris"

    if [[ -n "${AUDIVERIS_BIN}" ]]; then
        if [[ "${AUDIVERIS_BIN}" == */* ]]; then
            [[ -x "${AUDIVERIS_BIN}" ]] || fail "AUDIVERIS_BIN 不可执行：${AUDIVERIS_BIN}"
            AUDIVERIS_BIN="$(cd -- "$(dirname -- "${AUDIVERIS_BIN}")" && pwd)/$(basename -- "${AUDIVERIS_BIN}")"
        else
            AUDIVERIS_BIN="$(command -v "${AUDIVERIS_BIN}" || true)"
            [[ -n "${AUDIVERIS_BIN}" ]] || fail "PATH 中找不到配置的 Audiveris"
        fi
        return
    fi

    if [[ -x "${local_binary}" ]]; then
        AUDIVERIS_BIN="${local_binary}"
        return
    fi
    if command -v audiveris >/dev/null 2>&1; then
        AUDIVERIS_BIN="$(command -v audiveris)"
        return
    fi
    if command -v Audiveris >/dev/null 2>&1; then
        AUDIVERIS_BIN="$(command -v Audiveris)"
        return
    fi
    if [[ -x "${system_binary}" ]]; then
        AUDIVERIS_BIN="${system_binary}"
        return
    fi

    [[ "${AUTO_INSTALL_AUDIVERIS}" == "true" ]] || fail "没有找到 Audiveris；请配置 AUDIVERIS_BIN 或启用自动准备"
    [[ "$(uname -m)" == "x86_64" ]] || fail "自动准备当前只支持 Linux x86_64"
    require_command dpkg-deb
    require_command sha256sum

    mkdir -p "${LOCAL_TOOLS_DIR}/downloads"
    local package="${LOCAL_TOOLS_DIR}/downloads/Audiveris-${AUDIVERIS_VERSION}-ubuntu24.04-x86_64.deb"
    if [[ ! -f "${package}" ]]; then
        download_file "${AUDIVERIS_PACKAGE_URL}" "${package}"
    fi

    local actual_sha256
    actual_sha256="$(sha256sum "${package}" | awk '{print $1}')"
    [[ "${actual_sha256}" == "${AUDIVERIS_PACKAGE_SHA256}" ]] || fail "Audiveris 安装包 SHA-256 校验失败"

    local temporary_root
    temporary_root="$(mktemp -d "${LOCAL_TOOLS_DIR}/.audiveris-extract.XXXXXX")"
    if ! dpkg-deb -x "${package}" "${temporary_root}"; then
        rm -rf -- "${temporary_root}"
        fail "无法解压 Audiveris 安装包"
    fi
    [[ -x "${temporary_root}/opt/audiveris/bin/Audiveris" ]] || {
        rm -rf -- "${temporary_root}"
        fail "安装包中没有找到 Audiveris 可执行文件"
    }
    if [[ -e "${local_install_root}" ]]; then
        rm -rf -- "${temporary_root}"
        fail "本地 Audiveris 目录不完整，请检查 ${local_install_root}"
    fi
    mv -- "${temporary_root}" "${local_install_root}"
    AUDIVERIS_BIN="${local_binary}"
    echo "Audiveris ${AUDIVERIS_VERSION} 已准备到 ${local_install_root}"
}

# ==================== 服务健康检查 ====================

wait_for_health() {
    local service_name="$1"
    local url="$2"
    local token="$3"
    HEALTH_SERVICE_NAME="${service_name}" HEALTH_URL="${url}" HEALTH_TOKEN="${token}" "${PYTHON_BIN}" - <<'PY'
import os
import time
import urllib.error
import urllib.request

name = os.environ["HEALTH_SERVICE_NAME"]
url = os.environ["HEALTH_URL"]
token = os.environ["HEALTH_TOKEN"]
request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
last_error = None
for _ in range(50):
    try:
        with urllib.request.urlopen(request, timeout=2) as response:
            if response.status == 200:
                print(f"{name} 健康检查通过：{url}", flush=True)
                raise SystemExit(0)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        last_error = error
    time.sleep(0.2)
raise SystemExit(f"错误：{name} 健康检查失败：{last_error}")
PY
}

# ==================== Android 端口映射 ====================

configure_adb_reverse() {
    [[ "${ENABLE_ADB_REVERSE}" == "true" ]] || return 0
    if ! command -v adb >/dev/null 2>&1; then
        echo "提示：未找到 adb，跳过 Android 端口映射。"
        return 0
    fi
    local device_count
    device_count="$(adb devices | awk 'NR > 1 && $2 == "device" {count++} END {print count + 0}')"
    if [[ "${device_count}" != "1" ]]; then
        echo "提示：当前可用 Android 设备数为 ${device_count}，跳过自动端口映射。"
        return 0
    fi
    adb reverse "tcp:${GPIANO_OMR_PORT}" "tcp:${GPIANO_OMR_PORT}"
    adb reverse "tcp:${GPIANO_AI_PORT}" "tcp:${GPIANO_AI_PORT}"
    echo "Android 端口映射已建立：OMR ${GPIANO_OMR_PORT}，AI ${GPIANO_AI_PORT}"
}

# ==================== 配置验证 ====================

require_command "${PYTHON_BIN}"
check_python_version
require_value GPIANO_OMR_TOKEN
require_value GPIANO_AI_SERVICE_TOKEN
require_value GPIANO_MODEL_API_KEY
require_value GPIANO_MODEL_NAME

case "${GPIANO_MODEL_NAME}" in
    deepseek-v4-flash|deepseek-v4-pro) ;;
    *) fail "GPIANO_MODEL_NAME 只允许 deepseek-v4-flash 或 deepseek-v4-pro" ;;
esac

[[ "${GPIANO_MODEL_ENDPOINT}" == https://* ]] || fail "模型端点必须使用 HTTPS"
[[ "${GPIANO_OMR_PORT}" != "${GPIANO_AI_PORT}" || "${GPIANO_OMR_HOST}" != "${GPIANO_AI_HOST}" ]] || fail "OMR 与 AI 服务不能监听同一个地址和端口"

prepare_audiveris

if [[ "${CHECK_ONLY}" == "true" ]]; then
    echo "配置与依赖检查通过。"
    echo "Audiveris：${AUDIVERIS_BIN}"
    echo "模型：${GPIANO_MODEL_NAME}"
    exit 0
fi

check_port_available "${GPIANO_OMR_HOST}" "${GPIANO_OMR_PORT}"
check_port_available "${GPIANO_AI_HOST}" "${GPIANO_AI_PORT}"

# ==================== 启动与退出清理 ====================

OMR_PID=""
AI_PID=""

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ -n "${OMR_PID}" ]] && kill -0 "${OMR_PID}" 2>/dev/null; then
        kill "${OMR_PID}" 2>/dev/null || true
    fi
    if [[ -n "${AI_PID}" ]] && kill -0 "${AI_PID}" 2>/dev/null; then
        kill "${AI_PID}" 2>/dev/null || true
    fi
    [[ -z "${OMR_PID}" ]] || wait "${OMR_PID}" 2>/dev/null || true
    [[ -z "${AI_PID}" ]] || wait "${AI_PID}" 2>/dev/null || true
    echo "Gpiano 后端已停止。"
    exit "${status}"
}

trap cleanup EXIT
trap 'exit 130' INT TERM

(
    # Ctrl+C 由父启动器统一处理，避免子进程打印无意义的 KeyboardInterrupt 堆栈。
    trap '' INT
    export PYTHONDONTWRITEBYTECODE=1
    export AUDIVERIS_BIN GPIANO_OMR_HOST GPIANO_OMR_PORT GPIANO_OMR_TOKEN
    export GPIANO_OMR_DATA GPIANO_OMR_WORKERS GPIANO_OMR_TIMEOUT
    exec "${PYTHON_BIN}" "${PROJECT_ROOT}/omr-service/server.py"
) &
OMR_PID=$!

(
    # Ctrl+C 由父启动器统一处理，避免子进程打印无意义的 KeyboardInterrupt 堆栈。
    trap '' INT
    export PYTHONDONTWRITEBYTECODE=1
    export GPIANO_AI_HOST GPIANO_AI_PORT GPIANO_AI_SERVICE_TOKEN
    export GPIANO_MODEL_ENDPOINT GPIANO_MODEL_API_KEY GPIANO_MODEL_NAME GPIANO_MODEL_TIMEOUT
    exec "${PYTHON_BIN}" "${PROJECT_ROOT}/practice-ai-service/server.py"
) &
AI_PID=$!

wait_for_health "OMR companion" "http://${GPIANO_OMR_HOST}:${GPIANO_OMR_PORT}/health" "${GPIANO_OMR_TOKEN}"
wait_for_health "AI companion" "http://${GPIANO_AI_HOST}:${GPIANO_AI_PORT}/health" "${GPIANO_AI_SERVICE_TOKEN}"
configure_adb_reverse

echo
echo "Gpiano 后端已全部启动。"
echo "Android Debug OMR 地址：http://127.0.0.1:${GPIANO_OMR_PORT}"
echo "Android Debug AI 地址：http://127.0.0.1:${GPIANO_AI_PORT}"
echo "访问令牌请查看：${LOCAL_CONFIG}"
echo "按 Ctrl+C 同时停止两个服务。"

set +e
wait -n "${OMR_PID}" "${AI_PID}"
service_status=$?
set -e
fail "有后端服务意外退出（状态码 ${service_status}）"
