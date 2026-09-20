#!/system/bin/sh
# Read live stock-camera metadata through Android's built-in tag monitor.
# No APK installation, firmware changes, persistent properties, or uploads.
# Interface source: AOSP CameraService.cpp, handleWatchCommand/startWatchingTags.
# https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/CameraService.cpp
set -eu
if [ "$(id -u)" != 0 ]; then
    echo 'Запустите этот файл через su (root).'
    exit 1
fi
umask 077
trace_parent=/sdcard/Download/SCAMERA
mkdir -p "$trace_parent"
trace_dir=$(mktemp -d "$trace_parent/vivo-stock-schedule-XXXXXXXX")
watch_started=0
cleanup() {
    if [ "$watch_started" = 1 ]; then
        cmd media.camera watch stop >>"$trace_dir/monitor-stop.txt" 2>&1 || true
        watch_started=0
    fi
}
trap cleanup 0
trap 'exit 130' INT TERM
cmd media.camera help >"$trace_dir/camera-help.txt" 2>&1 || true
if ! grep -q 'watch' "$trace_dir/camera-help.txt"; then
    echo "Штатный монитор недоступен. Пришлите файл: $trace_dir/camera-help.txt"
    exit 2
fi
getprop ro.build.fingerprint >"$trace_dir/build.txt"
dumpsys media.camera >"$trace_dir/camera-before.txt" 2>&1 || true
for trace_lib in /vendor/lib64/libvivo_nice_cre.so /vendor/lib64/libvivo_nicetce.so /vendor/lib64/libvivo_nicetone.so; do
    if [ -f "$trace_lib" ]; then sha256sum "$trace_lib" >>"$trace_dir/library-hashes.txt"; fi
done
trace_tags=android.sensor.timestamp,android.sensor.exposureTime,android.sensor.sensitivity,android.control.aeState,android.control.enableZsl,android.control.captureIntent,vivo.parameter.VivoAlgoAECFrameControl,vivo.parameter.VivoAlgoCaptureFrameControl,vivo.parameter.VivoMotionAdaptiveAECInfo,vivo.control.RequestLeftInThisSnapshot,vivo.control.currentModeEx,vivo.control.hdr_gain,vivo.control.hdr_shutter,vivo.control.sensor_gain,vivo.feedback.RealGain,vivo.feedback.AdrcGain,vivo.feedback.ISPDigitalGain
watch_started=1
if ! cmd media.camera watch start -m "$trace_tags" -c com.android.camera >"$trace_dir/monitor-start.txt" 2>&1; then
    cleanup
    echo "Монитор не запустился. Пришлите файл: $trace_dir/monitor-start.txt"
    exit 3
fi
echo 'Откройте стоковую камеру Vivo, режим Фото, основную камеру 35 мм.'
echo 'За следующие 90 секунд сделайте 3 снимка: обычный свет; яркое окно с тёмной комнатой; тот же сюжет с движущимся предметом.'
echo 'Сборщик сохраняет метаданные камеры, без фотографий и общей истории logcat.'
trace_step=0
while [ "$trace_step" -lt 45 ]; do
    date '+%s' >>"$trace_dir/watch.txt"
    cmd media.camera watch dump >>"$trace_dir/watch.txt" 2>&1 || true
    trace_step=$((trace_step + 1))
    sleep 2
done
cleanup
dumpsys media.camera >"$trace_dir/camera-after.txt" 2>&1 || true
trace_name=${trace_dir##*/}
tar -cf "$trace_parent/$trace_name.tar" -C "$trace_parent" "$trace_name"
echo "Готово. Пришлите архив: $trace_parent/$trace_name.tar"
if ! grep -q 'VivoAlgoAECFrameControl' "$trace_dir/watch.txt"; then
    echo 'AEC-тег в выводе не найден: архив сохранён для проверки возможностей этой прошивки.'
fi
