# App-specific R8 rules. Library modules ship their own consumer rules.

# M5 commit 0: ACRA instantiates the local-only sender factory reflectively (CrashConfig, SimplePluginLoader).
-keep class app.thebox.crash.LocalFileSenderFactory { public <init>(); }

# Storage locations (docs/design/m6-storage-locations.md §7.5, §12.4, §21 U-13): smbj, mbassador and dcerpc.
# Placeholder: WP-S1 writes the rules from spike S-2's release build (mbassador @Handler methods incl.
# SMBClient.connectionClosed; dcerpc's reflective NDR types; slf4j unbound). Until then the release build relies on
# R8's defaults, which smbj issue #538 reports break list() after shrinking.
# --- smbj/mbassador/dcerpc rules (WP-S1) ---
# Commit 0 (measured 2026-10-24, R8 missing classes): mbassador's optional javax.el filters and smbj's Kerberos
# (GSS-API) authenticator reference classes Android lacks. Neither path is used (NTLM only, no EL filters).
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
# --- end smbj/mbassador/dcerpc rules ---
