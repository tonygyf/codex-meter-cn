import copy
import json
from pathlib import Path
import subprocess
import sys
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from collector import (SnapshotStore, demo_snapshot, make_server, normalize,
                       read_app_server, validate_endpoint, validate_snapshot, upload)

FIXTURE = Path(__file__).resolve().parents[3] / "packages/protocol/fixtures/snapshot-v1.json"


class ProtocolTests(unittest.TestCase):
    def test_shared_fixture(self):
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        self.assertEqual(validate_snapshot(data, data["collectedAt"]), data)

    def test_multi_bucket_preferred_and_duration_mapping(self):
        result = {"rateLimits": {"primary": {"usedPercent": 99, "windowDurationMins": 300}},
                  "rateLimitsByLimitId": {"codex": {
                      "primary": {"usedPercent": 23, "windowDurationMins": 10080, "resetsAt": None},
                      "secondary": {"usedPercent": 14, "windowDurationMins": 300, "resetsAt": 3000}}}}
        data = normalize(result, now=1000)
        self.assertEqual(data["windows"]["fiveHour"]["usedPercent"], 14)
        self.assertEqual(data["windows"]["weekly"]["usedPercent"], 23)
        self.assertEqual(set(data), {"schemaVersion", "collectedAt", "expiresAt", "windows"})

    def test_unknown_is_not_zero_and_no_ambiguous_bucket(self):
        self.assertIsNone(normalize({"rateLimits": {"primary": None}}, now=1000)["windows"]["fiveHour"])
        self.assertIsNone(normalize({"rateLimits": {"primary": {"windowDurationMins": 15, "usedPercent": 5}}}, now=1000)["windows"]["fiveHour"])
        with self.assertRaises(ValueError):
            normalize({"rateLimitsByLimitId": {"other": {}}}, now=1000)

    def test_reject_malformed_or_secret_fields(self):
        for name, value in [("schemaVersion", True), ("schemaVersion", 2), ("accessToken", "secret"),
                            ("expiresAt", 1)]:
            data = demo_snapshot()
            data[name] = value
            with self.assertRaises(ValueError): validate_snapshot(data)
        for used in (-1, 101, True, "3", float("nan")):
            data = demo_snapshot(); data["windows"]["fiveHour"]["usedPercent"] = used
            with self.assertRaises(ValueError): validate_snapshot(data)

    def test_rpc_handshake_ignores_notifications_and_redacts(self):
        script = '''import sys,json
for line in sys.stdin:
 m=json.loads(line)
 if m.get('method')=='initialize':
  assert m['params']['clientInfo']['name']=='codex_meter_cn'
  print(json.dumps({'id':m['id'],'result':{}}),flush=True)
 elif m.get('method')=='initialized': pass
 elif m.get('method')=='account/rateLimits/read':
  print(json.dumps({'method':'notice','params':{}}),flush=True)
  print(json.dumps({'id':m['id'],'result':{'rateLimits':{},'accessToken':'must-not-export'}}),flush=True)
'''
        data = normalize(read_app_server([sys.executable, "-u", "-c", script], timeout=2), now=1000)
        self.assertNotIn("must-not-export", json.dumps(data))

    def test_rpc_error_and_timeout(self):
        script = "import sys,json; m=json.loads(sys.stdin.readline()); print(json.dumps({'id':m['id'],'error':{'message':'PRIVATE'}}),flush=True)"
        with self.assertRaisesRegex(RuntimeError, "^app_server_request_failed$"):
            read_app_server([sys.executable, "-u", "-c", script], timeout=1)
        with self.assertRaisesRegex(RuntimeError, "app_server_timeout"):
            read_app_server([sys.executable, "-c", "import time;time.sleep(4)"], timeout=0.1)

    def test_endpoint_security(self):
        for url in ("http://public.example/v1/snapshot", "https://a:b@host/v1/snapshot",
                    "https://host/v1/snapshot?token=abc", "https://host/wrong"):
            with self.assertRaises(ValueError): validate_endpoint(url)
        validate_endpoint("https://host/v1/snapshot")
        validate_endpoint("http://127.0.0.1:8788/v1/snapshot")

    def test_direct_server_auth_cache_and_unsupported_write(self):
        store = SnapshotStore()
        token = "synthetic-test-read-token-1234567890123456"
        server = make_server("127.0.0.1", 0, store, token)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        url = f"http://127.0.0.1:{server.server_port}/v1/snapshot"
        try:
            with self.assertRaises(HTTPError) as error: urlopen(url)
            self.assertEqual(error.exception.code, 401)
            req = Request(url, headers={"Authorization": "Bearer " + token})
            with self.assertRaises(HTTPError) as error: urlopen(req)
            self.assertEqual(error.exception.code, 503)
            data = demo_snapshot(); store.set(data)
            with urlopen(req) as response:
                self.assertEqual(json.load(response), data)
            with self.assertRaises(HTTPError) as error: upload(data, url, "synthetic-write-token-1234567890123456")
            self.assertEqual(error.exception.code, 501)
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=2)


if __name__ == "__main__": unittest.main()
