# Xiaomi 17 Ultra: полный съём стокового зума (и проверка сторонней камеры на логической камере 0).
# Запуск в Termux с root:  sh /sdcard/x17u2/x17u2.sh stock     — стоковая камера, 16 шагов зума + 3 снимка
#                          sh /sdcard/x17u2/x17u2.sh third     — Open Camera (Camera2 API) на основной камере, 4 шага
# Потом заархивировать папку /sdcard/x17u2 целиком и прислать.
WHO=$1
OUT=/sdcard/x17u2
mkdir -p $OUT
su -c "am force-stop org.codeaurora.snapcam"
su -c "am force-stop com.android.camera"
su -c "am force-stop net.sourceforge.opencamera"
su -c "logcat -G 64M"
su -c "logcat -c"
# Наблюдение за ключами по кадрам: cameraserver запоминает историю этих тегов для камер, открытых после этого вызова,
# и печатает её в каждом следующем dumpsys ("Tag monitoring").
TAGS=android.control.zoomRatio,com.xiaomi.camera.userZoomRatio.userZoomRatio,com.xiaomi.optical.zoom.opticalZoomTargetRatio,com.xiaomi.optical.zoom.opticalZoomCurrentRatio,com.xiaomi.optical.zoom.opticalZoomState,org.codeaurora.qcamera3.sensor_meta_data.current_mode,android.lens.focalLength,android.lens.state,xiaomi.snapshot.userZoomRatio,com.xiaomi.sessionparams.operation,xiaomi.thirdparty.isThirdParty,android.control.zoomMethod
su -c "dumpsys media.camera -m $TAGS" > $OUT/${WHO}_monitor_enable.txt
if [ "$WHO" = "stock" ]; then
  su -c "am start -n com.android.camera/.Camera"
  PKG=com.android.camera
  STEPS="0.6 1 2 3.2 3.5 3.8 4.1 4.3 5 6 7 8 10 15 20 30"
elif [ "$WHO" = "third" ]; then
  su -c "monkey -p net.sourceforge.opencamera -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
  PKG=net.sourceforge.opencamera
  STEPS="3.2 3.5 4 4.3"
  echo "В Open Camera: Настройки → Камера API → Camera2 API; основная камера (1x). Зум ставь ползунком."
else
  echo "Укажи stock или third"; exit 1
fi
sleep 5
su -c "dumpsys media.camera" > $OUT/${WHO}_start.txt
for z in $STEPS; do
  echo "Поставь зум ${z}x, подожди 2 секунды и нажми Enter"; read x
  su -c "dumpsys media.camera" > $OUT/${WHO}_${z}.txt
  if grep -q "Client Package Name: $PKG" $OUT/${WHO}_${z}.txt; then echo "OK: камеру держит $PKG"
  else echo "ОШИБКА: камеру держит не $PKG. Открой нужную камеру и повтори шаг"; fi
  grep -m3 -A1 "opticalZoomCurrentRatio\|opticalZoomTargetRatio" $OUT/${WHO}_${z}.txt | grep -o "\[[0-9. ]*\]" | tr '\n' ' '; echo
  if [ "$WHO" = "stock" ]; then
    case $z in 3.5|7|10)
      echo "Сделай снимок на ${z}x, подожди 5 секунд и нажми Enter"; read x
      su -c "dumpsys media.camera" > $OUT/${WHO}_${z}_shot.txt ;;
    esac
  fi
done
su -c "logcat -d -b all" > $OUT/${WHO}_logcat.txt
su -c "dumpsys media.camera" > $OUT/${WHO}_end.txt
su -c "getprop" > $OUT/${WHO}_getprop.txt
if [ "$WHO" = "stock" ]; then
  ls -t /sdcard/DCIM/Camera/*.jpg 2>/dev/null | head -3 | while read f; do cp "$f" $OUT/; done
fi
echo "Готово: $OUT — заархивируй папку целиком"
