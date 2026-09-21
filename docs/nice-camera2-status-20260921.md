# NICE Camera2: проверенная граница, 2026-09-21

Реализован перенос выбора scene/table и расчёта N/S/ES/L, JNI и подключение
к CaptureController. Финальная сборка Actions и обработка NICE в установленном
приложении ещё требуют проверки; арифметика не заменяет эту проверку.

## Расчёт и источник входов

vivo-aec-scene/decrease/base/short-plan/long/solver/wire.h воспроизводят
scene dispatch, оба tuning-банка, decreaseEVCalc, EVBaseCalc, EVMinusCalc,
EVPlusCalc, debug delta EV и коррекции. Выходные экспозиции оригинала не служат
входом переносимого solver. Root observer копирует только входные структуры
и scoped accessors; оригинальный выход используется как независимая проверка.
Любое расхождение блокирует публикацию плана.

Поддержан проверенный донор com.vivo.stats.aec.so SHA-256
b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9.
Неподдержанный донор отвергается. Наблюдатель пассивный, ограничивает чтения,
объём и время, не вызывает vendor-функции и не меняет sensor mode.
SELinux остаётся enforcing; применяется узкое временное правило для root
инжектора, уже используемое исследовательским сборщиком.

Native frame ID сопоставляется с vivo.feedback.AECRealtimeDebugData[0];
дополнительно побитово сверяются shutter, gain, history/target product.
План привязан к generation/timestamp и живёт не более 800 мс.

Camera2 physical ID 3/4 проверены на RAW 4096x3072, ID 5 на 4080x3072.
Для каждой камеры по семи измеренным ступеням
ISO 72..3200 получена калибровка request ISO = round(vendor gain * 50).
Это преобразование запроса, а не подстановка ISO вместо vendor gain.
Каждый RAW проверяется по измеренному Vivo3rdAlgoAECFrameControl gain*shutter
с допуском 1.5% для дискретности сенсора. Поддержаны три rear ID на PD2454; остальные устройства/камеры отвергаются.
В тёмной сцене ID 4 выдал L ISO 5460 выше лимита Camera2 3200: план
отвергнут без подмены экспозиции. Это граница Camera2-доставки стокового плана.

CaptureController посылает 4 отдельных N + L + S + ES. Одинаковые L/N
разрешены, повторение кадров запрещено. Нет VCF2, ручного EV fallback или
записи sensor-mode тегов. Проверяется соответствие RAW/result/роли/серии.
RawTherapee sharpening в postpipeline сохранён.

## Проверки

Существующие проверки воспроизведены: table 3264, adjustment/long 2029,
short 1000, gaps 400, v21 table+adjustment 55+55, CamX 500 и границы,
Java legacy isolation, measured AE и 21 проверка capture identity.
Новые сравнения исходного ARM64: scene/table 5992, EVPlus 1200,
decrease 3000, EVBase 3000 (включая нулевую history correction), EVMinus 3600.
Эмулятор использует общую host libm; это не доказательство всех Android libm
аргументов. Полный solver воспроизводит 32 сохранённых плана N/S/ES/L и
метаданные без oracle-экспозиций (16 stock mode 9 + 16 app mode 14).
MAAE и неиспользуемые слоты полного native output не перенесены в RAW-план.

На телефоне production VivoStockAe/JNI + Camera2 выполнен на ID 3 и ID 5:
семь различных RAW, все семь measured exposure проверок проходят, включая
соответствие RAW timestamps/result. Проверено закрытие root observer без
осиротевшего процесса, раннее закрытие и восстановление наблюдателя после
обрыва: семь RAW повторно проверены без перезапуска provider. Прямая ранняя
отмена Frida до завершения подключения вызывала падение provider; runtime
теперь ждёт attached. Потоковая передача устраняет накопление устаревших входов.
Диагностический запуск через DEX не собирал APK и
не проверял NICE/NPU или качество готовой фотографии.

Доказательства: ../evidence/live-ae-20260921/ и ../reports/stock-ae-device-probe*.txt,
../reports/camera2-probe-manual2.txt, ../reports/camera2-probe-raw.txt,
../reports/ae-frame-context.log. Оригиналы сохранены.

## Оставшиеся приёмочные проверки

Финальный Actions APK с полными neural assets, SHA256SUMS, установка без
локальной перепаковки; реальная обработка NICE и RawTherapee, оценка результата.
Не объявлять эти проверки выполненными до получения результата.

Патчи поверх изменённых исходников не применялись. Начальная проверка transfer
совпала по исходникам/evidence, отличались .git/config и .git/index.
