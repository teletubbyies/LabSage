#!/usr/bin/env python3
"""Record a real SSE chat trace from the local SuperBizAgent backend.

Captures per-chunk wall-clock arrival times of `message` events (and total
trace duration) into a JSON fixture that SsePersistenceBenchmarkTest replays
with the production cadence policy.

Usage:
  python3 scripts/record_sse_trace.py "你的问题" out.json
"""
import json
import sys
import time
import urllib.request
import uuid

BASE = "http://localhost:6872"


def post_json(path, payload, token=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def record_stream(question, token):
    payload = {
        "conversationId": str(uuid.uuid4()),
        "question": question,
        "clientMessageId": str(uuid.uuid4()),
        "clientRequestId": str(uuid.uuid4()),
        "clientCreatedAt": int(time.time() * 1000),
    }
    req = urllib.request.Request(
        BASE + "/api/chat_stream",
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "Authorization": f"Bearer {token}",
        },
        method="POST",
    )
    chunks, traces, started, done_at = [], [], None, None
    with urllib.request.urlopen(req, timeout=180) as resp:
        event = None
        t0 = None
        for raw in resp:
            line = raw.decode("utf-8").rstrip("\n")
            if line.startswith("event:"):
                event = line.split(":", 1)[1].strip()
            elif line.startswith("data:"):
                if t0 is None:
                    t0 = time.monotonic()
                data = line.split(":", 1)[1].strip()
                now = time.monotonic()
                if event == "message":
                    chunks.append({"tMs": round((now - t0) * 1000, 2), "text": data})
                elif event in ("trace", "trace_summary"):
                    traces.append({"tMs": round((now - t0) * 1000, 2), "kind": event})
                elif event == "done":
                    done_at = round((now - t0) * 1000, 2)
    return chunks, traces, done_at


def main():
    question, out_path = sys.argv[1], sys.argv[2]
    user = f"trace-{uuid.uuid4().hex[:10]}"
    session = post_json("/api/auth/register", {"username": user, "password": "trace-password-1"})
    token = session["data"]["tokens"]["accessToken"]
    print(f"registered {user}")
    chunks, traces, done_at = record_stream(question, token)
    fixture = {
        "source": "real",
        "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "question": question,
        "chunkCount": len(chunks),
        "totalChars": sum(len(c["text"]) for c in chunks),
        "doneAtMs": done_at,
        "traceEvents": len(traces),
        "chunks": chunks,
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(fixture, f, ensure_ascii=False, indent=1)
    gaps = [chunks[i + 1]["tMs"] - chunks[i]["tMs"] for i in range(len(chunks) - 1)]
    print(
        f"chunks={len(chunks)} chars={fixture['totalChars']} doneAt={done_at}ms "
        f"gapMin={min(gaps):.1f} gapMedian={sorted(gaps)[len(gaps)//2]:.1f} gapMax={max(gaps):.1f}"
    )


if __name__ == "__main__":
    main()
