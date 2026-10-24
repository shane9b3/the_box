# App-specific R8 rules. Library modules ship their own consumer rules.

# M5 commit 0: ACRA instantiates the local-only sender factory reflectively (CrashConfig, SimplePluginLoader).
-keep class app.thebox.crash.LocalFileSenderFactory { public <init>(); }

# Storage locations (docs/design/m6-storage-locations.md §7.5, §12.4, §21 U-13): smbj, mbassador and dcerpc.
# --- smbj/mbassador/dcerpc rules (WP-S1) ---
# Written by WP-S1 from the sources (2026-10-24); spike S-2's release build on a real server confirms them (U-13).
# Commit 0 (measured 2026-10-24, R8 missing classes): mbassador's optional javax.el filters and smbj's Kerberos
# (GSS-API) authenticator reference classes Android lacks. Neither path is used (NTLM only, no EL filters).
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
# smbj's Kerberos/SPNEGO side also names javax.security.auth (absent on Android; SmbConfig never registers it there).
-dontwarn javax.security.auth.**
# mbassador finds @Handler methods by reflection (Connection, Session, SMBClient.connectionClosed: smbj issue #538) and
# instantiates its subscription/invocation classes reflectively (SubscriptionFactory, MetadataReader). Keep it whole
# (98 KB) and every @Handler method with its annotation.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * { @net.engio.mbassy.listener.Handler <methods>; }
-keep class com.hierynomus.smbj.event.** { *; }
# smbj resolves enums through Class.getEnumConstants (EnumWithValue.EnumUtils: NtStatus, dialects, flags) and keys
# file-information decoders by Class (FileInformationFactory, DiskShare.list): keep both shapes.
-keepclassmembers enum com.hierynomus.** { *; }
-keep class com.hierynomus.msfscc.fileinformation.** { *; }
# SmbConfig probes android.os.Build and loads the SPNEGO factory by name only off Android; NTLM is referenced directly.
-keep class com.hierynomus.smbj.auth.NtlmAuthenticator** { *; }
# dcerpc (srvsvc NetrShareEnum): no reflection in 0.12.13's sources; its enums go through valueOf/values().
-keepclassmembers enum com.rapid7.client.dcerpc.** { *; }
# slf4j stays unbound (no-op) in every build (§8 "Logs"): nothing to keep; the 1.x binder lookup is optional.
-dontwarn org.slf4j.impl.**
# (Save-location integration 2026-10-24, R8 missing class) dcerpc throws java.rmi.UnmarshalException on a malformed
# response; Android has no java.rmi, so that path is a NoClassDefFoundError at runtime, which SmbShareLister catches.
-dontwarn java.rmi.**
# --- end smbj/mbassador/dcerpc rules ---
