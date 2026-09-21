#!/system/bin/sh
set -eu
injector=$1
target=$2
script=$3
directory=$(mktemp -d "$4/session.XXXXXX")
mkfifo "$directory/events"
exec 3<&0
"$injector" -p "$target" -s "$script" </dev/null >"$directory/events" &
child=$!
(
    exec 4<"$directory/events"
    while IFS= read -r line <&4; do
        printf '%s\n' "$line"
        case "$line" in
            *'"event":"attached"'*)
                : >"$directory/attached"
                exec cat <&4
                ;;
        esac
    done
) &
pump=$!
(
    while IFS= read -r ignored <&3; do :; done
    # Frida cancellation during attach can corrupt the provider's suspended thread.
    # Wait for script initialization even if the app exits immediately after launch.
    while [ ! -f "$directory/attached" ] && kill -0 "$child" 2>/dev/null; do sleep 0.1; done
    kill -TERM "$child" 2>/dev/null || true
) &
watcher=$!
cleanup() {
    kill -TERM "$watcher" "$pump" 2>/dev/null || true
    rm -f "$directory/events" "$directory/attached"
    rmdir "$directory"
    printf '%s\n' 'SCAMERA_AE_CONTEXT {"event":"finished","reason":"injector_exit"}'
}
trap cleanup EXIT
wait "$child"
