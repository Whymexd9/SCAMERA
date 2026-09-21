#!/system/bin/sh
# Applies the documented live SELinux agent-file rule; Enforcing stays enabled.
# Records bounded scheduling metadata, without image buffers or firmware writes.
set -u
[ "$(id -u)" = 0 ] || { echo 'Запусти этот файл через su -c.'; exit 1; }
cd "$(dirname "$0")" || exit 1
[ "$(uname -m)" = aarch64 ] || { echo 'Нужен Android arm64.'; exit 1; }
sha256sum -c SHA256SUMS.txt || exit 1
for item in 'libvcf_session.so:93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad' 'libvivo.vas.adapter.vcf.so:f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901'; do
  library=${item%%:*}; expected=${item#*:}
  actual=$(sha256sum "/vendor/lib64/$library") || exit 1
  [ "${actual%% *}" = "$expected" ] || { echo "Другая версия $library; сбор остановлен."; exit 1; }
done
count=0; target=''
for proc in /proc/[0-9]*; do
  name=$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null | head -n 1)
  if [ "$name" = /vendor/bin/hw/vendor.qti.camera.provider-service_64 ]; then
    target=${proc##*/}; count=$((count+1))
  fi
done
[ "$count" = 1 ] || { echo "Найдено процессов Qualcomm camera provider: $count. Нужен ровно один."; exit 1; }
for library in libvivo.vas.adapter.vcf.so libvcf_session.so; do
  if ! grep -Fq "/vendor/lib64/$library" "/proc/$target/maps"; then
    echo "В Qualcomm camera provider PID=$target не найдена $library. Открой стоковую камеру и повтори запуск."
    exit 1
  fi
done
echo "Выбран Qualcomm camera provider PID=$target; обе библиотеки присутствуют."
# A unique run directory also prevents two collectors attaching concurrently.
lock=/data/local/tmp/scamera-zsl-live.lock
mkdir "$lock" 2>/dev/null || { echo "Сборщик уже запущен либо остался каталог $lock после прерывания."; exit 1; }
umask 077
run=$(mktemp -d /data/local/tmp/scamera-zsl-trace.XXXXXXXX) || { rmdir "$lock"; exit 1; }
helper=''
snapshot() {
  echo "TARGET PID=$target"
  cat "/proc/$target/maps" 2>&1
  echo 'CAMERA PROCESSES AND TARGET LIBRARIES:'
  for proc in /proc/[0-9]*; do
    name=$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null | head -n 1)
    case "$name" in
      *camera*|*Camera*)
        echo "PID=${proc##*/} NAME=$name"
        grep -E 'libvcf_session\.so|libvivo\.vas\.adapter\.vcf\.so' "$proc/maps" 2>/dev/null || true
        ;;
    esac
  done
}

finish() {
  trap - EXIT HUP INT TERM
  if [ -n "$helper" ]; then
    kill -TERM "$helper" 2>/dev/null || true
    n=0
    while kill -0 "$helper" 2>/dev/null && [ "$n" -lt 5 ]; do sleep 1; n=$((n+1)); done
    if kill -0 "$helper" 2>/dev/null; then kill -KILL "$helper" 2>/dev/null || true; fi
    wait "$helper" 2>/dev/null || true
  fi
  snapshot > "$run/modules-after.txt" 2>&1
  sh ./collect-crash.sh "$run" > "$run/crash-collector.log" 2>&1
  { echo 'AFTER:'; getenforce; sha256sum /sys/fs/selinux/policy; } >> "$run/environment.txt" 2>&1
  dmesg 2>&1 | grep -E 'avc:.*(hal_camera|camera.provider|frida|ptrace|execmem)' | tail -n 40 > "$run/recent-avc-may-predate-run.txt"
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
  echo 'SCAMERA ZSL live trace v7'; date -u; echo "PID=$target"
  getprop ro.build.fingerprint
  echo 'SELINUX BEFORE:'; getenforce; sha256sum /sys/fs/selinux/policy
  sha256sum /vendor/lib64/libvcf_session.so /vendor/lib64/libvivo.vas.adapter.vcf.so
  echo 'TARGET:'; cat "/proc/$target/attr/current"; grep -E '^(Uid|Gid|Groups):' "/proc/$target/status"
} > "$run/environment.txt" 2>&1
snapshot > "$run/modules-before.txt" 2>&1
echo 'Применяется правило SELinux: hal_camera_default → tmpfs:file { read write open getattr map execute }.'
echo 'Правило действует до перезагрузки; режим Enforcing сохраняется.'
if ! sh ./policy-fix.sh "$target" > "$run/policy-fix.log" 2>&1; then
  echo 'Не удалось применить правило. Подключение отменено; причина в policy-fix.log.'
  exit 1
fi
chmod 700 frida-inject || exit 1
./frida-inject -p "$target" -s trace.js > "$run/trace.log" 2>&1 &
helper=$!
echo 'ZSL v7: собираются только параметры очереди и брекета, без изображений.'
echo 'Сборщик запущен. Ожидание подключения...'
now() { read task_u rest < /proc/uptime; echo "${task_u%%.*}"; }
deadline=$(( $(now) + 100 )); announced=0; full_announced=0; status_announced=0
while [ "$(now)" -lt "$deadline" ]; do
  if grep -q '"event":"ready"' "$run/trace.log" && [ "$announced" = 0 ]; then
    echo 'NICE ПОДКЛЮЧЁН. Открой стоковую камеру, сделай один обычный снимок: Фото, 1×.'
    echo 'После снимка вернись в Termux и дождись ГОТОВО.'
    announced=1
  fi
  if grep -q '"event":"ready"' "$run/trace.log" && [ "$full_announced" = 0 ]; then
    echo 'ОЧЕРЕДЬ ZSL ПОДКЛЮЧЕНА: план, подготовка и выдача буферов наблюдаются.'
    full_announced=1
  fi
  if grep -q '"event":"waiting_status"' "$run/trace.log" && [ "$status_announced" = 0 ]; then
    echo 'Состояние через 15 секунд:'
    grep '"event":"waiting_status"' "$run/trace.log"
    status_announced=1
  fi
  grep -q '"event":"finished"' "$run/trace.log" && break
  if ! kill -0 "$helper" 2>/dev/null; then echo 'Перехват завершился; причина будет в архиве.'; break; fi
  sleep 1 || true
done
if ! grep -q '"event":"nice_leave"' "$run/trace.log"; then
  echo 'План NICE не получен. Пришли архив; повторять пока не нужно.'
fi
if grep -q '"event":"nice_leave"' "$run/trace.log" && ! grep -q '"event":"queue_enter"' "$run/trace.log"; then
  echo 'План NICE получен, вызовы очереди ZSL не записаны. Пришли архив для проверки.'
fi

if grep -q '"event":"queue_enter"' "$run/trace.log" && ! grep -q '"event":"delivery_leave"' "$run/trace.log"; then
  echo 'План записан; возврат выдачи буферов не наблюдался. Это не подтверждение полной серии.'
fi
