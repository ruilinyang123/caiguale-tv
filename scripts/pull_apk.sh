#!/bin/bash
# Runs on the APK mirror host (Baota), deployed as /root/pull_apk.sh and driven
# by cron every 5 minutes. This copy is the versioned source of record.
#
# Pull the latest APKs + manifests from the CNB mirror (rebuilt on every release)
# into the web-served directory /www/wwwroot/apkmirror.
#
# Two phases, because a full clone carries ~940 MB of APKs and takes ~77 s:
#   1. probe the published manifest through CNB raw (a few hundred bytes)
#   2. only when the published version differs from the local one: clone + rsync
# On any failure the previous files stay in place (keep-alive).
#
# Two rules keep that promise honest:
#   * the staged tree is checked against its own manifests BEFORE rsync runs. The
#     only verification used to happen after the rsync, by which point a partial
#     mirror had already emptied the live directory via --delete.
#   * /epg is excluded from the mirror rsync: the programme guide is refreshed
#     straight from raw above and has its own lifecycle, so a mirror rebuild that
#     happens to omit it must not delete the one already being served.
#
# Every external path can be overridden from the environment so the script can be
# rehearsed against a scratch tree; the defaults are what production uses.
set -u

DEST=${DEST:-/www/wwwroot/apkmirror}
MARKER=${MARKER:-mobile-arm64_v8a.json}
RAW=${RAW:-https://cnb.cool/code_free/webtv/-/git/raw/main/apk}
EPG_URL=${EPG_URL:-https://cnb.cool/code_free/webtv/-/git/raw/epg/pl.xml.gz}
CLONE_URL=${CLONE_URL:-https://cnb.cool/code_free/webtv.git}
LOCK=${LOCK:-/var/lock/pull_apk.lock}
HEARTBEAT=${HEARTBEAT:-/root/.pull_apk_last}
LOG=${LOG:-/var/log/pull_apk.log}
PURGE=${PURGE:-/root/eo_purge.sh}
MIN_FREE_KB=${MIN_FREE_KB:-1500000}
STAGE_ROOT=${STAGE_ROOT:-/home}

stamp() { date "+%F %T"; }

# Never let a slow clone pile up behind the 5-minute schedule.
exec 9>"$LOCK"
if ! flock -n 9; then
  echo "$(stamp) another pull is still running, skipping" >&2
  exit 0
fi
touch "$HEARTBEAT"

# EPG data changes several times a day, independent of app releases: refresh it
# on every run, BEFORE the APK version gate. Sourced from the CNB mirror's
# dedicated `epg` branch (the epg-sync workflow pushes it there; the release job
# rebuilds `main` from scratch and never touches this branch), never from GitHub.
EPG_TMP=$(mktemp -d)
if curl -fsS --max-time 60 -o "$EPG_TMP/pl.xml.gz" "$EPG_URL" \
   && gzip -t "$EPG_TMP/pl.xml.gz"; then
  mkdir -p "$DEST/epg"
  if ! cmp -s "$EPG_TMP/pl.xml.gz" "$DEST/epg/pl.xml.gz"; then
    install -m 644 "$EPG_TMP/pl.xml.gz" "$DEST/epg/pl.xml.gz"
    echo "$(stamp) epg updated ($(stat -c%s "$DEST/epg/pl.xml.gz") bytes)" >> "$LOG"
  fi
else
  echo "$(stamp) epg fetch failed, keeping current data" >&2
fi
rm -rf "$EPG_TMP"

version_of() {
  sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$1" 2>/dev/null | head -1
}
json_str() { sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$1" 2>/dev/null | head -1; }
json_num() { sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p" "$1" 2>/dev/null | head -1; }

WANT=$(curl -fsS --max-time 25 "$RAW/$MARKER" 2>/dev/null \
       | sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
if [ -z "$WANT" ]; then
  echo "$(stamp) version probe failed (CNB unreachable?), keeping current files" >&2
  exit 1
fi

HAVE=""
[ -f "$DEST/$MARKER" ] && HAVE=$(version_of "$DEST/$MARKER")
if [ -n "$HAVE" ] && [ "$HAVE" = "$WANT" ]; then
  # Up to date: stay quiet, this runs every 5 minutes and the log stays small.
  exit 0
fi

TMP="$STAGE_ROOT/.apk_pull.$$"
rm -rf "$TMP"
mkdir -p "$STAGE_ROOT" 2>/dev/null || true
# The clone carries ~940 MB and the web dir lives on the small root filesystem
# (/ is 20G), so stage the clone on /home and refuse to start when space is
# short — a full disk mid-rsync would leave a half-synced mirror behind.
for check in "$STAGE_ROOT" /; do
  avail=$(df -Pk "$check" | awk 'NR==2 {print $4}')
  if [ -z "${avail:-}" ] || [ "$avail" -lt "$MIN_FREE_KB" ]; then
    echo "$(stamp) not enough free space on $check (${avail:-?}KB), keeping current files" >&2
    exit 1
  fi
done
if ! git clone --depth 1 -q "$CLONE_URL" "$TMP"; then
  echo "$(stamp) clone failed (want $WANT, have ${HAVE:-none}), keeping current files" >&2
  rm -rf "$TMP"
  exit 1
fi
if [ ! -d "$TMP/apk" ]; then
  echo "$(stamp) mirror has no apk dir, keeping current files" >&2
  rm -rf "$TMP"
  exit 1
fi

# --- pre-flight gate -------------------------------------------------------
# Every manifest must resolve to a staged APK of exactly the declared size. If
# anything is off we bail out here, while the live directory is still intact.
STAGE="$TMP/apk"
manifests=0
broken=0
for manifest in "$STAGE"/*.json; do
  [ -f "$manifest" ] || continue
  manifests=$((manifests + 1))
  name=$(basename "$manifest")
  apk=$(json_str "$manifest" apk)
  size=$(json_num "$manifest" size)
  if [ -z "$apk" ] || [ -z "$size" ]; then
    echo "$(stamp) staged $name declares no apk/size" >&2
    broken=$((broken + 1)); continue
  fi
  if [ ! -f "$STAGE/$apk" ]; then
    echo "$(stamp) staged tree is missing $apk (declared by $name)" >&2
    broken=$((broken + 1)); continue
  fi
  actual=$(stat -c%s "$STAGE/$apk" 2>/dev/null || echo 0)
  if [ "$actual" != "$size" ]; then
    echo "$(stamp) staged $apk size mismatch: expected $size got $actual" >&2
    broken=$((broken + 1))
  fi
done
if [ "$manifests" -eq 0 ]; then
  echo "$(stamp) staged tree has no manifests, refusing to sync" >&2
  broken=$((broken + 1))
fi
if [ "$broken" -ne 0 ]; then
  echo "$(stamp) staged tree failed verification ($broken problem(s)), keeping current files" >&2
  rm -rf "$TMP"
  exit 1
fi

mkdir -p "$DEST"
rsync -a --delete --chmod=D755,F644 --exclude=/index.html --exclude=/epg "$STAGE/" "$DEST/"
[ -f "$STAGE/index.html" ] && cp "$STAGE/index.html" "$DEST/index.html"
rm -rf "$TMP"

# Belt and braces: re-check what actually landed. Catches a truncated rsync
# (e.g. full disk) which would otherwise serve a broken download.
fail=0
for manifest in "$DEST"/*.json; do
  [ -f "$manifest" ] || continue
  apk=$(json_str "$manifest" apk)
  size=$(json_num "$manifest" size)
  [ -n "$apk" ] && [ -n "$size" ] || continue
  actual=$(stat -c%s "$DEST/$apk" 2>/dev/null || echo 0)
  if [ "$actual" != "$size" ]; then
    echo "$(stamp) size mismatch: $apk expected $size got $actual" >&2
    fail=1
  fi
done

# Optional: purge the EdgeOne edge cache right after a release lands. Only runs
# when the purge helper exists, so it stays a no-op until EO credentials are set.
if [ "$fail" -eq 0 ] && [ -x "$PURGE" ]; then
  "$PURGE" --prefix "https://pan.imotao.com/file/apk/" || echo "$(stamp) EdgeOne purge failed" >&2
fi

if [ "$fail" -eq 0 ]; then
  echo "$(stamp) synced ${HAVE:-none} -> $WANT, all APK sizes verified" >> "$LOG"
else
  echo "$(stamp) synced ${HAVE:-none} -> $WANT, SIZE VERIFICATION FAILED (see stderr)" >> "$LOG"
fi
exit $fail
