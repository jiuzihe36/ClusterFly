#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ClusterMirror 工程推到 GitHub（keychain token + Contents API）。

用法:
  python3 push_clustermirror.py            # 推送全部
  python3 push_clustermirror.py --dry      # 只看要推什么
"""
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
    cmd = ["curl", "-sS", "--max-time", "60", "-X", method,
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

def head_sha():
    r = api("GET", "/repos/%s/git/ref/heads/main" % REPO)
    return r.get("object", {}).get("sha") if isinstance(r, dict) else None

def put_file(rel_path, message=None):
    """新建/更新单个文件，返回 commit sha 前 8 位。"""
    local = os.path.join(ROOT, rel_path)
    if not os.path.exists(local):
        return "跳过（本地不存在）"
    with open(local, "rb") as f:
        content = base64.b64encode(f.read()).decode()
    payload = {"message": message or ("update " + rel_path), "content": content}
    cur = api("GET", "/repos/%s/contents/%s" % (REPO, rel_path))
    if isinstance(cur, dict) and cur.get("sha"):
        payload["sha"] = cur["sha"]
        payload["message"] = message or ("update " + rel_path)
    else:
        payload["message"] = message or ("add " + rel_path)
    res = api("PUT", "/repos/%s/contents/%s" % (REPO, rel_path), payload)
    if "commit" in res:
        return res["commit"]["sha"][:8]
    return "ERR: " + str(res)[:200]

def delete_file(rel_path):
    cur = api("GET", "/repos/%s/contents/%s" % (REPO, rel_path))
    if not (isinstance(cur, dict) and cur.get("sha")):
        return "跳过（远端不存在）"
    res = api("DELETE", "/repos/%s/contents/%s" % (REPO, rel_path),
              {"message": "remove " + rel_path, "sha": cur["sha"]})
    if "commit" in res:
        return res["commit"]["sha"][:8]
    return "ERR: " + str(res)[:200]

# --- 要推送的文件（按依赖顺序，settings/build 先走）---
FILES = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradle/wrapper/gradle-wrapper.properties",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/hermes/clustermirror/ClusterMirrorService.java",
    "app/src/main/java/com/hermes/clustermirror/MainActivity.java",
    ".github/workflows/build.yml",
    "push_clustermirror.py",
]

# --- 要删掉的文件 ---
# 1) 旧 Kotlin 探测工程（编译失败，已废弃）
# 2) v4.0 那套 com.mirror.amap Kotlin 源（会抢 launcher，污染 APK 包名）
# 3) 遗留的 Groovy 构建脚本（和 .kts 冲突，AGP 会挑一个）
REMOVE = [
    "app/src/main/java/com/amap/mirror/probe/DisplayProbeActivity.kt",
    "app/src/main/java/com/amap/mirror/mock/MockNaviSource.kt",
    "app/src/main/java/com/mirror/amap/AmapBroadcastReceiver.kt",
    "app/src/main/java/com/mirror/amap/MainActivity.kt",
    "app/src/main/java/com/mirror/amap/MirrorService.kt",
    "app/src/main/java/com/mirror/amap/NaviDetector.kt",
    "app/src/main/java/com/mirror/amap/NaviRenderer.kt",
    "build.gradle",
    "settings.gradle",
    "app/build.gradle",
    "app/proguard-rules.pro",
    # 空文件（0 字节），历史残留
    "AmapMirror-v1.0.apk",
]

if __name__ == "__main__":
    if "--dry" in sys.argv:
        for f in FILES:
            print("PUSH  ", f, os.path.exists(os.path.join(ROOT, f)))
        for f in REMOVE:
            print("REMOVE", f)
        sys.exit(0)

    print("=== 删除旧 Kotlin 探测源 ===")
    for f in REMOVE:
        print("%-64s %s" % (f, delete_file(f)))

    print("=== 推送文件 ===")
    for f in FILES:
        print("%-64s %s" % (f, put_file(f)))

    print("=== main HEAD ===")
    print(head_sha())
