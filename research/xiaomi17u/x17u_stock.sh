# Xiaomi 17 Ultra: полный съём зума стоковой камеры.
# Termux с root:  sh /sdcard/x17u_stock/x17u_stock.sh
# Потом заархивировать папку /sdcard/x17u_stock целиком и прислать.
OUT=/sdcard/x17u_stock
PKG=com.android.camera
mkdir -p $OUT
su -c "am force-stop org.codeaurora.snapcam"
su -c "am force-stop $PKG"
su -c "logcat -G 64M"
su -c "logcat -c"
# История этих ключей по кадрам: cameraserver запоминает её для камер, открытых после этого вызова,
# и печатает в каждом следующем dumpsys (раздел "Tag monitoring").
TAGS=android.control.zoomRatio,com.xiaomi.camera.userZoomRatio.userZoomRatio,com.xiaomi.optical.zoom.opticalZoomTargetRatio,com.xiaomi.optical.zoom.opticalZoomCurrentRatio,com.xiaomi.optical.zoom.opticalZoomState,org.codeaurora.qcamera3.sensor_meta_data.current_mode,android.lens.focalLength,android.lens.state,xiaomi.snapshot.userZoomRatio,com.xiaomi.sessionparams.operation,xiaomi.thirdparty.isThirdParty,android.control.zoomMethod
su -c "dumpsys media.camera -m $TAGS" > $OUT/monitor_enable.txt
su -c "am start -n $PKG/.Camera"
sleep 5
su -c "dumpsys media.camera" > $OUT/start.txt
for z in 0.6 1 2 3.2 3.5 3.8 4.1 4.3 5 6 7 8 10 15 20 30; do
  echo "Поставь зум ${z}x, подожди 2 секунды и нажми Enter"; read x
  su -c "dumpsys media.camera" > $OUT/z_${z}.txt
  if grep -q "Client Package Name: $PKG" $OUT/z_${z}.txt; then echo "OK: камеру держит сток"
  else echo "ОШИБКА: камеру держит не сток. Открой стоковую камеру и повтори шаг"; fi
  grep -m2 -A1 "opticalZoomTargetRatio\|opticalZoomCurrentRatio" $OUT/z_${z}.txt | grep -o "\[[0-9. ]*\]" | tr '\n' ' '; echo
  case $z in 3.5|7|10)
    echo "Сделай снимок на ${z}x, подожди 5 секунд и нажми Enter"; read x
    su -c "dumpsys media.camera" > $OUT/z_${z}_shot.txt ;;
  esac
done
su -c "logcat -d -b all" > $OUT/logcat.txt
su -c "dumpsys media.camera" > $OUT/end.txt
su -c "getprop" > $OUT/getprop.txt
ls -t /sdcard/DCIM/Camera/*.jpg 2>/dev/null | head -3 | while read f; do cp "$f" $OUT/; done
echo "Готово: $OUT — заархивируй папку целиком"
