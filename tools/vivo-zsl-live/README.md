# SCAMERA ZSL live v8

v6 проверена трассой `30Hy1RcT`: NICE и обе очереди вернулись,
PID provider 28598 сохранился. Получение будущего кадра v6 не наблюдала.

В трассе v7 VvrJ8241 план и подготовка обеих очередей записаны, PID сохранился,
но ни входа, ни выхода getPastAndNextBuffers нет. Это не доказывает потерю кадра.
v8 наблюдает экспортированные getPastAndNextBuffers (0x12bb14), getPastBuffers
(0x128734) и getNextBuffers (0x129f50) в libvcf_session.so.
Записываются ограниченные векторы shared_ptr и ID, без чтения пикселей
или вызова методов буферов. Наблюдаются все очереди в ограниченном окне;
knownQueue отмечает связь с ранее наблюдавшейся подготовкой.
Вектор BufferPrepareInfo по queue+0x270 имеет шаг 144; он остаётся сырыми байтами.
Окно наблюдения — 30 секунд после первого плана NICE, общий предел агента — 90 секунд,
оболочки — 100 секунд. Завершение окна не означает завершение серии.

Открыть стоковую камеру, затем выполнить в Termux:

```sh
su -c 'scamera_zsl_dir=$(mktemp -d /data/local/tmp/scamera-zsl.XXXXXX) && tar -xzf /sdcard/Download/SCAMERA-ZSL-Live-v1.tar.gz -C "$scamera_zsl_dir" && grep -q "version:8" "$scamera_zsl_dir/trace.js" && sh "$scamera_zsl_dir/run.sh"'
```

После `NICE ПОДКЛЮЧЁН` сделать один снимок Фото, 1×, вернуться в Termux,
дождаться `ГОТОВО`. Результат: Download/SCAMERA/scamera-zsl-trace.*.tar.gz.
Новые перехваты v8 проверен локально; на телефоне ещё не проверен.

## Проверяемые границы

- libvivo.vas.adapter.vcf.so SHA256:
  f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901
  getNiceHdrCaptureControlInfo: 0x101f48.
- libvcf_session.so SHA256:
  93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad
  preparePastAndNextBuffers: 0x12b6f8; getPastAndNextBuffers: 0x12bb14.
- Проверяются путь модуля, хеш на диске и экспортированный адрес.
  Перехватов внутри функций нет. Чтения ограничены 64 KiB/256 элементами.
- Цель — только /vendor/bin/hw/vendor.qti.camera.provider-service_64.
  Неверный домен, несколько provider или несовпадение библиотек отменяют подключение.

Падение v5 подтверждено tombstone_26 из 09UAIDGg: SIGILL по смещению
0x1417f4 внутри literal-данных патча перехвата 0x1417e8. Этот перехват удалён.
Пять текущих перехватов стоят на экспортированных входах функций.

## Анализ

```sh
python3 analyze.py scamera-zsl-trace.XXXXXXXX.tar.gz
node check_trace.js
python3 check_runner.py
```

Анализ разделяет query/произведённый план, очередь и выдачу буферов.
`futureDeliveryObserved` требует известной очереди, возврата 1, роста future-вектора с ненулевыми
указателями и сохранения его прежнего префикса. Это не проверка содержимого RAW,
экспозиций или входа нейросети. ID общие для нескольких очередей, повторы нельзя
считать новыми временными кадрами. Native gain/shutter не переводятся в Camera2.

В 30Hy1RcT: alternateExposureMode=true, 4 past + 1 future; future EV=100,
gain/shutter=0. Catch mode при возврате NICE=4, при подготовке очереди=6.
Эти различия не подменяются постоянными настройками.

## Инжектор и политика

Тот же Frida 17.18.0 arm64, что в проверенной v6:
4952c58fa7f3caca7816e5e8a2b8be9c2b88b95a28110296504007b661c2fc4f.
Автоматическая правка политики внутри инжектора отключена; COPYING/COPYING.LIB включены.
Оболочка применяет одно live-правило через KernelSU или Magisk:

```
allow hal_camera_default tmpfs file { read write open getattr map execute }
```

Оно действует до перезагрузки для всех tmpfs:file объектов этого домена,
не только агента. Enforcing сохраняется. Постоянный модуль не устанавливается,
прошивка, свойства и экспозиции не меняются. Перехваты могут влиять на тайминг.
`collect-crash.sh` собирает существующие crash-логи и text tombstone без инжектора;
исторические записи нужно сопоставлять по PID/времени.
