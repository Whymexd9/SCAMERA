#!/system/bin/sh
# Applies the documented live SELinux agent-file rule; Enforcing stays enabled.
# Records bounded scheduling metadata, without image buffers or firmware writes.
set -u
[ "$(id -u)" = 0 ] || { echo 'Запусти этот файл через su -c.'; exit 1; }
cd "$(dirname "$0")" || exit 1
[ "$(uname -m)" = aarch64 ] || { echo 'Нужен Android arm64.'; exit 1; }
sha256sum -c SHA256SUMS.txt || exit 1
for item in 'libvcf_platform_utils.so:5f3bf712b8a8c4b092b7e5bd2ccdc4dfdfced7cc301730d1cc3fcbe8fe1a2f9d' 'libvivo.vaf.algo.nice.so:965c448c63274d031f974c3f24de062efe69ca5f90bb5060ef5e495bf05c1738' 'libvcf_session.so:93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad' 'libvivo.vas.adapter.vcf.so:f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901' 'camera/components/com.vivo.stats.aec.so:b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9'; do
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
nice_target=''; nice_count=0
for proc in /proc/[0-9]*; do
  name=$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null | head -n 1)
  if [ "$name" = /vendor/bin/hw/vendor.vivo.hardware.camera3rd.provider@1.0-service ] && grep -Fq '/vendor/lib64/libvivo.vaf.algo.nice.so' "$proc/maps"; then
    nice_target=${proc##*/}; nice_count=$((nice_count+1))
  fi
done
[ "$nice_count" = 1 ] || { echo "Найдено camera3rd с NICE: $nice_count; нужен ровно один. Пришли этот вывод."; exit 1; }
for pair in "$target:hal_camera_default" "$nice_target:hal_camera3rd_default"; do
  process_id=${pair%%:*}; expected_domain=${pair#*:}
  actual_domain=$(tr -d '\000\n' < "/proc/$process_id/attr/current")
  [ "$actual_domain" = "u:r:$expected_domain:s0" ] || { echo "Неожиданный контекст PID=$process_id: $actual_domain"; exit 1; }
done

# A unique run directory also prevents two collectors attaching concurrently.
lock=/data/local/tmp/scamera-zsl-live.lock
mkdir "$lock" 2>/dev/null || { echo "Сборщик уже запущен либо остался каталог $lock после прерывания."; exit 1; }
umask 077
run=$(mktemp -d /data/local/tmp/scamera-zsl-trace.XXXXXXXX) || { rmdir "$lock"; exit 1; }
helper=''; nice_helper=''
snapshot() {
  echo "TARGET PID=$target"
  cat "/proc/$target/maps" 2>&1
  echo "NICE PID=$nice_target"
  cat "/proc/$nice_target/maps" 2>&1
  echo 'CAMERA PROCESSES AND TARGET LIBRARIES:'
  for proc in /proc/[0-9]*; do
    name=$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null | head -n 1)
    case "$name" in
      *camera*|*Camera*)
        echo "PID=${proc##*/} NAME=$name"
        grep -E 'libvcf_session\.so|libvivo\.vas\.adapter\.vcf\.so|libvivo\.vaf\.algo\.nice\.so' "$proc/maps" 2>/dev/null || true
        ;;
    esac
  done
}

finish() {
  trap - EXIT HUP INT TERM
  for worker in "$helper" "$nice_helper"; do
  if [ -n "$worker" ]; then
    kill -TERM "$worker" 2>/dev/null || true
    n=0
    while kill -0 "$worker" 2>/dev/null && [ "$n" -lt 5 ]; do sleep 1; n=$((n+1)); done
    if kill -0 "$worker" 2>/dev/null; then kill -KILL "$worker" 2>/dev/null || true; fi
    wait "$worker" 2>/dev/null || true
  fi
  done
  cat "$run/capture.log" "$run/nice-inputs.log" > "$run/trace.log" 2>/dev/null || true
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
  echo 'SCAMERA ZSL live trace v21'; date -u; echo "PID=$target NICE_PID=$nice_target"
  getprop ro.build.fingerprint
  echo 'SELINUX BEFORE:'; getenforce; sha256sum /sys/fs/selinux/policy
  sha256sum /vendor/lib64/libvcf_session.so /vendor/lib64/libvivo.vas.adapter.vcf.so
  echo 'TARGET:'; cat "/proc/$target/attr/current"; grep -E '^(Uid|Gid|Groups):' "/proc/$target/status"
} > "$run/environment.txt" 2>&1
snapshot > "$run/modules-before.txt" 2>&1
echo 'Применяются правила tmpfs:file { read write open getattr map execute } для hal_camera_default и hal_camera3rd_default.'
echo 'Правило действует до перезагрузки; режим Enforcing сохраняется.'
if ! sh ./policy-fix.sh "$target" > "$run/policy-fix.log" 2>&1; then
  echo 'Не удалось применить правило. Подключение отменено; причина в policy-fix.log.'
  exit 1
fi
if ! sh ./policy-fix.sh "$nice_target" hal_camera3rd_default > "$run/policy-nice.log" 2>&1; then
  echo 'Правило для camera3rd не применено; причина в архиве.'; exit 1
fi
{ echo "globalThis.SCAMERA_TRACE_ROLE='capture';"; cat trace.js; cat ae-tuning.js; } > "$run/capture.js"
{ echo "globalThis.SCAMERA_TRACE_ROLE='nice_inputs';"; cat trace.js; } > "$run/nice-inputs.js"
chmod 700 frida-inject || exit 1
./frida-inject -p "$nice_target" -s "$run/nice-inputs.js" > "$run/nice-inputs.log" 2>&1 &
nice_helper=$!
./frida-inject -p "$target" -s "$run/capture.js" > "$run/capture.log" 2>&1 &
helper=$!
echo 'ZSL/AE v21: собираются выбранные таблицы AE и коррекции выдержки/усиления; пиксели не копируются.'
echo 'Сборщик запущен. Ожидание подключения...'
now() { read task_u rest < /proc/uptime; echo "${task_u%%.*}"; }
deadline=$(( $(now) + 70 )); announced=0; full_announced=0; status_announced=0
while [ "$(now)" -lt "$deadline" ]; do
  if grep -q '"event":"ready"' "$run/nice-inputs.log" && grep -q '"event":"ready"' "$run/capture.log" && grep -q '"event":"ae_tuning_ready"' "$run/capture.log" && [ "$announced" = 0 ]; then
    echo 'NICE ПОДКЛЮЧЁН. Открой стоковую камеру, сделай один обычный снимок: Фото, 1×.'
    echo 'На снимок есть до 60 секунд с запуска. После снимка вернись в Termux и дождись ГОТОВО.'
    announced=1
  fi
  if grep -q '"event":"ready"' "$run/capture.log" && [ "$full_announced" = 0 ] && [ "$announced" = 0 ]; then
    echo 'AE/ZSL ПОДКЛЮЧЕНЫ. Ожидается готовность второго процесса.'
    full_announced=1
  fi
  if grep -q '"event":"waiting_status"' "$run/capture.log" && [ "$status_announced" = 0 ]; then
    echo 'Состояние через 15 секунд:'
    grep '"event":"waiting_status"' "$run/capture.log"
    status_announced=1
  fi
  if grep -q '"event":"ae_tuning_finished"' "$run/capture.log"; then echo 'Сбор входов AE остановлен; пришли архив.'; break; fi
  grep -q '"event":"finished"' "$run/capture.log" && break
  if grep -q '"event":"finished"' "$run/nice-inputs.log"; then echo 'Наблюдение NICE остановлено; причина в архиве.'; break; fi
  if ! kill -0 "$nice_helper" 2>/dev/null; then echo 'Перехват NICE завершился; причина будет в архиве.'; break; fi
  if ! kill -0 "$helper" 2>/dev/null; then echo 'Перехват завершился; причина будет в архиве.'; break; fi
  sleep 1 || true
done
if ! grep -q '"event":"nice_leave"' "$run/capture.log"; then
  echo 'План NICE не получен. Пришли архив; повторять пока не нужно.'
fi
if grep -q '"event":"nice_leave"' "$run/capture.log" && ! grep -q '"event":"queue_enter"' "$run/capture.log"; then
  echo 'План NICE получен, вызовы очереди ZSL не записаны. Пришли архив для проверки.'
fi

if grep -q '"event":"queue_enter"' "$run/capture.log" && ! grep -q '"event":"delivery_leave"' "$run/capture.log"; then
  echo 'План записан; возврат выдачи буферов не наблюдался. Это не подтверждение полной серии.'
fi

if ! grep -q '"event":"nice_input_leave"' "$run/nice-inputs.log"; then
  echo 'Входы NICE во втором процессе не наблюдались. Пришли архив; это не доказательство связи camera3rd со стоковым снимком.'
fi
