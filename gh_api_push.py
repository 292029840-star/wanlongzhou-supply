#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gh_api_push.py — 当 git over HTTPS 被网络/代理封禁(github.com 502)时，
用 GitHub Git Database REST API(api.github.com 通常仍通)把本地改动推到远程。

适用场景:
  - 本环境 github.com 的 CONNECT 隧道被代理稳定 502 / 直连超时，
    但 api.github.com 可达。git push 走不通，改用本脚本。

做了什么:
  1. 取远程 <branch> HEAD 的 tree(递归)
  2. 用 `git hash-object` 算每个本地跟踪文件 blob SHA，与远程 tree 比对，
     只挑「内容变化/新增」的文件
  3. 为变化文件 POST /git/blobs(base64)
  4. POST /git/trees(base_tree + 变化 entry) -> new_tree
  5. POST /git/commits(parents=[远程 HEAD]) -> new_commit
  6. PATCH /git/refs/heads/<branch> 更新分支
  7. 若给 --tag/--auto-tag: POST /git/refs 建标签(触发 on:push:tags 的 CI)

用法:
  export GH_TOKEN=ghp_xxx
  python gh_api_push.py                       # 只更新 main(推差异)
  python gh_api_push.py --tag v1.2            # 更新 main + 建 v1.2 标签触发 CI
  python gh_api_push.py --auto-tag            # HEAD 若有精确 tag 则建之
  python gh_api_push.py --dry-run             # 只打印将推送哪些文件,不改远程
  python gh_api_push.py --owner O --repo R --branch main

注意:
  - 仅处理 新增/修改，不自动删除远程多余文件(可加 --prune 扩展)
  - token 从 --token 或环境变量 GH_TOKEN 读取(不写死在脚本里)
  - 代理:默认读 HTTPS_PROXY 环境变量;读不到则回退到 127.0.0.1:56625
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.request

API = "https://api.github.com"
DEFAULT_PROXY = "http://127.0.0.1:56625"


def sh(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError("命令失败 %s -> %s" % (" ".join(cmd), r.stderr.strip()))
    return r.stdout


def build_opener(proxy):
    if proxy:
        return urllib.request.build_opener(urllib.request.ProxyHandler(
            {"https": proxy, "http": proxy}))
    return urllib.request.build_opener()


def api(opener, token, method, path, data=None):
    url = API + path
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "gh-api-push")
    try:
        with opener.open(req, timeout=60) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.stderr.write("HTTPError %s: %s\n" % (e.code, e.read().decode()[:800]))
        raise


def get_remote_tree(opener, token, owner, repo, branch):
    ref = api(opener, token, "GET", "/repos/%s/%s/git/refs/heads/%s" % (owner, repo, branch))
    base_sha = ref["object"]["sha"]
    commit = api(opener, token, "GET", "/repos/%s/%s/git/commits/%s" % (owner, repo, base_sha))
    base_tree = commit["tree"]["sha"]
    # 递归拉整棵 tree
    tree = api(opener, token, "GET", "/repos/%s/%s/git/trees/%s?recursive=1" % (owner, repo, base_tree))
    remote = {t["path"]: t["sha"] for t in tree.get("tree", [])}
    return base_sha, base_tree, remote


def local_changes(remote):
    # -z: NUL 分隔且不转义(避免中文文件名被加引号导致打不开)
    raw = sh(["git", "ls-files", "-z"])
    files = [x for x in raw.split("\0") if x]
    changed = []
    for f in files:
        if not f:
            continue
        local_sha = sh(["git", "hash-object", f]).strip()
        if remote.get(f) != local_sha:
            changed.append(f)
    return changed


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--token", default=os.environ.get("GH_TOKEN"))
    p.add_argument("--owner", default="292029840-star")
    p.add_argument("--repo", default="wanlongzhou-supply")
    p.add_argument("--branch", default="main")
    p.add_argument("--tag", default=None, help="建标签并触发 CI, 如 v1.2")
    p.add_argument("--auto-tag", action="store_true", help="若 HEAD 有精确 tag 则建之")
    p.add_argument("--message", default=None)
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--proxy", default=None)
    args = p.parse_args()

    if not args.token:
        sys.exit("缺少 token: 用 --token 或环境变量 GH_TOKEN")
    proxy = args.proxy or os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or DEFAULT_PROXY
    opener = build_opener(proxy)

    base_sha, base_tree, remote = get_remote_tree(opener, args.token, args.owner, args.repo, args.branch)
    print("[base] %s tree=%s 远程文件数=%d" % (base_sha[:10], base_tree[:10], len(remote)))

    changed = local_changes(remote)
    if not changed:
        print("无差异, 远程 %s 已与本地一致。" % args.branch)
        return
    print("[变更] 将推送 %d 个文件:" % len(changed))
    for f in changed:
        print("   -", f)

    if args.dry_run:
        print("[dry-run] 未改动远程。")
        return

    entries = []
    for f in changed:
        with open(f, "rb") as fh:
            raw = fh.read()
        b64 = base64.b64encode(raw).decode()
        blob = api(opener, args.token, "POST", "/repos/%s/%s/git/blobs" % (args.owner, args.repo),
                   {"content": b64, "encoding": "base64"})
        entries.append({"path": f, "mode": "100644", "type": "blob", "sha": blob["sha"]})

    new_tree = api(opener, args.token, "POST", "/repos/%s/%s/git/trees" % (args.owner, args.repo),
                   {"base_tree": base_tree, "tree": entries})
    msg = args.message or ("chore: 经 API 同步 %d 个文件到 %s" % (len(changed), args.branch))
    new_commit = api(opener, args.token, "POST", "/repos/%s/%s/git/commits" % (args.owner, args.repo),
                     {"message": msg, "tree": new_tree["sha"], "parents": [base_sha]})
    print("[commit] %s" % new_commit["sha"])

    api(opener, args.token, "PATCH", "/repos/%s/%s/git/refs/heads/%s" % (args.owner, args.repo, args.branch),
        {"sha": new_commit["sha"]})
    print("[%s] -> %s" % (args.branch, new_commit["sha"][:10]))

    tag = args.tag
    if args.auto_tag:
        try:
            tag = sh(["git", "describe", "--tags", "--exact-match", "HEAD"]).strip()
        except Exception:
            tag = None
    if tag:
        api(opener, args.token, "POST", "/repos/%s/%s/git/refs" % (args.owner, args.repo),
            {"ref": "refs/tags/" + tag, "sha": new_commit["sha"]})
        print("[tag] %s 已建 -> 触发 CI" % tag)

    print("ALL_DONE", new_commit["sha"])


if __name__ == "__main__":
    main()
