#!/bin/bash
# P28 device check: replay a kept burst on the phone with the worker of this tree and a set of hybrid_tuning.txt lines, pull the
# merged RGB and the log, measure the lateral CA left in it (measure_rgb_ca.py). Runs adb; nothing here is run by the CI.
#
#   device_replay.sh <label> <burst on the device> [tuning lines...]
#   env: SERIAL (adb serial, default fb27034c = OPPO Find X7 Ultra), WORKER (local worker binary, default
#        app/build/generated/vivoNeuralAssets/vivo-neural/arm64-v8a/vivo-neural-worker), OUT (local dir, default build/rawca-replays),
#        APP_TUNING ("|"-separated lines written before the extra lines: the app's own tuning of the shot, from its log)
# Build the worker first (Windows, NDK 27.2):
#   local-tools/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe --target=aarch64-linux-android26 \
#     -std=c++17 -O2 -Wall -Wextra -pthread -fPIE -pie -static-libstdc++ -Wl,-z,max-page-size=16384 \
#     app/src/main/cpp/vivo-neural-worker.cpp -ldl -lEGL -lGLESv3 -o <WORKER>
# Examples (the owner's handheld Quad burst and the X7 Ultra ultrawide burst of P19):
#   tools/rawca/device_replay.sh hand_off   /data/local/tmp/hand.nch rawCa 0
#   tools/rawca/device_replay.sh hand_nop19 /data/local/tmp/hand.nch rawCa 0 caCorrect 0
#   tools/rawca/device_replay.sh hand_base  /data/local/tmp/hand.nch rawCa 1
#   tools/rawca/device_replay.sh hand_frames /data/local/tmp/hand.nch rawCa 2
#   adb push research/ca19/uw.nch /data/local/tmp/uw.nch   # 680 MB, once
#   tools/rawca/device_replay.sh uw_frames  /data/local/tmp/uw.nch rawCa 2 rawCaPasses 2
set -e
export MSYS_NO_PATHCONV=1
SERIAL=${SERIAL:-fb27034c}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKER=${WORKER:-$ROOT/app/build/generated/vivoNeuralAssets/vivo-neural/arm64-v8a/vivo-neural-worker}
OUT=${OUT:-$ROOT/build/rawca-replays}
L=$1; B=$2; shift 2
mkdir -p "$OUT"
A="adb -s $SERIAL"
# the app's lib dir changes with every install: link its libraries (bundled CRE, libc++) into the job dir
$A shell 'D=$(dirname $(pm path org.codeaurora.snapcam | head -1 | cut -d: -f2))/lib/arm64; mkdir -p /data/local/tmp/job; cd /data/local/tmp/job && rm -f *.so && for f in $D/*.so; do ln -s $f .; done'
$A push "$WORKER" /data/local/tmp/worker >/dev/null
$A shell chmod 755 /data/local/tmp/worker
{
    if [ -n "$APP_TUNING" ]; then IFS='|' read -ra LINES <<< "$APP_TUNING"; printf '%s\n' "${LINES[@]}"; fi
    while [ $# -gt 1 ]; do echo "$1 $2"; shift 2; done
} > "$OUT/$L.tuning.txt"
$A push "$OUT/$L.tuning.txt" /data/local/tmp/job/hybrid_tuning.txt >/dev/null
$A shell "cd /data/local/tmp && LD_LIBRARY_PATH=/data/local/tmp/job SCAM_HYBRID=1 ./worker --nice-capture /data/local/tmp/job $B /data/local/tmp/$L.f32 > /data/local/tmp/$L.log 2>&1; echo rc=\$?"
$A pull /data/local/tmp/$L.log "$OUT/$L.log" >/dev/null
$A pull /data/local/tmp/$L.f32 "$OUT/$L.f32" >/dev/null
$A shell rm -f /data/local/tmp/$L.f32
grep -E "RAW CA|STAGES|MOSAIC: total|TUNING FILE|rror|failed" "$OUT/$L.log" | cut -c1-400 || true
md5sum "$OUT/$L.f32"
python "$ROOT/tools/rawca/measure_rgb_ca.py" "$OUT/$L.f32"
