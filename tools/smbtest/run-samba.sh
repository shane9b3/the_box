#!/usr/bin/env bash
# Usage: run-samba.sh start|stop|status|fg
# Env: SMB_TEST_PASSWORD (default "boxtest-pw", TEST ONLY), SMBD, SMBPASSWD (default: MacPorts samba4 under /opt/local).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/work"
PASS="${SMB_TEST_PASSWORD:-boxtest-pw}"
# MacPorts (sudo port install samba4) is the default; Homebrew cannot build Samba on Intel macOS.
SMBD="${SMBD:-/opt/local/sbin/smbd}"
SMBPASSWD="${SMBPASSWD:-/opt/local/bin/smbpasswd}"
[ -x "$SMBD" ] && [ "$SMBD" != /usr/sbin/smbd ] || { echo "Samba smbd not found at $SMBD (not Apple's /usr/sbin/smbd). Install MacPorts samba4 or set SMBD. See README." >&2; exit 2; }
[ -x "$SMBPASSWD" ] || { echo "smbpasswd not found at $SMBPASSWD; set SMBPASSWD. See README." >&2; exit 2; }
PIDF="$WORK/run/smbd.pid"

prepare() {
  mkdir -p "$WORK"/{private,lock,state,cache,run,ncalrpc,log,share/rewind,share/guest}
  chmod 700 "$WORK/private"
  sed -e "s#__WORK__#$WORK#g" -e "s#__ME__#$(id -un)#g" "$HERE/smb.conf" > "$WORK/smb.conf"
  # smbpasswd -c <conf> -a -s reads the password twice from stdin. The unix user must exist: use ours.
  if ! "$SMBPASSWD" -c "$WORK/smb.conf" -L -e testuser >/dev/null 2>&1; then
    printf '%s\n%s\n' "$PASS" "$PASS" | "$SMBPASSWD" -c "$WORK/smb.conf" -L -a -s testuser
  fi
}
case "${1:-}" in
  start) prepare; "$SMBD" --daemon --configfile="$WORK/smb.conf"; sleep 1; echo "smbd up on 4445 (pid $(cat "$PIDF" 2>/dev/null))";;
  fg)    prepare; exec "$SMBD" -F --debug-stdout --configfile="$WORK/smb.conf";;
  stop)  [ -f "$PIDF" ] && kill "$(cat "$PIDF")" && echo stopped || echo "not running"
         pkill -f "samba-dcerpcd.*$WORK" 2>/dev/null || true;;
  status) [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null && echo running || echo stopped;;
  *) echo "usage: $0 start|stop|status|fg" >&2; exit 64;;
esac
