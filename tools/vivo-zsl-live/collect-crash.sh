#!/system/bin/sh
set -u
umask 077
[ "$(id -u)" = 0 ] || { echo 'Запусти через su -c.'; exit 1; }
standalone=0
if [ "$#" = 0 ]; then
  standalone=1
  report=$(mktemp -d /data/local/tmp/scamera-crash.XXXXXXXX) || exit 1
else
  report=$1
  [ -d "$report" ] || exit 1
fi
{
  date -u
  echo 'Read-only crash collection; no injection, policy changes or service restart.'
  echo 'Historical logs may include older crashes; correlate timestamp and PID.'
} > "$report/crash-info.txt"
logcat -b crash -d -v threadtime -t 1500 > "$report/logcat-crash.txt" 2>&1
echo "LOGCAT_EXIT=$?" >> "$report/crash-info.txt"
mkdir -p "$report/tombstones" || exit 1
ls -lt /data/tombstones/tombstone_?? > "$report/tombstones-list.txt" 2>&1
ls -t /data/tombstones/tombstone_?? 2>/dev/null | head -n 8 | while IFS= read -r file; do
  if [ -f "$file" ] && grep -Fq 'vendor.qti.camera.provider-service_64' "$file"; then
    head -c 2097152 "$file" > "$report/tombstones/${file##*/}.txt"
  fi
done
if [ "$standalone" = 1 ]; then
  dest=/sdcard/Download/SCAMERA
  mkdir -p "$dest" || exit 1
  tar -czf "$dest/${report##*/}.tar.gz" -C "$report" . || exit 1
  echo "ГОТОВО: $dest/${report##*/}.tar.gz"
fi
