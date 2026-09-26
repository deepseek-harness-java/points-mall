#!/usr/bin/env python3
"""points-mall E2E：通过业务应用 SSE 代理调用 DSH Agent，验证工具全链路。"""
import json, subprocess, sys

AGENT = "points-copilot"
URL = "http://127.0.0.1:18090/api/assistant/stream"

CASES = [
    ("T1 商品列表", "积分商城有哪些商品可以兑换？简洁回答", ["外卖红包", "蓝牙耳机"]),
    ("T2 会员查询", "帮我查一下会员 u01 的积分余额，简洁回答", ["u01", "积分"]),
    ("T3 积分兑换", "我是会员 u01，帮我用积分兑换一张 10 元话费直充，直接兑换告诉我兑换码", ["PT10", "话费"]),
    ("T4 兑换记录", "我是会员 u01，帮我查一下我的兑换记录，简洁回答", ["兑换", "话费"]),
    ("T5 兑换行情", "积分商城今天兑换情况怎么样？哪些商品兑得最多？简洁回答", ["兑换", "热"]),
]

def ask(message, timeout=170):
    payload = json.dumps({"message": message}, ensure_ascii=False)
    try:
        out = subprocess.run(
            ["curl", "-s", "--noproxy", "*", "-N", "-X", "POST", URL,
             "-H", "Content-Type: application/json", "-d", payload,
             "--max-time", str(timeout)],
            capture_output=True, text=True, timeout=timeout + 10).stdout
    except Exception as e:
        return "", f"curl 异常: {e}"
    text = []
    ev = ""
    for line in out.splitlines():
        line = line.rstrip("\r")
        if line.startswith("event:"):
            ev = line[6:].strip()
        elif line.startswith("data:"):
            s = line[5:].strip()
            if not s or s == "[DONE]" or ev != "chunk":
                continue
            try:
                j = json.loads(s)
                c = j.get("content", "")
                if c:
                    text.append(c)
            except Exception:
                pass
            ev = ""
    return "".join(text), out

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    cases = CASES if not only else [c for c in CASES if c[0].startswith(only)]
    passed, failed = 0, []
    for name, q, keys in cases:
        reply, raw = ask(q)
        ok = all(k in reply for k in keys)
        print(f"[{'PASS' if ok else 'FAIL'}] {name}\n  Q: {q}\n  A: {reply[:200]}")
        if ok:
            passed += 1
        else:
            failed.append(name)
            if not reply:
                print(f"  raw 首行: {raw.splitlines()[:3] if raw else '(空)'}")
    print(f"\n===== points-mall E2E: {passed}/{len(cases)} PASS =====")

if __name__ == "__main__":
    main()
