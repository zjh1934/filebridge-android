# FileBridge release R8 规则
# 原则：用 Android 官方默认规则压缩未使用的三方代码；只保留会被反射或框架查找到的入口。

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# 本项目代码与数据模型完整保留（体积很小，换来零反射风险）
-keep class com.example.filebridge.** { *; }
-keepclassmembers class com.example.filebridge.** { *; }

# FileProvider 由 Manifest 反射实例化
-keep class androidx.core.content.FileProvider { *; }

# Compose / AndroidX / Kotlin / 协程都自带 consumer proguard 规则，交给 AGP 处理即可
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.compose.**
-dontwarn kotlin.**
