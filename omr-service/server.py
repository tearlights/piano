#!/usr/bin/env python3
"""Small authenticated Audiveris companion for Gpiano.

SPDX-License-Identifier: AGPL-3.0-or-later
"""

from __future__ import annotations

import hmac
import json
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path, PurePosixPath
from typing import Any
from xml.etree import ElementTree


MAX_INPUT_BYTES = 25 * 1024 * 1024
MAX_MUSIC_XML_BYTES = 20 * 1024 * 1024
JOB_ID = re.compile(r"^[0-9a-f-]{36}$")
SUPPORTED_TYPES = {
    "image/png": ".png",
    "image/jpeg": ".jpg",
    "image/webp": ".webp",
}


@dataclass(frozen=True)
class Config:
    token: str
    host: str
    port: int
    data_dir: Path
    audiveris_bin: str
    timeout_seconds: int
    workers: int
    socket_timeout_seconds: int = 15
    http_workers: int = 16
    max_jobs: int = 500
    job_ttl_seconds: int = 7 * 24 * 60 * 60

    @classmethod
    def from_environment(cls) -> "Config":
        token = os.environ.get("GPIANO_OMR_TOKEN", "").strip()
        if not token:
            raise SystemExit("GPIANO_OMR_TOKEN is required")
        data_dir = Path(os.environ.get("GPIANO_OMR_DATA", "omr-service/var")).resolve()
        return cls(
            token=token,
            host=os.environ.get("GPIANO_OMR_HOST", "127.0.0.1"),
            port=int(os.environ.get("GPIANO_OMR_PORT", "8765")),
            data_dir=data_dir,
            audiveris_bin=os.environ.get("AUDIVERIS_BIN", "audiveris"),
            timeout_seconds=int(os.environ.get("GPIANO_OMR_TIMEOUT", "900")),
            workers=max(1, int(os.environ.get("GPIANO_OMR_WORKERS", "1"))),
            socket_timeout_seconds=max(1, int(os.environ.get("GPIANO_OMR_SOCKET_TIMEOUT", "15"))),
            http_workers=max(1, int(os.environ.get("GPIANO_OMR_HTTP_WORKERS", "16"))),
            max_jobs=max(1, int(os.environ.get("GPIANO_OMR_MAX_JOBS", "500"))),
            job_ttl_seconds=max(60, int(os.environ.get("GPIANO_OMR_JOB_TTL", str(7 * 24 * 60 * 60)))),
        )


class JobCapacityError(Exception):
    pass


class JobStore:
    def __init__(self, root: Path, max_jobs: int = 500, ttl_seconds: int = 7 * 24 * 60 * 60):
        self.root = root
        self.max_jobs = max_jobs
        self.ttl_seconds = ttl_seconds
        self.root.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()

    def create(self, mime_type: str, original_name: str, content: bytes) -> dict[str, Any]:
        with self._lock:
            now = int(time.time() * 1000)
            self._cleanup_locked(now)
            if sum(1 for path in self.root.iterdir() if path.is_dir()) >= self.max_jobs:
                raise JobCapacityError("OMR job capacity reached")
            job_id = str(uuid.uuid4())
            job_dir = self.job_dir(job_id)
            job_dir.mkdir(mode=0o700)
            try:
                extension = SUPPORTED_TYPES[mime_type]
                input_name = f"input{extension}"
                (job_dir / input_name).write_bytes(content)
                job = {
                    "jobId": job_id,
                    "status": "queued",
                    "stage": "queued",
                    "inputName": input_name,
                    "originalName": safe_display_name(original_name),
                    "mimeType": mime_type,
                    "createdAt": now,
                    "updatedAt": now,
                    "errorCode": None,
                    "errorMessage": None,
                    "diagnostics": None,
                }
                self.save(job)
                return job
            except BaseException:
                shutil.rmtree(job_dir, ignore_errors=True)
                raise

    def cleanup(self, now_ms: int | None = None) -> int:
        with self._lock:
            return self._cleanup_locked(now_ms if now_ms is not None else int(time.time() * 1000))

    def _cleanup_locked(self, now_ms: int) -> int:
        removed = 0
        ttl_ms = self.ttl_seconds * 1000
        for job_dir in self.root.iterdir():
            if not job_dir.is_dir():
                continue
            job_path = job_dir / "job.json"
            try:
                job = json.loads(job_path.read_text(encoding="utf-8"))
                status = job.get("status")
                updated_at = int(job.get("updatedAt", 0))
                expired = status in {"ready", "failed"} and now_ms - updated_at >= ttl_ms
            except (OSError, TypeError, ValueError, json.JSONDecodeError):
                try:
                    expired = now_ms - int(job_dir.stat().st_mtime * 1000) >= ttl_ms
                except OSError:
                    expired = False
            if expired:
                shutil.rmtree(job_dir, ignore_errors=True)
                removed += 1
        return removed

    def load(self, job_id: str) -> dict[str, Any] | None:
        if not JOB_ID.fullmatch(job_id):
            return None
        path = self.job_dir(job_id) / "job.json"
        if not path.is_file():
            return None
        with self._lock:
            return json.loads(path.read_text(encoding="utf-8"))

    def save(self, job: dict[str, Any]) -> None:
        job_id = str(job["jobId"])
        if not JOB_ID.fullmatch(job_id):
            raise ValueError("invalid job id")
        job["updatedAt"] = int(time.time() * 1000)
        target = self.job_dir(job_id) / "job.json"
        temporary = target.with_suffix(".tmp")
        with self._lock:
            temporary.write_text(json.dumps(job, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            os.replace(temporary, target)

    def transition(self, job_id: str, **changes: Any) -> dict[str, Any]:
        with self._lock:
            job = self.load(job_id)
            if job is None:
                raise KeyError(job_id)
            job.update(changes)
            self.save(job)
            return job

    def resumable(self) -> list[str]:
        result: list[str] = []
        for path in self.root.glob("*/job.json"):
            try:
                job = json.loads(path.read_text(encoding="utf-8"))
                if job.get("status") in {"queued", "running"}:
                    self.transition(job["jobId"], status="queued", stage="queued-after-restart")
                    result.append(job["jobId"])
            except (OSError, ValueError, KeyError, json.JSONDecodeError):
                continue
        return result

    def job_dir(self, job_id: str) -> Path:
        return self.root / job_id


class AudiverisRunner:
    def __init__(self, config: Config, store: JobStore):
        self.config = config
        self.store = store
        self.executor = ThreadPoolExecutor(max_workers=config.workers, thread_name_prefix="audiveris")

    def submit(self, job_id: str) -> None:
        self.executor.submit(self._run, job_id)

    def _run(self, job_id: str) -> None:
        job = self.store.load(job_id)
        if job is None or job.get("status") == "ready":
            return
        job_dir = self.store.job_dir(job_id)
        output_dir = job_dir / "output"
        home_dir = job_dir / "home"
        shutil.rmtree(output_dir, ignore_errors=True)
        output_dir.mkdir()
        home_dir.mkdir(exist_ok=True)
        log_path = job_dir / "audiveris.log"
        self.store.transition(job_id, status="running", stage="audiveris", errorCode=None, errorMessage=None)
        started = time.monotonic()
        command = [
            self.config.audiveris_bin,
            "-batch",
            "-transcribe",
            "-export",
            "-save",
            "-constant",
            "org.audiveris.omr.sheet.BookManager.useCompression=false",
            "-output",
            str(output_dir),
            "--",
            str(job_dir / job["inputName"]),
        ]
        environment = os.environ.copy()
        environment["HOME"] = str(home_dir)
        try:
            with log_path.open("wb") as log:
                completed = subprocess.run(
                    command,
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    env=environment,
                    timeout=self.config.timeout_seconds,
                    check=False,
                )
            if completed.returncode != 0:
                raise RecognitionFailure("engine_failed", "Audiveris 未能完成这张谱面的识别")
            music_xml = find_music_xml(output_dir)
            result = job_dir / "result.musicxml"
            result.write_bytes(music_xml)
            duration = int((time.monotonic() - started) * 1000)
            self.store.transition(
                job_id,
                status="ready",
                stage="ready",
                diagnostics={
                    "engine": "Audiveris",
                    "durationMs": duration,
                    "confidenceAvailable": False,
                },
            )
        except subprocess.TimeoutExpired:
            self._fail(job_id, "timeout", "识别超时，请检查图片质量后重试")
        except RecognitionFailure as error:
            self._fail(job_id, error.code, str(error))
        except (OSError, ValueError, zipfile.BadZipFile, ElementTree.ParseError):
            self._fail(job_id, "invalid_output", "Audiveris 没有生成可用的 MusicXML")

    def _fail(self, job_id: str, code: str, message: str) -> None:
        self.store.transition(
            job_id,
            status="failed",
            stage="failed",
            errorCode=code,
            errorMessage=message,
            diagnostics={"engine": "Audiveris", "confidenceAvailable": False},
        )


class RecognitionFailure(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    daemon_threads = True

    def configure_transport(self, socket_timeout_seconds: int, http_workers: int) -> None:
        self.socket_timeout_seconds = socket_timeout_seconds
        self._request_slots = threading.BoundedSemaphore(http_workers)

    def get_request(self) -> tuple[Any, Any]:
        request, client_address = super().get_request()
        request.settimeout(self.socket_timeout_seconds)
        return request, client_address

    def process_request(self, request: Any, client_address: Any) -> None:
        if not self._request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._request_slots.release()
            raise

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()


class OmrServer(BoundedThreadingHTTPServer):

    def __init__(self, config: Config):
        self.config = config
        self.store = JobStore(config.data_dir / "jobs", config.max_jobs, config.job_ttl_seconds)
        self.store.cleanup()
        self.runner = AudiverisRunner(config, self.store)
        self.configure_transport(config.socket_timeout_seconds, config.http_workers)
        super().__init__((config.host, config.port), OmrHandler)
        for job_id in self.store.resumable():
            self.runner.submit(job_id)


class OmrHandler(BaseHTTPRequestHandler):
    server: OmrServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        if not self.authorized():
            return
        path = urllib.parse.urlsplit(self.path).path
        if path == "/health":
            available = shutil.which(self.server.config.audiveris_bin) is not None or Path(self.server.config.audiveris_bin).is_file()
            self.send_json(
                HTTPStatus.OK if available else HTTPStatus.SERVICE_UNAVAILABLE,
                {"status": "ok" if available else "unavailable", "engine": "Audiveris"},
            )
            return
        match = re.fullmatch(r"/v1/jobs/([0-9a-f-]{36})(/musicxml)?", path)
        if not match:
            self.send_error_json(HTTPStatus.NOT_FOUND, "not_found", "接口不存在")
            return
        job = self.server.store.load(match.group(1))
        if job is None:
            self.send_error_json(HTTPStatus.NOT_FOUND, "job_not_found", "识别任务不存在")
            return
        if match.group(2):
            if job["status"] != "ready":
                self.send_error_json(HTTPStatus.CONFLICT, "result_not_ready", "识别结果尚未就绪")
                return
            result = self.server.store.job_dir(job["jobId"]) / "result.musicxml"
            if not result.is_file() or result.stat().st_size > MAX_MUSIC_XML_BYTES:
                self.send_error_json(HTTPStatus.INTERNAL_SERVER_ERROR, "result_missing", "识别结果文件不可用")
                return
            content = result.read_bytes()
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/vnd.recordare.musicxml+xml; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(content)
            return
        self.send_json(HTTPStatus.OK, public_job(job))

    def do_POST(self) -> None:
        if not self.authorized():
            return
        if urllib.parse.urlsplit(self.path).path != "/v1/jobs":
            self.send_error_json(HTTPStatus.NOT_FOUND, "not_found", "接口不存在")
            return
        mime_type = self.headers.get_content_type()
        if mime_type not in SUPPORTED_TYPES:
            self.send_error_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_type", "仅支持 PNG、JPEG 或 WebP")
            return
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            length = -1
        if length <= 0 or length > MAX_INPUT_BYTES:
            self.send_error_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "invalid_size", "单页图片必须小于 25 MB")
            return
        content = self.rfile.read(length)
        if len(content) != length:
            self.send_error_json(HTTPStatus.BAD_REQUEST, "incomplete_upload", "图片上传不完整")
            return
        encoded_name = self.headers.get("X-File-Name", "score")
        original_name = urllib.parse.unquote(encoded_name)
        try:
            job = self.server.store.create(mime_type, original_name, content)
        except JobCapacityError:
            self.send_error_json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                "job_capacity_reached",
                "识别任务已满，请等待现有任务完成或稍后重试",
            )
            return
        self.server.runner.submit(job["jobId"])
        self.send_json(HTTPStatus.ACCEPTED, public_job(job))

    def authorized(self) -> bool:
        supplied = self.headers.get("Authorization", "")
        expected = f"Bearer {self.server.config.token}"
        if tokens_equal(supplied, expected):
            return True
        self.send_error_json(HTTPStatus.UNAUTHORIZED, "unauthorized", "访问令牌无效")
        return False

    def send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        content = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(content)

    def send_error_json(self, status: HTTPStatus, code: str, message: str) -> None:
        self.send_json(status, {"code": code, "message": message})

    def log_message(self, format_string: str, *args: Any) -> None:
        # Deliberately omit headers, filenames and request bodies.
        print(f"{self.address_string()} - {format_string % args}")


def safe_display_name(value: str) -> str:
    first_line = value.replace("\0", "").splitlines()[0] if value.splitlines() else "score"
    name = Path(first_line.replace("\\", "/")).name.strip()
    name = "".join(character for character in name if character.isprintable() and character not in "\r\n\0")
    return name[:160] or "score"


def tokens_equal(supplied: str, expected: str) -> bool:
    """Compare even malformed/non-ASCII header values without crashing a request thread."""
    return hmac.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8"))


def public_job(job: dict[str, Any]) -> dict[str, Any]:
    return {
        "jobId": job["jobId"],
        "status": job["status"],
        "stage": job["stage"],
        "errorCode": job.get("errorCode"),
        "errorMessage": job.get("errorMessage"),
        "diagnostics": job.get("diagnostics"),
    }


def find_music_xml(output_dir: Path) -> bytes:
    candidates = sorted(
        (path for path in output_dir.rglob("*") if path.is_file() and path.suffix.lower() in {".xml", ".musicxml", ".mxl"}),
        key=lambda path: (path.suffix.lower() == ".mxl", path.stat().st_mtime),
        reverse=True,
    )
    for candidate in candidates:
        try:
            content = extract_mxl(candidate) if candidate.suffix.lower() == ".mxl" else candidate.read_bytes()
            if len(content) > MAX_MUSIC_XML_BYTES:
                continue
            root = ElementTree.fromstring(content)
            tag = root.tag.rsplit("}", 1)[-1]
            if tag in {"score-partwise", "score-timewise", "opus"}:
                return content
        except (OSError, ValueError, zipfile.BadZipFile, ElementTree.ParseError):
            continue
    raise RecognitionFailure("no_musicxml", "Audiveris 没有生成可用的 MusicXML")


def extract_mxl(path: Path) -> bytes:
    with zipfile.ZipFile(path) as archive:
        container = ElementTree.fromstring(archive.read("META-INF/container.xml"))
        root_file = next(
            (node.attrib.get("full-path") for node in container.iter() if node.tag.rsplit("}", 1)[-1] == "rootfile"),
            None,
        )
        if not root_file:
            raise ValueError("MXL container has no rootfile")
        pure = PurePosixPath(root_file)
        if pure.is_absolute() or ".." in pure.parts:
            raise ValueError("unsafe MXL rootfile")
        info = archive.getinfo(str(pure))
        if info.file_size > MAX_MUSIC_XML_BYTES:
            raise ValueError("MXL MusicXML is too large")
        return archive.read(info)


def main() -> None:
    config = Config.from_environment()
    server = OmrServer(config)
    print(f"Gpiano OMR companion listening on {config.host}:{config.port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        server.runner.executor.shutdown(wait=False, cancel_futures=True)


if __name__ == "__main__":
    main()
