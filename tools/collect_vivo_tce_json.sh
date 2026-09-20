#!/system/bin/sh
# PD2454 / pinned libvivo_nicetce only. No CameraService monitor, ptrace,
# log-level changes, app termination, image dump or automatic shutter action.
# -1 is deliberate: this donor tests !=0 for JSON, but >0 for segmentation YUV.
# check_vivo_tce_dump_gates.py executes all four original conditional blocks.
set -eu
umask 077

if [ "$(id -u)" != 0 ]; then
    echo 'Запустите через su (root).'
    exit 1
fi
for tce_command in sha256sum timeout find tar getprop setprop; do
    command -v "$tce_command" >/dev/null || exit 1
done
tce_expected=9f5deac3bc68fc86fcf16b98f43c232a9642bc309c7d5d42c88d6a4b596b892d
tce_actual=$(sha256sum /vendor/lib64/libvivo_nicetce.so)
tce_actual=${tce_actual%% *}
if [ "$tce_actual" != "$tce_expected" ]; then
    echo 'Другая версия TCE. Настройки не менялись; нужен актуальный libvivo_nicetce.so.'
    exit 2
fi
tce_level=$(getprop vendor.vivo.vaf.dump.nicetce)
case "$tce_level" in
    ''|0) ;;
    *) echo 'Общий дамп TCE уже включён. Сбор отменён, настройки не менялись.'; exit 2 ;;
esac

# Optional output/search paths also permit host lifecycle tests with mock Android
# commands. They do not bypass donor identity or property guards.
tce_parent=${1:-/sdcard/Download/SCAMERA}
tce_root=${2:-/data/vendor/camera}
[ -d "$tce_root" ] || { echo "Нет каталога: $tce_root"; exit 2; }
mkdir -p "$tce_parent"
tce_lock="$tce_parent/.tce-json-collector-lock"
if ! mkdir "$tce_lock" 2>/dev/null; then
    echo 'Другой сборщик уже запущен или оставил блокировку. Повторный запуск отменён.'
    exit 2
fi
tce_previous=''
tce_changed=0
tce_restored=0
tce_dir=''
restore_tce_property() {
    [ "$tce_changed" = 1 ] || return 0
    [ "$tce_restored" = 0 ] || return 0
    if setprop vendor.vivo.vaf.dump.nice.portraitseg "$tce_previous" &&
       [ "$(getprop vendor.vivo.vaf.dump.nice.portraitseg)" = "$tce_previous" ]; then
        tce_restored=1
        [ -z "$tce_dir" ] || echo restored > "$tce_dir/property-restore.txt"
        return 0
    fi
    echo 'Не удалось восстановить параметр. Выполните restore-property.sh из каталога сбора.' >&2
    return 1
}
finish_tce_collector() {
    tce_exit=$?
    trap - EXIT HUP INT TERM
    if restore_tce_property; then
        rmdir "$tce_lock" 2>/dev/null || true
    else
        tce_exit=3
    fi
    exit "$tce_exit"
}
trap finish_tce_collector EXIT
trap 'exit 130' HUP INT TERM
tce_dir=$(mktemp -d "$tce_parent/vivo-tce-json-XXXXXXXX")
mkdir "$tce_dir/json"
tce_previous=$(getprop vendor.vivo.vaf.dump.nice.portraitseg)
# Only numeric/empty property values are valid for this donor. Restrict these
# before writing a quoted recovery command; no arbitrary property text is code.
case "$tce_previous" in
    ''|0|1|-1) ;;
    *) echo 'Неожиданное исходное значение portraitseg; настройки не менялись.'; exit 2 ;;
esac
printf '%s\n' '#!/system/bin/sh' \
    "setprop vendor.vivo.vaf.dump.nice.portraitseg '$tce_previous'" \
    > "$tce_dir/restore-property.sh"
printf '%s\n' "$tce_actual" > "$tce_dir/tce-sha256.txt"
printf '%s\n' "$tce_previous" > "$tce_dir/property-before.txt"
getprop ro.build.fingerprint > "$tce_dir/build.txt"
touch "$tce_dir/start-marker"
tce_changed=1
setprop vendor.vivo.vaf.dump.nice.portraitseg -1
[ "$(getprop vendor.vivo.vaf.dump.nice.portraitseg)" = -1 ] || exit 2
echo 'Откройте стоковую камеру. Сделайте ОДИН снимок в авто на том же модуле и зуме, что в SCAMERA.'
echo 'Дождитесь готовой миниатюры, вернитесь в Termux и нажмите Enter. Окно сбора — 90 секунд.'
echo "Каталог восстановления при обрыве Termux: $tce_dir"
# mksh (Android /system/bin/sh) supports a bounded read. A timeout or EOF must
# still restore the original property; neither condition retries capture.
if read -r -t 90 tce_answer; then
    echo finished > "$tce_dir/wait-status.txt"
else
    echo timeout-or-eof > "$tce_dir/wait-status.txt"
fi
restore_tce_property || exit 3

# Copy new TCE JSON only. Never modify or delete the stock camera's files.
if timeout 10 find "$tce_root" -type f -name '*.json' -newer "$tce_dir/start-marker" \
    -print > "$tce_dir/candidates.txt" 2> "$tce_dir/find-error.txt"; then
    echo complete > "$tce_dir/find-status.txt"
else
    echo incomplete > "$tce_dir/find-status.txt"
fi
tce_count=0
while IFS= read -r tce_file; do
    [ -f "$tce_file" ] || continue
    tce_size=$(wc -c < "$tce_file")
    [ "$tce_size" -le 2097152 ] || continue
    if ! grep -q 'RawHDRInputAEParam' "$tce_file"; then continue; fi
    if ! grep -q 'createToneMode' "$tce_file"; then continue; fi
    tce_count=$((tce_count + 1))
    cp "$tce_file" "$tce_dir/json/tce-$tce_count.json"
    printf '%s\n' "$tce_file" >> "$tce_dir/source-paths.txt"
    [ "$tce_count" -lt 16 ] || break
done < "$tce_dir/candidates.txt"
printf '%s\n' "$tce_count" > "$tce_dir/json-count.txt"
# A bounded read of existing TCE messages helps diagnose absent output. No
# global logging property changes or live logcat session are involved.
if command -v logcat >/dev/null; then
    timeout 5 logcat -b main -b system -d -v threadtime -t 1500 \
        -s 'VIVO_NiceTCE:V' '*:S' > "$tce_dir/tce-log.txt" 2> "$tce_dir/log-error.txt" || true
fi
tce_name=${tce_dir##*/}
tar -czf "$tce_parent/$tce_name.tar.gz" -C "$tce_parent" "$tce_name"
echo "Параметр восстановлен. Найдено JSON TCE: $tce_count."
echo "Пришлите архив: $tce_parent/$tce_name.tar.gz"
if [ "$tce_count" = 0 ]; then
    echo 'JSON не появился. Не повторяйте сбор: сначала нужен разбор этого архива.'
    exit 2
fi
