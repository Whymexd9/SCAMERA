#!/system/bin/sh
# Passive snapshot only: never calls CameraService, enables monitoring, starts
# camera capture, changes properties, clears logs, or attaches to a process.
set -eu
if [ "$(id -u)" != 0 ]; then echo 'Запустите через su (root).'; exit 1; fi
umask 077
log_parent=/sdcard/Download/SCAMERA
mkdir -p "$log_parent"
log_dir=$(mktemp -d "$log_parent/vivo-stock-passive-XXXXXXXX")
getprop ro.build.fingerprint > "$log_dir/build.txt"
getprop persist.sys.log.ctrl > "$log_dir/log-control.txt"
getprop persist.log.ratelimit > "$log_dir/log-ratelimit.txt"
# Both tag forms occur in VLogWrapper. Filtering happens in logcat: no broad
# application-history dump is saved. -d reads existing entries and exits.
if /system/bin/logcat -b main -b system -d -v threadtime -t 20000 -s 'VCameraSdk:V' '_V_VCameraSdk:V' '*:S' > "$log_dir/stock-log.txt" 2> "$log_dir/read-error.txt"; then
    echo 0 > "$log_dir/read-status.txt"
else
    echo "$?" > "$log_dir/read-status.txt"
fi
# VLogWrapper may route output to its own files when rate limiting is on.
# Read bounded tails and retain only the stock capture command, not other logs.
mkdir "$log_dir/camera-file-excerpts"
log_index=0
for log_file in /data/bbklog/camap_log/camapp_*.txt; do
    [ -f "$log_file" ] || continue
    log_index=$((log_index + 1))
    [ "$log_index" -le 10 ] || break
    tail -c 1048576 "$log_file" | grep -F '[SuperNightCaptureCommand]' > "$log_dir/camera-file-excerpts/${log_file##*/}" || true
done
log_name=${log_dir##*/}
tar -cf "$log_parent/$log_name.tar" -C "$log_parent" "$log_name"
echo "Пришлите архив: $log_parent/$log_name.tar"
if ! grep -Eq 'aecFrameInfo:|aecFrameControl:|captureFrameControl:|forwardFrameCount:' "$log_dir/stock-log.txt" "$log_dir"/camera-file-excerpts/*.txt 2>/dev/null; then
    echo 'Нужные записи в текущем журнале не найдены. Настройки логирования не менялись; съёмку повторять не нужно.'
fi
