#!/system/bin/sh
# Withdrawn: device crash log proves TagMonitor float formatting aborts
# cameraserver, including during client disconnect. Reducing polling does not
# remove the fault. Do not enable watch or dumpsys metadata monitoring here.
echo 'Сборщик отключён: TagMonitor вызывает падение cameraserver на этой прошивке Vivo.'
echo 'Версии v1, v2 и v3 не запускайте. Повторная съёмка для этого сбоя не нужна.'
exit 2
