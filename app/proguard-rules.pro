# App-specific R8 rules. Library modules ship their own consumer rules.

# M5 commit 0: ACRA instantiates the local-only sender factory reflectively (CrashConfig, SimplePluginLoader).
-keep class app.thebox.crash.LocalFileSenderFactory { public <init>(); }
