#!/usr/bin/env bash
# 從電腦遙控手機量測（Git Bash）。會播掃頻測試音：執行前一定要先跟使用者確認。
# 用法：tools/remote_measure.sh <手機序號> <收音方式> <次數> <倒數下車 true/false> <存到 measurements/ 下哪個資料夾> [recalibrate]
# 例：  tools/remote_measure.sh 988ad044314c41465730 UNPROCESSED 1 true car_redmi
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
SERIAL=$1 SOURCE=$2 RUNS=$3 LEAVE=$4 OUT=$5 RECAL=${6:-}
PKG=tw.marco.caraudio.measure
R=/sdcard/Android/data/$PKG/files/results
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
a() { "$ADB" -s "$SERIAL" "$@"; }

a shell pm grant $PKG android.permission.RECORD_AUDIO
a shell svc power stayon usb            # 插著 USB 時螢幕不關（錄音 App 必須在前景）
trap 'a shell svc power stayon false >/dev/null 2>&1 || true' EXIT
a shell rm -f /sdcard/Android/data/$PKG/files/remote_rejected.txt
a shell input keyevent KEYCODE_WAKEUP
before=$(a shell ls -t $R 2>/dev/null | head -1 | tr -d '\r' || true)
a shell am start -n $PKG/.MainActivity -a $PKG.REMOTE_MEASURE \
  --es source "$SOURCE" --ei runs "$RUNS" --ez leaveCar "$LEAVE" --ez recalibrate "$([ "$RECAL" = recalibrate ] && echo true || echo false)"

# 等這批的 summary.txt 出現（倒數 15 秒＋校準＋每次約 10 秒，最多等 4 分鐘）
for _ in $(seq 1 80); do
  sleep 3
  # 手機沒執行這個指令（上一批還在量、沒有麥克風權限）會留下原因
  if why=$(a shell cat /sdcard/Android/data/$PKG/files/remote_rejected.txt 2>/dev/null); then
    echo "手機沒有執行：$why" >&2
    exit 1
  fi
  d=$(a shell ls -t $R 2>/dev/null | head -1 | tr -d '\r' || true)
  if [ -n "$d" ] && [ "$d" != "$before" ] && a shell ls "$R/$d/summary.txt" >/dev/null 2>&1; then
    mkdir -p "$ROOT/measurements/$OUT"
    a pull "$R/$d" "$ROOT/measurements/$OUT/" >/dev/null
    echo "完成：measurements/$OUT/$d"
    sed -n '1,12p' "$ROOT/measurements/$OUT/$d/summary.txt"
    exit 0
  fi
done
echo "逾時：4 分鐘內沒有看到結果，請看手機畫面" >&2
exit 1
