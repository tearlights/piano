#!/usr/bin/env python3
"""Authenticated, provider-neutral generative practice companion for Gpiano."""

from __future__ import annotations

import hmac
import ipaddress
import json
import os
import socket
import threading
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable


MAX_REQUEST_BYTES = 512 * 1024
MAX_PROVIDER_BYTES = 512 * 1024
ALLOWED_HANDS = {"Both", "Right", "Left"}
ALLOWED_PRESETS = {"right-hand", "left-hand", "reduced-reach"}


@dataclass(frozen=True)
class Config:
    service_token: str
    host: str
    port: int
    provider_endpoint: str
    provider_token: str
    model: str
    timeout_seconds: int
    socket_timeout_seconds: int = 15
    http_workers: int = 16

    @property
    def model_ready(self) -> bool:
        return bool(self.provider_endpoint and self.provider_token and self.model)

    @classmethod
    def from_environment(cls) -> "Config":
        service_token = os.environ.get("GPIANO_AI_SERVICE_TOKEN", "").strip()
        if not service_token:
            raise SystemExit("GPIANO_AI_SERVICE_TOKEN is required")
        endpoint = os.environ.get("GPIANO_MODEL_ENDPOINT", "").strip()
        if endpoint:
            validate_provider_endpoint(
                endpoint,
                allow_insecure_loopback=os.environ.get("GPIANO_AI_ALLOW_INSECURE_LOOPBACK", "").lower() == "true",
            )
        return cls(
            service_token=service_token,
            host=os.environ.get("GPIANO_AI_HOST", "127.0.0.1"),
            port=int(os.environ.get("GPIANO_AI_PORT", "8766")),
            provider_endpoint=endpoint,
            provider_token=os.environ.get("GPIANO_MODEL_API_KEY", "").strip(),
            model=os.environ.get("GPIANO_MODEL_NAME", "").strip(),
            timeout_seconds=max(5, int(os.environ.get("GPIANO_MODEL_TIMEOUT", "90"))),
            socket_timeout_seconds=max(1, int(os.environ.get("GPIANO_AI_SOCKET_TIMEOUT", "15"))),
            http_workers=max(1, int(os.environ.get("GPIANO_AI_HTTP_WORKERS", "16"))),
        )


class RequestProblem(Exception):
    def __init__(self, code: str, message: str, status: HTTPStatus = HTTPStatus.BAD_REQUEST):
        super().__init__(message)
        self.code = code
        self.status = status


def validate_provider_endpoint(value: str, allow_insecure_loopback: bool = False) -> None:
    parsed = urllib.parse.urlsplit(value)
    hostname = parsed.hostname
    loopback = hostname in {"127.0.0.1", "localhost", "::1"}
    secure = parsed.scheme == "https"
    allowed_development_url = allow_insecure_loopback and loopback and parsed.scheme == "http"
    if (not secure and not allowed_development_url) or not hostname or parsed.username or parsed.password:
        raise ValueError(
            "GPIANO_MODEL_ENDPOINT must use HTTPS; explicit development mode only permits HTTP loopback",
        )
    if allowed_development_url:
        return
    try:
        addresses = {item[4][0] for item in socket.getaddrinfo(hostname, parsed.port or 443, type=socket.SOCK_STREAM)}
    except socket.gaierror as error:
        raise ValueError("GPIANO_MODEL_ENDPOINT host cannot be resolved") from error
    if not addresses or any(not ipaddress.ip_address(address).is_global for address in addresses):
        raise ValueError("GPIANO_MODEL_ENDPOINT must resolve only to public network addresses")


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        return None


def validate_request(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict) or payload.get("schemaVersion") != 1:
        raise RequestProblem("invalid_request", "请求版本无效")
    if payload.get("consent") is not True:
        raise RequestProblem("consent_required", "本次请求缺少用户发送授权")
    question = payload.get("question")
    if not isinstance(question, str) or not question.strip() or len(question) > 800:
        raise RequestProblem("invalid_question", "问题必须为 1～800 个字符")
    selection = payload.get("selection")
    if not isinstance(selection, dict):
        raise RequestProblem("invalid_selection", "选段信息缺失")
    start = selection.get("fromMeasure")
    end = selection.get("toMeasure")
    if not isinstance(start, int) or not isinstance(end, int) or start < 1 or end < start:
        raise RequestProblem("invalid_selection", "选段范围无效")
    if selection.get("hand") not in ALLOWED_HANDS:
        raise RequestProblem("invalid_selection", "选段手别无效")
    speed = selection.get("speed")
    if not isinstance(speed, (int, float)) or not 0.25 <= speed <= 2.0:
        raise RequestProblem("invalid_selection", "选段速度无效")
    score = payload.get("score")
    events = score.get("events") if isinstance(score, dict) else None
    if not isinstance(events, list) or not events or len(events) > 500:
        raise RequestProblem("invalid_score", "选段事件必须为 1～500 个")
    event_ids: set[str] = set()
    for event in events:
        if not isinstance(event, dict):
            raise RequestProblem("invalid_score", "选段事件格式无效")
        event_id = event.get("id")
        measure = event.get("measure")
        if not isinstance(event_id, str) or not event_id or event_id in event_ids:
            raise RequestProblem("invalid_score", "选段事件标识为空或重复")
        if not isinstance(measure, int) or measure < start or measure > end:
            raise RequestProblem("invalid_score", "选段事件超出授权范围")
        event_ids.add(event_id)
    return payload


def normalize_model_response(content: str, request_payload: dict[str, Any]) -> dict[str, Any]:
    text = content.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if len(lines) >= 3 and lines[-1].strip() == "```":
            text = "\n".join(lines[1:-1])
            if text.lstrip().startswith("json"):
                text = text.lstrip()[4:].lstrip()
    try:
        result = json.loads(text)
    except (TypeError, json.JSONDecodeError) as error:
        raise RequestProblem("invalid_model_output", "模型没有返回有效 JSON", HTTPStatus.BAD_GATEWAY) from error
    if not isinstance(result, dict):
        raise RequestProblem("invalid_model_output", "模型结果格式无效", HTTPStatus.BAD_GATEWAY)
    answer = result.get("answer")
    steps = result.get("practiceSteps")
    if not isinstance(answer, str) or not answer.strip() or len(answer) > 4000:
        raise RequestProblem("invalid_model_output", "模型回答为空或过长", HTTPStatus.BAD_GATEWAY)
    if not isinstance(steps, list) or not 1 <= len(steps) <= 6 or any(
        not isinstance(step, str) or not step.strip() or len(step) > 500 for step in steps
    ):
        raise RequestProblem("invalid_model_output", "模型练习步骤无效", HTTPStatus.BAD_GATEWAY)

    selection = request_payload["selection"]
    allowed_events = {event["id"] for event in request_payload["score"]["events"]}
    evidence = result.get("evidence", [])
    if not isinstance(evidence, list) or not 1 <= len(evidence) <= 12:
        raise RequestProblem("invalid_model_output", "模型引用数量无效", HTTPStatus.BAD_GATEWAY)
    for item in evidence:
        measures = item.get("measureIndexes") if isinstance(item, dict) else None
        event_ids = item.get("eventIds") if isinstance(item, dict) else None
        summary = item.get("summary") if isinstance(item, dict) else None
        if (
            not isinstance(measures, list)
            or not measures
            or any(not isinstance(value, int) or value < selection["fromMeasure"] or value > selection["toMeasure"] for value in measures)
            or not isinstance(event_ids, list)
            or not event_ids
            or any(not isinstance(value, str) or value not in allowed_events for value in event_ids)
            or not isinstance(summary, str)
            or not summary.strip()
            or len(summary) > 500
        ):
            raise RequestProblem("invalid_model_output", "模型引用了授权范围外的谱面内容", HTTPStatus.BAD_GATEWAY)

    playback = result.get("suggestedPlayback")
    if playback is not None:
        if not isinstance(playback, dict):
            raise RequestProblem("invalid_model_output", "模型试听建议无效", HTTPStatus.BAD_GATEWAY)
        speed = playback.get("speed")
        if (
            not isinstance(speed, (int, float))
            or not 0.25 <= speed <= 1.5
            or playback.get("hand") not in ALLOWED_HANDS
            or not isinstance(playback.get("looping"), bool)
        ):
            raise RequestProblem("invalid_model_output", "模型试听参数超出范围", HTTPStatus.BAD_GATEWAY)
    preset = result.get("practiceVersionPreset")
    if preset is not None and preset not in ALLOWED_PRESETS:
        raise RequestProblem("invalid_model_output", "模型练习版本类型不受支持", HTTPStatus.BAD_GATEWAY)
    return {
        "schemaVersion": 1,
        "answer": answer.strip(),
        "practiceSteps": [step.strip() for step in steps],
        "evidence": evidence,
        "suggestedPlayback": playback,
        "practiceVersionPreset": preset,
    }


def provider_request(
    config: Config,
    payload: dict[str, Any],
    opener: Callable[..., Any] | None = None,
) -> dict[str, Any]:
    if not config.model_ready:
        raise RequestProblem("model_not_configured", "生成式模型尚未配置", HTTPStatus.SERVICE_UNAVAILABLE)
    system_prompt = (
        "你是面向钢琴学习者的练习助手。只能依据用户提供的结构化选段和本地证据回答，不得虚构谱面事实。"
        "输出一个 JSON 对象：answer 字符串；practiceSteps 为 1 到 6 个可执行步骤；evidence 为数组，每项含 "
        "measureIndexes、eventIds、summary；suggestedPlayback 可为 null 或含 speed、hand(Both/Right/Left)、looping；"
        "practiceVersionPreset 可为 null、right-hand、left-hand、reduced-reach。不要输出 Markdown 或 XML。"
    )
    provider_body = json.dumps(
        {
            "model": config.model,
            "temperature": 0.2,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False, separators=(",", ":"))},
            ],
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        config.provider_endpoint,
        data=provider_body,
        method="POST",
        headers={
            "Authorization": f"Bearer {config.provider_token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    request_opener = opener or urllib.request.build_opener(NoRedirectHandler()).open
    try:
        with request_opener(request, timeout=config.timeout_seconds) as response:
            raw = response.read(MAX_PROVIDER_BYTES + 1)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise RequestProblem("provider_unavailable", "模型服务暂时不可用", HTTPStatus.BAD_GATEWAY) from error
    if len(raw) > MAX_PROVIDER_BYTES:
        raise RequestProblem("provider_response_too_large", "模型响应过大", HTTPStatus.BAD_GATEWAY)
    try:
        envelope = json.loads(raw.decode("utf-8"))
        content = envelope["choices"][0]["message"]["content"]
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError, IndexError, TypeError) as error:
        raise RequestProblem("invalid_provider_response", "模型服务响应格式无效", HTTPStatus.BAD_GATEWAY) from error
    return normalize_model_response(content, payload)


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    daemon_threads = True

    def configure_transport(self, socket_timeout_seconds: int, http_workers: int) -> None:
        self.socket_timeout_seconds = socket_timeout_seconds
        self._request_slots = threading.BoundedSemaphore(http_workers)

    def get_request(self) -> tuple[socket.socket, Any]:
        request, client_address = super().get_request()
        request.settimeout(self.socket_timeout_seconds)
        return request, client_address

    def process_request(self, request: socket.socket, client_address: Any) -> None:
        if not self._request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._request_slots.release()
            raise

    def process_request_thread(self, request: socket.socket, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()


class PracticeAiServer(BoundedThreadingHTTPServer):

    def __init__(self, config: Config):
        self.config = config
        self.configure_transport(config.socket_timeout_seconds, config.http_workers)
        super().__init__((config.host, config.port), PracticeAiHandler)


class PracticeAiHandler(BaseHTTPRequestHandler):
    server: PracticeAiServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        if not self.authorized():
            return
        if urllib.parse.urlsplit(self.path).path != "/health":
            self.send_problem(RequestProblem("not_found", "接口不存在", HTTPStatus.NOT_FOUND))
            return
        ready = self.server.config.model_ready
        self.send_json(
            HTTPStatus.OK if ready else HTTPStatus.SERVICE_UNAVAILABLE,
            {"status": "ok" if ready else "unavailable", "modelConfigured": ready},
        )

    def do_POST(self) -> None:
        if not self.authorized():
            return
        if urllib.parse.urlsplit(self.path).path != "/v1/analyze":
            self.send_problem(RequestProblem("not_found", "接口不存在", HTTPStatus.NOT_FOUND))
            return
        try:
            length = int(self.headers.get("Content-Length", "-1"))
            if length < 1 or length > MAX_REQUEST_BYTES:
                raise RequestProblem("request_too_large", "请求为空或过大", HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
            raw = self.rfile.read(length)
            payload = validate_request(json.loads(raw.decode("utf-8")))
            self.send_json(HTTPStatus.OK, provider_request(self.server.config, payload))
        except RequestProblem as error:
            self.send_problem(error)
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            self.send_problem(RequestProblem("invalid_json", "请求 JSON 无效"))

    def authorized(self) -> bool:
        expected = f"Bearer {self.server.config.service_token}"
        actual = self.headers.get("Authorization", "")
        if not tokens_equal(actual, expected):
            self.send_problem(RequestProblem("unauthorized", "访问令牌无效", HTTPStatus.UNAUTHORIZED))
            return False
        return True

    def send_problem(self, problem: RequestProblem) -> None:
        self.send_json(problem.status, {"code": problem.code, "message": str(problem)})

    def send_json(self, status: HTTPStatus, value: dict[str, Any]) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        return


def tokens_equal(actual: str, expected: str) -> bool:
    try:
        return hmac.compare_digest(actual.encode("ascii"), expected.encode("ascii"))
    except UnicodeEncodeError:
        return False


def main() -> None:
    config = Config.from_environment()
    with PracticeAiServer(config) as server:
        print(f"Gpiano practice AI companion listening on {config.host}:{config.port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
