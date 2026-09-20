#!/system/bin/sh
# v2: isolate Binder stdin/stdout/stderr with pipes; retain real command status.
# No firmware changes, persistent properties, SELinux changes, or uploads.
# Donor libcameraservice.so: shellCommand 0x1d46a8; handleWatchCommand 0x20ccf4;
# Camera3Device::dump 0x30ff44 supports -m <tags> and -m off.
set -eu
if [ "$(id -u)" != 0 ]; then
    echo 'Запустите этот файл через su (root).'
    exit 1
fi
umask 077
trace_parent=/sdcard/Download/SCAMERA
mkdir -p "$trace_parent"
trace_dir=$(mktemp -d "$trace_parent/vivo-stock-schedule-v2-XXXXXXXX")
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
        dumpsys) collect_command "$trace_dir/monitor-stop.txt" dumpsys -t 10 media.camera -m off || true ;;
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
printf 'SCAMERA stock schedule collector v2\n' > "$trace_dir/collector.txt"
if ! command -v timeout >/dev/null 2>&1; then
    echo 'Нет утилиты timeout; сбор не запущен.' | tee -a "$trace_dir/collector.txt"
    exit 2
fi
getprop ro.build.fingerprint > "$trace_dir/build.txt"
id > "$trace_dir/identity.txt"
id -Z >> "$trace_dir/identity.txt" 2>&1 || true
getenforce >> "$trace_dir/identity.txt" 2>&1 || true
collect_command "$trace_dir/camera-help.txt" cmd media.camera help || true
collect_command "$trace_dir/camera-before.txt" dumpsys -t 10 media.camera || true
for trace_lib in /system/lib64/libcameraservice.so /vendor/lib64/libvivo_nice_cre.so /vendor/lib64/libvivo_nicetce.so /vendor/lib64/libvivo_nicetone.so; do
    if [ -f "$trace_lib" ]; then sha256sum "$trace_lib" >> "$trace_dir/library-hashes.txt"; fi
done
trace_tags=android.sensor.timestamp,android.sensor.exposureTime,android.sensor.sensitivity,android.control.aeState,android.control.enableZsl,android.control.captureIntent,android.colorCorrection.gains,android.colorCorrection.transform,vivo.parameter.VivoAlgoAECFrameControl,vivo.parameter.VivoAlgoCaptureFrameControl,vivo.parameter.VivoMotionAdaptiveAECInfo,vivo.control.RequestLeftInThisSnapshot,vivo.control.currentModeEx,vivo.control.hdr_gain,vivo.control.hdr_shutter,vivo.control.sensor_gain,vivo.feedback.RealGain,vivo.feedback.AdrcGain,vivo.feedback.ISPDigitalGain

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

echo 'Откройте стоковую камеру Vivo: Фото, основная камера 35 мм. Другие приложения камеры закройте.'
echo 'Сейчас 15 секунд на открытие камеры, затем 90 секунд на три снимка: обычный свет; яркое окно с тёмной комнатой; движущийся предмет.'
echo 'Сохраняются диагностика и метаданные камеры; фотографии и общий logcat не собираются.'
sleep 15
if [ "$trace_mode" = none ]; then
    # Legacy entry point is in the supplied Vivo binary. It applies to clients
    # already connected, so the stock camera must be open before this call.
    trace_mode=dumpsys
    if collect_command "$trace_dir/monitor-start-dumpsys.txt" dumpsys -t 10 media.camera -m "$trace_tags" && ! command_failed "$trace_dir/monitor-start-dumpsys.txt" && grep -q 'Tag monitoring enabled' "$trace_dir/monitor-start-dumpsys.txt"; then
        echo 'Монитор включён через dumpsys.' | tee -a "$trace_dir/collector.txt"
    else
        echo 'Запуск монитора не подтверждён. Архив диагностики будет сохранён; фотографировать пока не нужно.' | tee -a "$trace_dir/collector.txt"
        collect_command "$trace_dir/camera-services.txt" dumpsys -l || true
        exit 3
    fi
fi
trace_step=0
while [ "$trace_step" -lt 45 ]; do
    date '+%s' >> "$trace_dir/watch.txt"
    if [ "$trace_mode" = watch ]; then
        collect_command "$trace_dir/watch-last.txt" cmd media.camera watch dump || true
    else
        collect_command "$trace_dir/watch-last.txt" dumpsys -t 10 media.camera || true
    fi
    cat "$trace_dir/watch-last.txt" >> "$trace_dir/watch.txt"
    if command_failed "$trace_dir/watch-last.txt"; then
        echo 'Чтение монитора завершилось ошибкой. Сохраняю диагностику.' | tee -a "$trace_dir/collector.txt"
        exit 4
    fi
    trace_step=$((trace_step + 1))
    sleep 2
done
collect_command "$trace_dir/camera-after.txt" dumpsys -t 10 media.camera || true
if ! grep -q 'VivoAlgoAECFrameControl' "$trace_dir/watch.txt"; then
    echo 'AEC-тег не найден; наличие нужных значений будет проверено по архиву.' | tee -a "$trace_dir/collector.txt"
fi
