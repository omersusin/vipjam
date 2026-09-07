# VipJam ProGuard rules — minify is enabled in release; keep reflection entry points.
-keep class com.vipjam.dsp.VipJamDispatcher { *; }
-keep class com.vipjam.service.VipJamService { *; }
-keep class android.media.audiofx.AudioEffect { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
