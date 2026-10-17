#!/usr/bin/env python3
"""Runs the public API -> Kafka -> notification flow. Python 3 standard library only."""
import json
import os
import time
import urllib.error
import urllib.request
import uuid

BASE = os.getenv("SIGNALDESK_URL", "http://localhost:8080").rstrip("/")
TOKEN = os.getenv("SIGNALDESK_ADMIN_TOKEN", "dev-acme-admin-change-me")


def request(method, path, body=None, extra=None):
    headers = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}
    headers.update(extra or {})
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


def main():
    body = {"title": "Smoke checkout failure", "serviceName": "checkout", "description": "Smoke test",
            "severity": "CRITICAL", "reporter": "maya"}
    headers = {"Idempotency-Key": "smoke-" + str(uuid.uuid4())}
    incident = request("POST", "/api/v1/incidents", body, headers)
    again = request("POST", "/api/v1/incidents", body, headers)
    assert incident["id"] == again["id"], "Create replay produced a different ID"
    path = "/api/v1/incidents/" + incident["id"]
    for action, expected in [("acknowledge", "ACKNOWLEDGED"), ("resolve", "RESOLVED"), ("close", "CLOSED")]:
        result = request("POST", path + "/" + action, {"actor": "maya", "note": "smoke"})
        assert result["status"] == expected
    assert len(request("GET", path + "/audit")) == 4
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        jobs = request("GET", path + "/notifications")
        if len(jobs) == 4 and all(job["status"] == "SENT" for job in jobs):
            print("PASS: create/replay/lifecycle/audit/Kafka/notifications; incident=" + incident["id"])
            return
        time.sleep(2)
    raise RuntimeError("Notifications did not reach SENT within 60s; check worker logs")


if __name__ == "__main__":
    main()
