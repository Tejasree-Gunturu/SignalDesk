#!/usr/bin/env python3
"""Small authenticated read baseline; reports rate-limit/error counts rather than hiding them."""
import argparse
import concurrent.futures
import json
import os
import time
import urllib.request
import urllib.error

p = argparse.ArgumentParser()
p.add_argument("--requests", type=int, default=50)
p.add_argument("--concurrency", type=int, default=5)
args = p.parse_args()
if args.requests < 1 or args.concurrency < 1:
    p.error("counts must be positive")
base = os.getenv("SIGNALDESK_URL", "http://localhost:8080").rstrip("/")
token = os.getenv("SIGNALDESK_ADMIN_TOKEN", "dev-acme-admin-change-me")


def run(_):
    start = time.perf_counter()
    code = 0
    try:
        req = urllib.request.Request(base + "/api/v1/incidents?size=20", headers={"Authorization": "Bearer " + token})
        with urllib.request.urlopen(req, timeout=15) as response:
            response.read()
            code = response.status
    except urllib.error.HTTPError as e:
        code = e.code
    except OSError:
        code = 0
    return code, (time.perf_counter() - start) * 1000


start = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
    results = list(pool.map(run, range(args.requests)))
elapsed = time.perf_counter() - start
ok = sorted(ms for code, ms in results if code == 200)
codes = {str(code): sum(1 for c, _ in results if c == code) for code, _ in results}
print(json.dumps({"requests": args.requests, "concurrency": args.concurrency, "elapsed_seconds": elapsed,
                  "status_counts": codes, "success_requests_per_second": len(ok) / elapsed,
                  "p50_ms": ok[len(ok) // 2] if ok else None,
                  "p95_ms": ok[min(len(ok) - 1, int(len(ok) * .95))] if ok else None}, indent=2))
