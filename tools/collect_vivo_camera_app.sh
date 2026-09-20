#!/system/bin/sh
# Read-only stock application collection. Does not modify firmware, settings,
# permissions, camera processes, or any application's private data.
set -eu
if [ "$(id -u)" != 0 ]; then
    echo "Run with root: su -c 'sh /sdcard/Download/collect_vivo_camera_app.sh'" >&2
    exit 1
fi
base=/sdcard/Download
work=$(mktemp -d "$base/vivo-camera-app-XXXXXX")
mkdir "$work/apks"
getprop ro.build.fingerprint > "$work/build.txt"
pm path com.android.camera > "$work/pm-path.txt" 2> "$work/pm-errors.txt" || true
sed -n 's/^package://p' "$work/pm-path.txt" > "$work/candidates.txt"
# The previous donor collection reported a PackageManager transaction error.
# Search camera application locations too, including split base.apk files.
for root in /system/app /system/priv-app /system_ext/app /system_ext/priv-app /product/app /product/priv-app /data/app; do
    if [ -d "$root" ]; then
        find "$root" -type f \( -path '*Camera*/*.apk' -o -path '*camera*/*.apk' \) \
            >> "$work/candidates.txt" 2>> "$work/find-errors.txt" || true
    fi
done
sort -u "$work/candidates.txt" > "$work/paths.txt"
count=0
while IFS= read -r apk; do
    [ -f "$apk" ] || continue
    count=$((count+1))
    name="$count-$(basename "$apk")"
    cp "$apk" "$work/apks/$name"
    printf '%s\t%s\n' "$name" "$apk" >> "$work/source-paths.txt"
done < "$work/paths.txt"
if [ "$count" -eq 0 ]; then
    echo "No camera APK found. Collection details: $work" >&2
    exit 1
fi
(cd "$work" && sha256sum apks/*.apk > SHA256SUMS.txt)
archive="$work.tar.gz"
tar -czf "$archive" -C "$work" .
echo "Collected $count camera APK candidates: $archive"
