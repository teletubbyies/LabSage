# LabSage R8 rules.
#
# Strategy: the app is a single-module Compose application with two
# reflection surfaces — Gson DTO round-trips and the Markwon-based markdown
# renderer. Everything else (Room/KSP, Retrofit, OkHttp, coroutines, Compose)
# ships consumer rules or is codegen'd, so it shrinks cleanly without keeps.

# --- Gson DTOs (reflection: field names ARE the wire format) -------------
# Keep the data classes themselves plus their fields; names must survive.
-keep class com.labsage.app.data.remote.** { *; }
-keep class com.labsage.app.data.auth.** { *; }
-keep class com.labsage.app.domain.model.MessageReference* { *; }
-keep class com.labsage.app.domain.model.ReferencesCodec { *; }

# Generic signatures are part of Gson's TypeToken resolution.
-keepattributes Signature, InnerClasses, EnclosingClass
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# --- Markdown renderer (Markwon builds visitors/spans reflectively) ------
-keep class dev.jeziellago.compose.markdowntext.** { *; }
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# --- OkHttp / Retrofit ship consumer rules; silence optional deps --------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
