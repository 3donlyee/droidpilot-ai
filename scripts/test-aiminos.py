#!/usr/bin/env python3
"""Aiminos v2 E2E suite: memory protocols + agent routing + stop/resume.
Usage: python3 scripts/test-aiminos.py https://aiminos.droidpilot.workers.dev
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8787").rstrip("/")
PASS, FAIL = [], []


def req(method, path, body=None, headers=None, timeout=45):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("content-type", "application/json")
    r.add_header("user-agent", "AiminosE2E/0.2")  # default python UA is bot-blocked by Cloudflare
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            if resp.status == 204:
                return None
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return {"_http": e.code, **json.loads(e.read().decode())}
        except Exception:
            return {"_http": e.code}


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"  {'✅' if cond else '❌'} {name}" + (f" — {detail}" if detail else ""))


def canned(tool):
    if tool == "open_app":
        return {"package": "com.zhiliaoapp.musically", "resolved": "exact"}
    if tool == "get_current_package":
        return {"package": "com.zhiliaoapp.musically"}
    if tool == "get_screen_nodes":
        return {"node_count": 2, "elements": [
            {"id": "node_001", "text": "Comment", "clickable": True, "enabled": True,
             "bounds": {"left": 610, "top": 1000, "right": 700, "bottom": 1100}}]}
    return {"ok": True}


def device_loop(H, stop_after=None, max_cycles=90):
    """Answer device commands; after `stop_after` results, POST /api/turn/stop."""
    answered = 0
    for _ in range(max_cycles):
        cmd = req("GET", "/api/device/poll?wait=4", headers=H)
        if cmd and cmd.get("type") == "command":
            print(f"      device ← {cmd['tool']}")
            req("POST", "/api/device/result",
                {"id": cmd["id"], "success": True, "data": canned(cmd["tool"])}, headers=H)
            answered += 1
            if stop_after is not None and answered >= stop_after:
                return answered
        time.sleep(0.3)
    return answered


def wait_turn(turn_id, timeout=150):
    t0 = time.time()
    while time.time() - t0 < timeout:
        t = req("GET", f"/api/turn/{turn_id}")
        if t and t.get("state") in ("done", "error", "stopped"):
            return t
        time.sleep(2.5)
    return None


def main():
    print(f"==> target: {BASE}")

    # 0. team
    ags = req("GET", "/api/agents")
    ids = [a["id"] for a in (ags or {}).get("agents", [])]
    check("team registry (5 agents)", set(ids) == {"general", "memorizer", "interactor", "observer", "navigator"}, str(ids))

    # register + pair
    r = req("POST", "/api/device/register", {
        "device_name": "E2E Suite", "model": "OPPO Reno5 (CPH2159)",
        "android_version": "13", "app_version": "0.2.0-test"})
    if not r or not r.get("ok"):
        print("register FAILED:", r); sys.exit(1)
    dev, sec, pin = r["device_id"], r["device_secret"], r["pairing_code"]
    H = {"x-device-id": dev, "x-device-secret": sec}
    check("device id prefix AIM-", dev.startswith("AIM-"), dev)
    pr = req("POST", "/api/pair/confirm", {"device_id": dev, "pairing_code": pin})
    check("pairing", bool(pr and pr.get("ok")))
    print(f"  (device {dev})")

    # 1. MEMORY: server-side memory_save via memorizer routing
    r = req("POST", "/api/chat", {"device_id": dev, "message": "تذكر أن حسابي في تيك توك هو @salem123", "source": "api"})
    t1 = wait_turn(r["turn_id"]) if r and r.get("ok") else None
    check("memory turn completes", bool(t1 and t1.get("state") == "done"),
          (t1 or {}).get("final_response", "")[:80])
    used_mem = any(s.get("tool", "").startswith("memory_") for s in (t1 or {}).get("steps", []))
    check("memory_save tool fired server-side", used_mem)
    snap = req("GET", f"/api/memory/{dev}")
    facts = [f.get("v", "") for f in (snap or {}).get("facts", [])]
    check("durable fact stored (T3)", any("@salem123" in v for v in facts), str(facts)[:120])

    # 2. AGENT ROUTING + device tools (navigator)
    r = req("POST", "/api/chat", {"device_id": dev, "message": "افتح TikTok وانتقل للفيديو التالي", "source": "api"})
    routed = (r or {}).get("agent_id")
    check("router picked navigator", routed == "navigator", str(routed))
    answered = device_loop(H)
    t2 = wait_turn(r["turn_id"]) if r and r.get("ok") else None
    check("navigator turn done", bool(t2 and t2.get("state") == "done"),
          (t2 or {}).get("final_response", "")[:80])
    tools_used = [s.get("tool") for s in (t2 or {}).get("steps", []) if s.get("type") == "tool_call"]
    check("device tools executed", "open_app" in tools_used and "swipe_up" in tools_used, str(tools_used))

    # 3. STOP: start a long task, answer 1 command, then stop
    r = req("POST", "/api/chat", {"device_id": dev, "message": "افتح TikTok ثم تمرر ثلاث مرات ثم اقرأ الشاشة واشرح لي ماذا ترى بالتفصيل", "source": "api"})
    if r and r.get("ok"):
        device_loop(H, stop_after=1)
        st = req("POST", "/api/turn/stop", {"device_id": dev, "source": "api"})
        t3 = wait_turn(r["turn_id"])
        check("stop endpoint ok", bool(st and st.get("ok")))
        check("turn state = stopped", bool(t3 and t3.get("state") == "stopped"), str((t3 or {}).get("state")))
    else:
        check("stop test started", False, str(r))

    # 4. history (T2) recorded
    snap = req("GET", f"/api/memory/{dev}")
    hist = (snap or {}).get("history", [])
    check("session history (T2) recorded", len(hist) >= 2, f"{len(hist)} items")
    check("rolling summary present or pending", "summary" in (snap or {}))

    print(f"\n==> RESULT: {len(PASS)} passed, {len(FAIL)} failed")
    if FAIL:
        print("   failed:", ", ".join(FAIL)); sys.exit(1)
    print("AIMINOS_E2E_ALL_PASS")


if __name__ == "__main__":
    main()
