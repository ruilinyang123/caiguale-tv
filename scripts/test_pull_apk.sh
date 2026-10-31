#!/usr/bin/env bash
# Regression harness for scripts/pull_apk.sh.
#
# pull_apk.sh runs on the APK mirror host every 5 minutes and, on a version
# change, rebuilds /www/wwwroot/apkmirror with `rsync --delete`. A regression in
# its pre-flight gate would silently empty the live mirror, so the gate-before-
# delete logic is worth pinning down with a test that needs no network and no
# root: curl / git / rsync / flock are stubbed and everything runs in a temp dir.
#
# Usage:  bash scripts/test_pull_apk.sh
# Exit:   0 when all scenarios pass, 1 otherwise.
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/pull_apk.sh"
[ -f "$SCRIPT" ] || { echo "cannot find $SCRIPT"; exit 1; }

pass=0; fail=0
RC=""; RESULT_MARKER=""; RESULT_APK=""; RESULT_EPG=""; RESULT_CLONED=""; RESULT_ERR=""
check() {
  if [ "$2" = "$3" ]; then echo "  PASS  $1 -> $3"; pass=$((pass+1));
  else echo "  FAIL  $1  expected=$2 actual=$3"; fail=$((fail+1)); fi
}

mk_stubs() {
  W=$(mktemp -d)
  # On Windows/Git-Bash mktemp can hand back a `C:\...` path; the colon corrupts
  # PATH when we prepend $W/bin, so normalise to POSIX first (cygpath is a no-op
  # fallback on Linux hosts where the path is already POSIX).
  W=$(cygpath -u "$W" 2>/dev/null || echo "$W")
  mkdir -p "$W/bin" "$W/mirror/apk" "$W/dest/epg" "$W/logs"

  printf '#!/bin/sh\nexit 0\n' > "$W/bin/flock"; chmod +x "$W/bin/flock"

  # curl stub: with -o write to file, without -o (version probe) write stdout.
  cat > "$W/bin/curl" <<'STUB'
#!/bin/sh
out=""; url=""
while [ $# -gt 0 ]; do
  case "$1" in -o) out="$2"; shift 2;; -*) shift;; *) url="$1"; shift;; esac
done
emit() { if [ -n "$out" ]; then cat > "$out"; else cat; fi; }
case "$url" in
  *pl.xml.gz)
      [ "${STUB_EPG_FAIL:-0}" = "1" ] && exit 22
      printf 'EPGDATA' | gzip -c | emit; exit 0;;
  *)
      printf '{"versionName": "%s", "apk": "mobile-arm64_v8a.apk", "size": 1}\n' "$STUB_WANT" | emit; exit 0;;
esac
STUB
  chmod +x "$W/bin/curl"

  # git stub: only `clone` matters — copy the pre-seeded mirror tree to dest.
  cat > "$W/bin/git" <<'STUB'
#!/bin/sh
[ "$1" = clone ] || exit 0
dest=""
for a in "$@"; do [ "${a#-}" = "$a" ] && dest="$a"; done
[ -n "$dest" ] || exit 1
echo cloned > "$STUB_TRACE_CLONE"
cp -a "$STUB_MIRROR" "$dest"
exit 0
STUB
  chmod +x "$W/bin/git"

  # rsync stub: emulate the subset pull_apk.sh relies on, honouring --exclude=/epg
  # and --exclude=/index.html plus --delete semantics for everything else.
  cat > "$W/bin/rsync" <<'STUB'
#!/bin/sh
src=""; dst=""
for a in "$@"; do case "$a" in -*) ;; *) if [ -z "$src" ]; then src="$a"; else dst="$a"; fi;; esac; done
src="${src%/}"; dst="${dst%/}"
echo "rsync $src -> $dst" >> "$STUB_TRACE_RSYNC"
for e in "$src"/*; do
  [ -e "$e" ] || continue
  b=$(basename "$e")
  [ "$b" = "index.html" ] && continue
  [ "$b" = "epg" ] && continue
  cp -a "$e" "$dst/"
done
for f in "$dst"/*; do
  [ -e "$f" ] || continue
  b=$(basename "$f")
  [ "$b" = "index.html" ] && continue
  [ "$b" = "epg" ] && continue
  [ -e "$src/$b" ] || rm -rf "$f"
done
exit 0
STUB
  chmod +x "$W/bin/rsync"
}

# Seed a mirror tree. $1=dir $2=version $3=ok|missing|badsize $4=has_epg(1/0)
mk_mirror() {
  d=$1; ver=$2; mode=$3; epg=$4
  mkdir -p "$d/apk"
  printf 'APK-A-CONTENT' > "$d/apk/mobile-arm64_v8a.apk"
  printf 'APK-B-CONTENT' > "$d/apk/leanback-arm64_v8a.apk"
  sa=$(stat -c%s "$d/apk/mobile-arm64_v8a.apk")
  sb=$(stat -c%s "$d/apk/leanback-arm64_v8a.apk")
  [ "$mode" = "badsize" ] && sa=999999
  printf '{"versionName": "%s", "apk": "mobile-arm64_v8a.apk", "size": %s}\n' "$ver" "$sa"  > "$d/apk/mobile-arm64_v8a.json"
  printf '{"versionName": "%s", "apk": "leanback-arm64_v8a.apk", "size": %s}\n' "$ver" "$sb" > "$d/apk/leanback-arm64_v8a.json"
  printf '<html>index</html>' > "$d/apk/index.html"
  [ "$mode" = "missing" ] && rm -f "$d/apk/leanback-arm64_v8a.apk"
  if [ "$epg" = "1" ]; then mkdir -p "$d/apk/epg"; printf 'EPGDATA' | gzip -c > "$d/apk/epg/pl.xml.gz"; fi
}

# $1=name $2=want_ver $3=mode $4=has_epg $5=dest_ver [$6=epg_fail]
run_case() {
  name=$1; want=$2; mode=$3; epg=$4; destver=$5; epg_fail=${6:-0}
  mk_stubs
  mk_mirror "$W/mirror" "$want" "$mode" "$epg"
  printf '{"versionName": "%s", "apk": "mobile-arm64_v8a.apk", "size": 11}\n' "$destver" > "$W/dest/mobile-arm64_v8a.json"
  printf 'OLD-APK-A' > "$W/dest/mobile-arm64_v8a.apk"
  printf 'LIVE-EPG' | gzip -c > "$W/dest/epg/pl.xml.gz"

  (
    PATH="$W/bin:$PATH" \
    STUB_MIRROR="$W/mirror" STUB_WANT="$want" STUB_EPG_FAIL="$epg_fail" \
    STUB_TRACE_CLONE="$W/trace.clone" STUB_TRACE_RSYNC="$W/trace.rsync" \
    DEST="$W/dest" LOG="$W/logs/log" HEARTBEAT="$W/hb" LOCK="$W/lock" \
    STAGE_ROOT="$W/stage" MIN_FREE_KB=0 PURGE="$W/nonexistent" \
    bash "$SCRIPT"
  ) > "$W/out" 2>&1
  RC=$?
  rm -rf "$W/stage" 2>/dev/null

  RESULT_MARKER=$(sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$W/dest/mobile-arm64_v8a.json" 2>/dev/null | head -1)
  RESULT_APK=$(cat "$W/dest/mobile-arm64_v8a.apk" 2>/dev/null || echo "(none)")
  RESULT_EPG=$(gzip -dc "$W/dest/epg/pl.xml.gz" 2>/dev/null || echo "(none)")
  RESULT_CLONED=$([ -f "$W/trace.clone" ] && echo yes || echo no)
  RESULT_ERR=$(grep -c 'failed verification' "$W/out" 2>/dev/null | head -1)
  echo "--- $name (rc=$RC) ---"
  sed 's/^/    /' "$W/out" | head -5
  rm -rf "$W"
}

echo "===== 1. complete mirror -> syncs ====="
run_case "complete" 9.9.9 ok 1 1.0.0
check "version updated to 9.9.9" "9.9.9" "$RESULT_MARKER"
check "apk replaced with new content" "APK-A-CONTENT" "$RESULT_APK"
check "epg refreshed from raw" "EPGDATA" "$RESULT_EPG"
check "exit 0" "0" "$RC"

echo "===== 2. mirror missing an APK -> abort, live untouched ====="
run_case "missing-apk" 9.9.9 missing 1 1.0.0
check "live version unchanged" "1.0.0" "$RESULT_MARKER"
check "live apk unchanged" "OLD-APK-A" "$RESULT_APK"
check "exit 1" "1" "$RC"
check "gate failure reported" "1" "$([ "${RESULT_ERR:-0}" -ge 1 ] && echo 1 || echo 0)"

echo "===== 3. mirror APK size disagrees with manifest -> abort ====="
run_case "badsize" 9.9.9 badsize 1 1.0.0
check "live version unchanged" "1.0.0" "$RESULT_MARKER"
check "exit 1" "1" "$RC"

echo "===== 4. mirror omits epg/ AND upstream epg fails -> live epg survives ====="
run_case "no-epg" 9.9.9 ok 0 1.0.0 1
check "version still updates" "9.9.9" "$RESULT_MARKER"
check "live epg preserved" "LIVE-EPG" "$RESULT_EPG"

echo "===== 5. version unchanged -> early exit, no clone ====="
run_case "same-version" 1.0.0 ok 1 1.0.0
check "no clone attempted" "no" "$RESULT_CLONED"
check "exit 0" "0" "$RC"

echo
echo "result: $pass passed / $fail failed"
exit $((fail > 0))
