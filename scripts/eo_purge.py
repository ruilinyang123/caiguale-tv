#!/usr/bin/env python3
"""Purge Tencent Cloud EdgeOne (TEO) cache for the APK mirror.

Used by the Baota pull script (/root/pull_apk.sh) after new APK files land in
/www/wwwroot/apkmirror/, so a fresh release stops being served from stale edge
cache. Stdlib only (no tencentcloud-sdk-python needed).

Credentials come from the environment (never hard-code them):
  TEO_SECRET_ID / TEO_SECRET_KEY   Tencent Cloud API keys (CAM)
  TEO_ZONE_ID                      EdgeOne site id, e.g. "zone-xxxxxxxx"

Usage:
  python3 eo_purge.py --urls "https://pan.imotao.com/file/apk/index.html" ...
  python3 eo_purge.py --prefix "https://pan.imotao.com/file/apk/"   # 目录刷新
  python3 eo_purge.py --tag apk-mirror                              # Cache-Tag（企业版）
  python3 eo_purge.py --urls ... --dry-run

Notes baked in from the EdgeOne docs (2026-10):
  * purge_url   直接删除缓存，生效 5-50 分钟，单次 1-5000 条
  * purge_prefix 默认「标记过期」：节点会带 If-None-Match/If-Modified-Since
    回源校验，源站回 304 就继续用旧缓存 —— 要真正删掉必须 --method delete
  * purge_cache_tag 仅企业版套餐支持，生效最快（5-10 分钟）
  * 若某资源的缓存 TTL 小于 5 分钟，刷新比等 TTL 过期还慢，不要刷
"""

import argparse
import hashlib
import hmac
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

HOST = "teo.tencentcloudapi.com"
SERVICE = "teo"
VERSION = "2022-09-01"
ACTION = "CreatePurgeTask"
ALGORITHM = "TC3-HMAC-SHA256"


def _sha256_hex(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _hmac_sha256(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()


def build_request(payload: dict, secret_id: str, secret_key: str, timestamp: int | None = None) -> urllib.request.Request:
    """Build a signed TC3-HMAC-SHA256 request for the TEO CreatePurgeTask API."""
    body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    timestamp = timestamp or int(datetime.now(timezone.utc).timestamp())
    date = datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%d")

    content_type = "application/json; charset=utf-8"
    # Signed header values must be lowercased in the canonical form.
    signed_headers = "content-type;host;x-tc-action"
    canonical_headers = (
        f"content-type:{content_type}\n"
        f"host:{HOST}\n"
        f"x-tc-action:{ACTION.lower()}\n"
    )
    canonical_request = "\n".join([
        "POST",
        "/",
        "",
        canonical_headers,
        signed_headers,
        _sha256_hex(body.encode("utf-8")),
    ])
    credential_scope = f"{date}/{SERVICE}/tc3_request"
    string_to_sign = "\n".join([
        ALGORITHM,
        str(timestamp),
        credential_scope,
        _sha256_hex(canonical_request.encode("utf-8")),
    ])

    secret_date = _hmac_sha256(("TC3" + secret_key).encode("utf-8"), date)
    secret_service = _hmac_sha256(secret_date, SERVICE)
    secret_signing = _hmac_sha256(secret_service, "tc3_request")
    signature = hmac.new(secret_signing, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()

    authorization = (
        f"{ALGORITHM} Credential={secret_id}/{credential_scope}, "
        f"SignedHeaders={signed_headers}, Signature={signature}"
    )
    request = urllib.request.Request(
        f"https://{HOST}/",
        data=body.encode("utf-8"),
        method="POST",
        headers={
            "Authorization": authorization,
            "Content-Type": content_type,
            "Host": HOST,
            "X-TC-Action": ACTION,
            "X-TC-Timestamp": str(timestamp),
            "X-TC-Version": VERSION,
        },
    )
    return request


def purge(payload: dict) -> tuple[int, dict]:
    secret_id = os.environ.get("TEO_SECRET_ID", "")
    secret_key = os.environ.get("TEO_SECRET_KEY", "")
    if not secret_id or not secret_key:
        raise SystemExit("TEO_SECRET_ID / TEO_SECRET_KEY 未设置")
    request = build_request(payload, secret_id, secret_key)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8") or "{}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Purge EdgeOne cache for the APK mirror")
    parser.add_argument("--urls", nargs="*", default=[], help="要刷新的完整 URL（含协议）")
    parser.add_argument("--prefix", action="append", default=[], help="目录刷新，如 https://pan.imotao.com/file/apk/")
    parser.add_argument("--tag", action="append", default=[], help="Cache-Tag 刷新（仅企业版）")
    parser.add_argument("--method", choices=["invalidate", "delete"], default="delete",
                        help="目录刷新方式：delete=直接删除，invalidate=仅刷新有变更的（默认 delete）")
    parser.add_argument("--dry-run", action="store_true", help="只打印请求体，不调用 API")
    args = parser.parse_args()

    zone_id = os.environ.get("TEO_ZONE_ID", "")
    if not zone_id:
        raise SystemExit("TEO_ZONE_ID 未设置（EdgeOne 控制台站点概览可见）")

    tasks = []
    if args.urls:
        tasks.append({"ZoneId": zone_id, "Type": "purge_url", "Targets": args.urls})
    if args.prefix:
        tasks.append({"ZoneId": zone_id, "Type": "purge_prefix", "Method": args.method, "Targets": args.prefix})
    if args.tag:
        tasks.append({"ZoneId": zone_id, "Type": "purge_cache_tag", "Targets": args.tag})
    if not tasks:
        parser.error("至少需要 --urls / --prefix / --tag 之一")

    for payload in tasks:
        if args.dry_run:
            print("[dry-run]", json.dumps(payload, ensure_ascii=False))
            continue
        status, body = purge(payload)
        response = body.get("Response", body)
        job_id = response.get("JobId")
        failed = response.get("FailedList") or []
        # TC3 APIs answer HTTP 200 even for auth/param failures, carrying a
        # Response.Error object instead — check it explicitly.
        error = response.get("Error") or {}
        if status == 200 and not error and job_id and not failed:
            print(f"[ok] {payload['Type']} -> JobId={job_id}")
            continue
        reason = error.get("Code") or (f"HTTP {status}" if status != 200 else "no JobId")
        detail = error.get("Message") or json.dumps(body, ensure_ascii=False)
        print(f"[fail] {payload['Type']} {reason}: {detail}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
