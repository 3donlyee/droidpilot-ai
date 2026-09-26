#!/usr/bin/env python3
"""
DroidPilot AI — fake device E2E test.

Simulates a DroidPilot Android device + a user chatting, entirely via HTTP:
  1. registers a device
  2. confirms pairing with the returned PIN (as the Web UI would)
  3. sends a chat message
  4. long-polls for commands and answers them with canned results
  5. prints the turn steps and the final AI response

Usage:
  python3 scripts/fake_device.py https://droidpilot-ai.<subdomain>.workers.dev ["افتح TikTok وانتقل للفيديو التالي"]

No credentials or secrets are required — pairing is self-contained.
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8787").rstrip("/")
PROMPT = sys.argv[2] if len(sys.argv) > 2 else "افتح TikTok وانتقل للفيديو التالي"
MODEL = (sys.argv[3] if len(sys.argv) > 3 else None)  # optional model_id override


def req(method, path, body=None, headers=None, timeout=45):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("content-type", "application/json")
    r.add_header("user-agent", "DroidPilot-FakeDevice/0.1")
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            if resp.status == 204:
                return None
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode())
        except Exception:
            payload = {}
        return {"_http": e.code, **payload}


UA = {"user-agent": "DroidPilot-FakeDevice/0.1"}


def canned(tool, args):
    """Canned device answers — stands in for the real Android agent."""
    if tool == "open_app":
        return {"package": "com.zhiliaoapp.musically", "resolved": "exact"}
    if tool == "get_current_package":
        return {"package": "com.zhiliaoapp.musically"}
    if tool == "get_screen_nodes":
        return {
            "node_count": 41,
            "elements": [
                {"id": "node_001", "text": "Like", "clickable": True, "enabled": True,
                 "bounds": {"left": 400, "top": 1000, "right": 600, "bottom": 1100}},
                {"id": "node_002", "text": "Comment", "clickable": True, "enabled": True,
                 "bounds": {"left": 610, "top": 1000, "right": 700, "bottom": 1100}},
            ],
        }
    if tool == "get_device_info":
        return {"model": "OPPO Reno5 (fake)", "android_version": "13"}
    if tool == "run_shell":
        return {"exit_code": 0, "stdout": "ok"}
    return {"ok": True}


def main():
    print(f"==> target: {BASE}")
    r = req("POST", "/api/device/register", {
        "device_name": "Fake OPPO Reno5",
        "model": "OPPO Reno5 (CPH2159)",
        "android_version": "13",
        "app_version": "0.1.0-test",
    })
    if not r or not r.get("ok"):
        print("register FAILED:", r)
        sys.exit(1)
    dev, sec, pin = r["device_id"], r["device_secret"], r["pairing_code"]
    H = {"x-device-id": dev, "x-device-secret": sec}
    print(f"    registered {dev}  PIN={pin}")

    r = req("POST", "/api/pair/confirm", {"device_id": dev, "pairing_code": pin})
    print("==> pairing:", "OK" if r and r.get("ok") else f"FAILED {r}")
    if not r or not r.get("ok"):
        sys.exit(1)

    r = req("POST", "/api/chat", {"message": PROMPT, "device_id": dev, "model_id": MODEL})
    if not r or not r.get("ok"):
        print("chat FAILED:", r)
        sys.exit(1)
    turn = r["turn_id"]
    print(f"==> chat: turn={turn}")

    steps_seen = 0
    idle = 0
    for _ in range(120):
        cmd = req("GET", "/api/device/poll?wait=5", headers=H)
        if cmd and "type" in cmd and cmd.get("type") == "command":
            idle = 0
            print(f"    ANDROID ← {cmd['tool']}({json.dumps(cmd.get('arguments', {}), ensure_ascii=False)[:80]})")
            data = canned(cmd["tool"], cmd.get("arguments") or {})
            rr = req("POST", "/api/device/result",
                     {"id": cmd["id"], "success": True, "data": data}, headers=H)
            if not rr or not rr.get("ok"):
                print("    result post FAILED:", rr)
        else:
            t = req("GET", f"/api/turn/{turn}")
            if t and t.get("state") in ("done", "error"):
                break
            idle += 1
            if idle > 30:
                break
        time.sleep(0.4)

    t = req("GET", f"/api/turn/{turn}")
    print("\n==> TURN RESULT ==", t.get("state") if t else "?")
    for s in (t or {}).get("steps", []):
        line = f"  [{time.strftime('%H:%M:%S', time.gmtime(s['ts'] / 1000))}] {s['type'].upper()}"
        if s.get("tool"):
            line += f" {s['tool']}"
        if s.get("summary"):
            line += f" -> {s['summary']}"
        if s.get("text"):
            line += f" : {s['text'][:140]}"
        print(line)
    print("\nFINAL:", (t or {}).get("final_response") or (t or {}).get("error"))

    logs = req("GET", "/api/logs")
    print("\n==> last audit entries:")
    for e in (logs or {}).get("logs", [])[-8:]:
        print("   ", json.dumps(e, ensure_ascii=False))


if __name__ == "__main__":
    main()
