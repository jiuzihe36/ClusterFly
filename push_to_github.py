#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 AmapMirror 工程推到 GitHub（用 keychain 里的 token，走 Contents API）。"""
import base64
import json
import os
import subprocess
import sys

REPO = "jiuzihe36/AmapMirror"
ROOT = os.path.expanduser("~/AmapMirror")


def token():
    out = subprocess.run(
        ["security", "find-internet-password", "-s", "github.com", "-w"],
        capture_output=True, text=True)
    t = out.stdout.strip()
    if not t:
        sys.exit("读取 GitHub token 失败（keychain）")
    return t


def api(method, url, data=None):
    cmd = ["curl", "-sS", "--max-time", "40", "-X", method,
           "-H", "Authorization: Bearer " + token(),
           "-H", "Accept: application/vnd.github+json",
           "-H", "Content-Type: application/json",
           "https://api.github.com" + url]
    if data is not None:
        tmp = "/tmp/gh_payload.json"
        with open(tmp, "wb") as f:
            f.write(json.dumps(data).encode())
        cmd += ["--data-binary", "@" + tmp]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    try:
        return json.loads(out)
    except Exception:
        return {"_raw": out[:300]}


def put_file(rel_path):
    local = os.path.join(ROOT, rel_path)
    if not os.path.exists(local):
        print("  跳过（不存在）:", rel_path)
        return None
    with open(local, "rb") as f:
        content = base64.b64encode(f.read()).decode()
    payload = {"message": "add " + rel_path, "content": content}
    # 已存在则带上 sha
    cur = api("GET", "/repos/%s/contents/%s" % (REPO, rel_path))
    if isinstance(cur, dict) and cur.get("sha"):
        payload["sha"] = cur["sha"]
    res = api("PUT", "/repos/%s/contents/%s" % (REPO, rel_path), payload)
    if "commit" in res:
        return res["commit"]["sha"][:8]
    return "ERR: " + str(res)[:160]


FILES = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/amap/mirror/probe/DisplayProbeActivity.kt",
    "app/src/main/java/com/amap/mirror/mock/MockNaviSource.kt",
    ".github/workflows/build.yml",
]

if __name__ == "__main__":
    for f in FILES:
        print("%-62s %s" % (f, put_file(f)))
