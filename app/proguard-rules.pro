# LocalCast Pro ProGuard Rules

# 保持MediaCodec相关类
-keep class android.media.MediaCodec { *; }
-keep class android.media.MediaFormat { *; }
-keep class android.media.MediaCodecInfo { *; }

# 保持项目核心类
-keep class com.localcast.pro.core.** { *; }
-keep class com.localcast.pro.service.** { *; }

# 保持网络相关
-keep class java.net.** { *; }
-keep class javax.crypto.** { *; }
