#!/system/bin/sh
# Run on the donor phone: su -c 'sh /sdcard/Download/collect_stock_ae.sh'
# Reads vendor files only. No hooks, camera settings or policy changes.
set -eu
if [ "$(id -u)" != 0 ]; then
    echo 'Run this script through su.' >&2
    exit 1
fi
work=$(mktemp -d /data/local/tmp/scamera-stock-ae.XXXXXXXX)
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$work/files"
: > "$work/missing.txt"
for source in \
    /vendor/lib64/camera/components/com.vivo.stats.aec.so \
    /vendor/lib64/camera/components/com.qti.stats.aec.so \
    /vendor/lib64/camera/components/com.qti.stats.aecwrapper.so \
    /vendor/lib64/camera/components/com.qti.stats.aecxcore.so \
    /vendor/lib64/camera/components/com.qtistatic.stats.aec.so \
    /vendor/lib64/camera/com.vivoaec.tuned.pd2454_*.bin
do
    if [ -f "$source" ]; then
        relative=${source#/}
        mkdir -p "$work/files/${relative%/*}"
        cp "$source" "$work/files/$relative"
        sha256sum "$source" >> "$work/SHA256SUMS.txt"
    else
        printf '%s\n' "$source" >> "$work/missing.txt"
    fi
done
getprop ro.build.fingerprint > "$work/build-fingerprint.txt"
out="/sdcard/Download/SCAMERA-stock-AE-$(date +%Y%m%d-%H%M%S)-$$.tar.gz"
mkdir -p /sdcard/Download
tar -czf "$out" -C "$work" .
printf 'Archive: %s\n' "$out"
if [ -s "$work/missing.txt" ]; then
    echo 'Missing paths (also included in archive):'
    cat "$work/missing.txt"
fi
