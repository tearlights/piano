import io
import json
import unittest
from unittest.mock import patch

from server import (
    Config,
    NoRedirectHandler,
    RequestProblem,
    normalize_model_response,
    provider_request,
    validate_provider_endpoint,
    validate_request,
)


REQUEST = {
    "schemaVersion": 1,
    "consent": True,
    "question": "这组三连音怎么练？",
    "selection": {"fromMeasure": 1, "toMeasure": 1, "hand": "Right", "speed": 0.75},
    "score": {"events": [{"id": "p0:m1:n0", "measure": 1, "pitch": "F♯4"}]},
    "localAnalysis": {"guidance": []},
}


class FakeResponse:
    def __init__(self, value):
        self.value = value

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return None

    def read(self, _limit):
        return self.value


class PracticeAiServiceTest(unittest.TestCase):
    def test_rejects_private_provider_addresses(self):
        with patch("server.socket.getaddrinfo", return_value=[(2, 1, 6, "", ("192.168.1.10", 443))]):
            with self.assertRaises(ValueError):
                validate_provider_endpoint("https://model.example/v1/chat")

    def test_allows_explicit_http_loopback_only_for_development(self):
        with self.assertRaises(ValueError):
            validate_provider_endpoint("http://127.0.0.1:8443/v1/chat")
        validate_provider_endpoint("http://127.0.0.1:8443/v1/chat", allow_insecure_loopback=True)

    def test_provider_redirects_are_not_followed(self):
        handler = NoRedirectHandler()
        self.assertIsNone(handler.redirect_request(None, None, 302, "Found", {}, "https://attacker.example"))

    def test_requires_explicit_consent(self):
        payload = dict(REQUEST, consent=False)
        with self.assertRaises(RequestProblem) as problem:
            validate_request(payload)
        self.assertEqual("consent_required", problem.exception.code)

    def test_rejects_evidence_outside_sent_events(self):
        content = json.dumps(
            {
                "answer": "先均分。",
                "practiceSteps": ["慢练三遍"],
                "evidence": [{"measureIndexes": [1], "eventIds": ["invented"], "summary": "三连音"}],
                "suggestedPlayback": None,
                "practiceVersionPreset": None,
            },
            ensure_ascii=False,
        )
        with self.assertRaises(RequestProblem) as problem:
            normalize_model_response(content, REQUEST)
        self.assertEqual("invalid_model_output", problem.exception.code)

    def test_rejects_unanchored_evidence(self):
        content = json.dumps(
            {
                "answer": "先均分。",
                "practiceSteps": ["慢练三遍"],
                "evidence": [{"measureIndexes": [], "eventIds": [], "summary": "没有可核对位置"}],
                "suggestedPlayback": None,
                "practiceVersionPreset": None,
            },
            ensure_ascii=False,
        )
        with self.assertRaises(RequestProblem) as problem:
            normalize_model_response(content, REQUEST)
        self.assertEqual("invalid_model_output", problem.exception.code)

    def test_calls_compatible_provider_and_normalizes_result(self):
        content = {
            "answer": "把三个音均匀放进一拍。",
            "practiceSteps": ["先数 1-2-3", "50% 速度循环"],
            "evidence": [{"measureIndexes": [1], "eventIds": ["p0:m1:n0"], "summary": "该事件属于三连音"}],
            "suggestedPlayback": {"speed": 0.5, "hand": "Right", "looping": True},
            "practiceVersionPreset": "right-hand",
        }
        envelope = json.dumps({"choices": [{"message": {"content": json.dumps(content, ensure_ascii=False)}}]}).encode()
        captured = {}

        def opener(request, timeout):
            captured["body"] = json.loads(request.data.decode())
            captured["timeout"] = timeout
            return FakeResponse(envelope)

        config = Config("service", "127.0.0.1", 8766, "https://model.example/v1/chat/completions", "key", "model", 30)
        result = provider_request(config, validate_request(REQUEST), opener)
        self.assertEqual("把三个音均匀放进一拍。", result["answer"])
        self.assertEqual("right-hand", result["practiceVersionPreset"])
        self.assertEqual("model", captured["body"]["model"])
        self.assertNotIn("key", json.dumps(captured["body"]))


if __name__ == "__main__":
    unittest.main()
