#!/system/bin/sh
# v3: watch only; no full camera dumps or repeated polling during capture.
# No firmware changes, persistent properties, SELinux changes, or uploads.
# The v2 dumpsys fallback is withdrawn after a device camera-freeze report.
set -eu
if [ "$(id -u)" != 0 ]; then
    echo 'Запустите этот файл через su (root).'
    exit 1
fi
umask 077
trace_parent=/sdcard/Download/SCAMERA
mkdir -p "$trace_parent"
trace_dir=$(mktemp -d "$trace_parent/vivo-stock-schedule-v3-XXXXXXXX")
trace_mode=none
trace_finished=0

# cmd passes all three FDs to the service. Do not pass Download files or the
# terminal's FDs: a rejected FD transfer can fail the whole Binder transaction.
# The status file is opened only AFTER the command returns, outside Binder.
collect_command() {
    trace_command_output=$1
    shift
    printf '' | {
        if timeout 15 "$@"; then trace_rc=0; else trace_rc=$?; fi
        printf '%s\n' "$trace_rc" > "$trace_dir/last-command.status"
    } 2>&1 | cat > "$trace_command_output"
    return "$(cat "$trace_dir/last-command.status")"
}
command_failed() {
    grep -Eqi '^(cmd: .*([Ff]ail|[Ee]rror)|Permission [Dd]enial|Permission denied|Can.t find service|Unknown command|Error:)' "$1"
}
finish() {
    [ "$trace_finished" = 0 ] || return 0
    trace_finished=1
    case "$trace_mode" in
        watch) collect_command "$trace_dir/monitor-stop.txt" cmd media.camera watch stop || true ;;
    esac
    trace_mode=none
    trace_name=${trace_dir##*/}
    if tar -cf "$trace_parent/$trace_name.tar" -C "$trace_parent" "$trace_name"; then
        echo "Пришлите архив: $trace_parent/$trace_name.tar"
    else
        echo "Архив не создан. Файлы остались в: $trace_dir"
    fi
}
trap finish 0
trap 'exit 130' INT TERM
printf 'SCAMERA stock schedule collector v3\n' > "$trace_dir/collector.txt"
if ! command -v timeout >/dev/null 2>&1; then
    echo 'Нет утилиты timeout; сбор не запущен.' | tee -a "$trace_dir/collector.txt"
    exit 2
fi
getprop ro.build.fingerprint > "$trace_dir/build.txt"
id > "$trace_dir/identity.txt"
id -Z >> "$trace_dir/identity.txt" 2>&1 || true
getenforce >> "$trace_dir/identity.txt" 2>&1 || true
collect_command "$trace_dir/camera-help.txt" cmd media.camera help || true
for trace_lib in /system/lib64/libcameraservice.so /vendor/lib64/libvivo_nice_cre.so /vendor/lib64/libvivo_nicetce.so /vendor/lib64/libvivo_nicetone.so; do
    if [ -f "$trace_lib" ]; then sha256sum "$trace_lib" >> "$trace_dir/library-hashes.txt"; fi
done
trace_tags=android.sensor.exposureTime,android.sensor.sensitivity,vivo.parameter.VivoAlgoAECFrameControl,vivo.parameter.VivoAlgoCaptureFrameControl,vivo.parameter.VivoMotionAdaptiveAECInfo,vivo.control.RequestLeftInThisSnapshot

if grep -q 'watch' "$trace_dir/camera-help.txt" && ! command_failed "$trace_dir/camera-help.txt"; then
    # Mark before starting so a timeout or interrupt still triggers cleanup.
    trace_mode=watch
    if collect_command "$trace_dir/monitor-start.txt" cmd media.camera watch start -m "$trace_tags" -c com.android.camera && ! command_failed "$trace_dir/monitor-start.txt"; then
        echo 'Монитор watch запущен через pipe.' | tee -a "$trace_dir/collector.txt"
    else
        collect_command "$trace_dir/watch-stop-after-error.txt" cmd media.camera watch stop || true
        trace_mode=none
    fi
fi

if [ "$trace_mode" != watch ]; then
    echo 'Монитор watch недоступен. Сбор остановлен; резервный dumpsys отключён после сообщения о зависании камеры.' | tee -a "$trace_dir/collector.txt"
    exit 3
fi
echo 'Откройте стоковую камеру Vivo: Фото, основная камера 35 мм.'
echo 'За следующие 30 секунд сделайте один снимок яркого окна с тёмной комнатой.'
echo 'Опрос камеры во время съёмки отключён. При зависании остановите сбор через Ctrl+C.'
# No service calls during this interval. Read the tag buffer exactly once.
sleep 30
if ! collect_command "$trace_dir/watch.txt" cmd media.camera watch dump || command_failed "$trace_dir/watch.txt"; then
    echo 'Не удалось прочитать монитор. Сохраняю диагностику.' | tee -a "$trace_dir/collector.txt"
    exit 4
fi
if ! grep -q 'VivoAlgoAECFrameControl' "$trace_dir/watch.txt"; then
    echo 'AEC-тег не найден; наличие нужных значений будет проверено по архиву.' | tee -a "$trace_dir/collector.txt"
fi
