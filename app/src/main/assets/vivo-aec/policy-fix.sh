#!/system/bin/sh
set -u

[ "$(id -u)" = 0 ] || { echo 'policy_error: root required'; exit 1; }
target=${1:-}
case "$target" in ''|*[!0-9]*) echo 'policy_error: invalid PID'; exit 1;; esac
domain=$(tr -d '\000\n' < "/proc/$target/attr/current") || exit 1
expected_domain=${2:-hal_camera_default}
case "$expected_domain" in hal_camera_default|hal_camera3rd_default) ;; *) echo 'policy_error: unsupported domain'; exit 1;; esac
[ "$domain" = "u:r:$expected_domain:s0" ] || {
  echo "policy_error: unexpected domain $domain"; exit 1;
}
[ "$(getenforce)" = Enforcing ] || {
  echo 'policy_error: expected Enforcing; mode was not changed'; exit 1;
}

rule="allow $expected_domain tmpfs file { read write open getattr map execute }"
backend=''; policy_tool=''
for candidate in /data/adb/ksud /data/adb/ksu/bin/ksud; do
  if [ -x "$candidate" ]; then backend=ksu; policy_tool=$candidate; break; fi
done
if [ -z "$policy_tool" ]; then
  candidate=$(command -v ksud 2>/dev/null || true)
  if [ -n "$candidate" ] && [ -x "$candidate" ]; then backend=ksu; policy_tool=$candidate; fi
fi
if [ -z "$policy_tool" ]; then
  for candidate in /data/adb/magisk/magiskpolicy /debug_ramdisk/magiskpolicy /sbin/magiskpolicy; do
    if [ -x "$candidate" ]; then backend=magisk; policy_tool=$candidate; break; fi
  done
fi
if [ -z "$policy_tool" ]; then
  candidate=$(command -v magiskpolicy 2>/dev/null || true)
  if [ -n "$candidate" ] && [ -x "$candidate" ]; then backend=magisk; policy_tool=$candidate; fi
fi
[ -n "$policy_tool" ] || {
  echo 'policy_error: ksud/magiskpolicy not found; injector not started'; exit 1;
}

echo "POLICY_TOOL=$policy_tool BACKEND=$backend"
echo "RULE=$rule"
echo 'Scope: all tmpfs:file objects accessed by the verified target domain, not one filename.'
echo 'Lifetime: current boot; no module or boot script is installed.'
sha256sum /sys/fs/selinux/policy || exit 1
if [ "$backend" = ksu ]; then
  "$policy_tool" sepolicy patch "$rule"
else
  "$policy_tool" --live "$rule"
fi
status=$?
echo "POLICY_COMMAND_EXIT=$status"
[ "$status" = 0 ] || exit 1
[ "$(getenforce)" = Enforcing ] || {
  echo 'policy_error: Enforcing check failed'; exit 1;
}
sha256sum /sys/fs/selinux/policy || exit 1
echo 'policy_applied: command succeeded; attachment still requires device verification'
