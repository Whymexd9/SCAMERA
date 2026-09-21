#!/system/bin/sh
set -eu
umask 077

if [ "$(id -u)" != 0 ]; then
    echo 'Запусти через su -c.' >&2
    exit 1
fi
for tool in timeout logcat tar sha256sum; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Нет команды: $tool" >&2
        exit 1
    fi
done

work=$(mktemp -d /data/local/tmp/scamera-vcf2-connection.XXXXXXXX)
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM
mkdir -p "$work/files" "$work/processes"
: > "$work/missing.txt"
: > "$work/SHA256SUMS.txt"
getprop ro.build.fingerprint > "$work/build-fingerprint.txt"
getenforce > "$work/selinux.txt"
date > "$work/start-time.txt"
printf '%s\n' "${BOOTCLASSPATH-}" > "$work/bootclasspath.txt"

copy_file() {
    source=$1
    if [ ! -f "$source" ]; then
        printf '%s\n' "$source" >> "$work/missing.txt"
        return
    fi
    relative=${source#/}
    mkdir -p "$work/files/${relative%/*}"
    if cp "$source" "$work/files/$relative" 2>> "$work/copy-errors.txt"; then
        (cd "$work" && sha256sum "files/$relative") >> "$work/SHA256SUMS.txt"
    else
        printf '%s\n' "$source" >> "$work/missing.txt"
    fi
}

# Examine service/client UID handling together before moving callbacks.
for source in \
    /system/framework/vivo-camera-framework.jar \
    /system/bin/vivocameraserver \
    /system/etc/init/vivocameraserver.rc \
    /system/lib64/libvivocameraservice.so \
    /system/lib64/libcameraservice.so \
    /system/lib64/libvivo.pap.vivocameraprovider.client.so \
    /system/lib64/vendor.vivo.hardware.vivocameraprovider-V1-ndk.so
do
    copy_file "$source"
done

process_snapshot() {
    phase=$1
    for name in com.hdrplus.ultra vivocameraserver cameraserver vendor.qti.camera.provider-service_64; do
        for process_id in $(pidof "$name" 2>/dev/null || true); do
            case "$process_id" in ''|*[!0-9]*) continue ;; esac
            for entry in status maps; do
                cat "/proc/$process_id/$entry" > "$work/processes/$phase-$name-$process_id-$entry.txt" \
                    2>> "$work/process-read-errors.txt" || true
            done
        done
    done
}

process_snapshot before
echo 'В течение 45 секунд открой SCAMERA → Фото → VCF2 и нажми спуск. Затем вернись сюда.'
log_status=0
timeout 45 logcat -b main -b system -b crash -v threadtime -T 1 \
    -f "$work/logcat.txt" -r 4096 -n 2 '*:V' \
    2> "$work/logcat-errors.txt" || log_status=$?
printf '%s\n' "$log_status" > "$work/logcat-exit-status.txt"
process_snapshot after
copy_file /sdcard/Download/SCAMERA/SCAMERA-debug.log
date > "$work/end-time.txt"

out="/sdcard/Download/SCAMERA-VCF2-connection-$(date +%Y%m%d-%H%M%S)-$$.tar.gz"
mkdir -p /sdcard/Download
tar -czf "$out" -C "$work" .
printf 'Пришли архив: %s\n' "$out"
if [ "$log_status" != 0 ] && [ "$log_status" != 124 ]; then
    echo 'Сбор logcat завершился с ошибкой; её описание включено в архив.'
fi
