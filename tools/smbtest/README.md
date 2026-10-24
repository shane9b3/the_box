# Local SMB test server (WP-S5, design doc 16.3)

Unprivileged Samba on port 4445 for the save-location tests. State lives in `work/` (git-ignored).

    tools/smbtest/run-samba.sh start | stop | status | fg
    tools/smbtest/check.sh          # lists shares, 16 MiB put/get + cmp on both; non-zero on failure

- Shares: `rewind` (user `testuser`, password `$SMB_TEST_PASSWORD`, default `boxtest-pw`, tests only) and `guest` (no password, writable).
- Emulator: host `10.0.2.2`, port `4445`, share `rewind` or `guest` (manual entry with a port). JVM tests: `127.0.0.1:4445`.
- Needs Homebrew `samba` (`smbd`, `smbpasswd`, `smbclient`). Apple's `/usr/sbin/smbd` is not used.

## Status: U-18 NOT verified on this Mac (2026-10-24)

`brew install samba` fails here: Homebrew has no Intel bottles for samba 4.25.1 (macOS Intel is unsupported)
and the source build is refused because Xcode 15.2 is "too outdated" (needs 26.3). It would also upgrade 15 existing
formulae. So the config and scripts here are written but untested. Options: update Xcode and retry; or
MacPorts (`sudo port install samba4`, needs admin) and point `SMBD`/`SMBPASSWD` at `/opt/local/sbin/smbd` and
`/opt/local/bin/smbpasswd`; or use the fallback below.

## Fallback: macOS File Sharing (port 445, no guest)

Do this by hand; scripts do not touch system settings.
1. System Settings > General > Sharing > File Sharing on. Add a folder (for example `~/smbtest`) under Shared Folders.
2. Create a sharing-only user (Users & Groups > Add User > "Sharing Only"), give it read & write on the folder.
3. Info (i) > Options > tick "Share files and folders using SMB" for that user (enter its password).
4. Emulator: host `10.0.2.2`, port `445` (default), share name = folder name. Check: `smbclient -L //127.0.0.1 -U sharer`.
Guest cases cannot be covered this way.

## Caveats

- `smb ports = 4445` only; NetBIOS disabled, so no NBNS or discovery tests (device-only per 16.3).
- srvsvc runs in `samba-dcerpcd` (Samba >= 4.17); `smbd` must find it next to itself.
- The `guest` share uses `force user` = your login so files are writable without a Samba account.
- The emulator NAT carries no multicast.
