#!/usr/bin/env bash
# Exit check for the local SMB test server. Non-zero on any failure.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PASS="${SMB_TEST_PASSWORD:-boxtest-pw}"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
SMBCLIENT="${SMBCLIENT:-/opt/local/bin/smbclient}"
[ -x "$SMBCLIENT" ] || SMBCLIENT="$(command -v smbclient || true)"
[ -n "$SMBCLIENT" ] && [ -x "$SMBCLIENT" ] || { echo "smbclient missing (MacPorts samba4 provides /opt/local/bin/smbclient; or set SMBCLIENT)" >&2; exit 2; }
dd if=/dev/urandom of="$T/src.bin" bs=1048576 count=16 2>/dev/null
OUT="$("$SMBCLIENT" -p 4445 -L //127.0.0.1 -U "testuser%$PASS" -m SMB2 2>&1)" || { echo "$OUT"; exit 1; }
echo "$OUT"
for s in rewind guest; do echo "$OUT" | grep -qw "$s" || { echo "FAIL: share $s not listed" >&2; exit 1; }; done
for s in rewind guest; do
  U="testuser%$PASS"; [ "$s" = guest ] && U="%"
  "$SMBCLIENT" -p 4445 "//127.0.0.1/$s" -U "$U" -m SMB2 -c "put $T/src.bin chk.bin; get chk.bin $T/back-$s.bin; rm chk.bin"
  cmp "$T/src.bin" "$T/back-$s.bin" || { echo "FAIL: $s byte mismatch" >&2; exit 1; }
  echo "OK: $s 16 MiB put/get byte-equal"
done
echo "ALL OK"
