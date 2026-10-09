#!/system/bin/sh
# Run as root from the extracted bundle. No network, setprop, SELinux edits,
# service restarts, firmware writes. v4 captures bounded RGB and LUT memory through stdout.
set -u
[ "$(id -u)" = 0 ] || { echo 'Запусти этот файл через su -c.'; exit 1; }
cd "$(dirname "$0")" || exit 1
[ "$(uname -m)" = aarch64 ] || { echo 'Нужен Android arm64.'; exit 1; }
sha256sum -c SHA256SUMS.txt || exit 1
actual=$(sha256sum /vendor/lib64/libvivo_nicetce.so) || exit 1
[ "${actual%% *}" = 9f5deac3bc68fc86fcf16b98f43c232a9642bc309c7d5d42c88d6a4b596b892d ] || {
  echo 'Другая версия TCE; перехват не запущен.'; exit 1;
}
for storage in /data/local/tmp /sdcard/Download; do
  available=$(df -Pk "$storage" | tail -n 1 | awk '{print $4}')
  case "$available" in ''|*[!0-9]*) echo "Не удалось проверить место: $storage"; exit 1;; esac
  [ "$available" -ge 1048576 ] || { echo 'Нужен минимум 1 ГиБ свободного места для RGB-трассы.'; exit 1; }
done
count=0; target=''
for proc in /proc/[0-9]*; do
  name=$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null | head -n 1)
  if [ "$name" = /vendor/bin/hw/vendor.vivo.hardware.camera3rd.provider@1.0-service ]; then
    target=${proc##*/}; count=$((count+1))
  fi
done
[ "$count" = 1 ] || { echo "Найдено процессов camera3rd: $count. Нужен ровно один."; exit 1; }
# A unique run directory also prevents two collectors attaching concurrently.
lock=/data/local/tmp/scamera-tce-live.lock
mkdir "$lock" 2>/dev/null || { echo "Сборщик уже запущен либо остался каталог $lock после прерывания."; exit 1; }
umask 077
run=$(mktemp -d /data/local/tmp/scamera-tce-trace.XXXXXXXX) || { rmdir "$lock"; exit 1; }
helper=''
finish() {
  trap - EXIT HUP INT TERM
  if [ -n "$helper" ]; then
    kill -TERM "$helper" 2>/dev/null || true
    n=0
    while kill -0 "$helper" 2>/dev/null && [ "$n" -lt 5 ]; do sleep 1; n=$((n+1)); done
    if kill -0 "$helper" 2>/dev/null; then kill -KILL "$helper" 2>/dev/null || true; fi
    wait "$helper" 2>/dev/null || true
  fi
  { echo 'AFTER:'; getenforce; sha256sum /sys/fs/selinux/policy; } >> "$run/environment.txt" 2>&1
  dmesg 2>&1 | grep -E 'avc:.*(hal_camera3rd|frida|ptrace|execmem)' | tail -n 40 > "$run/recent-avc-may-predate-run.txt"
  dest=/sdcard/Download/SCAMERA
  if mkdir -p "$dest" && tar -czf "$dest/${run##*/}.tar.gz" -C "$run" .; then
    echo "ГОТОВО: $dest/${run##*/}.tar.gz"
  else
    echo "Не удалось сохранить архив. Данные остались в $run"
  fi
  rmdir "$lock" 2>/dev/null || true
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
{
  echo 'SCAMERA TCE live trace v4'; date -u; echo "PID=$target"
  getprop ro.build.fingerprint
  echo 'DUMP PROPERTY (unchanged):'; getprop vendor.vivo.vaf.dump.nicetce
  echo 'SELINUX BEFORE:'; getenforce; sha256sum /sys/fs/selinux/policy
  echo 'TCE:'; echo "$actual"
  echo 'TARGET:'; cat "/proc/$target/attr/current"; grep -E '^(Uid|Gid|Groups):' "/proc/$target/status"
} > "$run/environment.txt" 2>&1
chmod 700 frida-inject || exit 1
./frida-inject -p "$target" -s trace.js > "$run/trace.log" 2>&1 &
helper=$!
echo 'v4 сохраняет RGB снимка и LUT; дождись сообщения ГОТОВО.'
echo 'Сборщик запущен. Ожидание подключения...'
now() { read task_u rest < /proc/uptime; echo "${task_u%%.*}"; }
deadline=$(( $(now) + 250 )); announced=0
while [ "$(now)" -lt "$deadline" ]; do
  if grep -q '"event":"ready"' "$run/trace.log" && [ "$announced" = 0 ]; then
    echo 'ПОДКЛЮЧЕНО. Закрой и снова открой стоковую камеру, сделай один обычный снимок 1×.'
    echo 'Затем вернись в Termux. Сборщик остановится автоматически.'
    announced=1
  fi
  grep -q '"event":"finished"' "$run/trace.log" && break
  if ! kill -0 "$helper" 2>/dev/null; then echo 'Перехват завершился; причина будет в архиве.'; break; fi
  sleep 1 || true
done
if ! grep -q '"event":"process_leave"' "$run/trace.log"; then
  echo 'Завершённый вызов TCE не получен. Пришли архив с причиной; повторять пока не нужно.'
fi
