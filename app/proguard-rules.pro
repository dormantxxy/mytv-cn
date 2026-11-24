# kotlinx.serialization 生成的序列化器由代码直接引用；保留带注解的数据模型属性。
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** serializer(...);
}

# QuickJS 通过 JNI 访问包装类。
-keep class com.whl.quickjs.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# NanoHTTPD / Java-WebSocket 由应用直接创建，但其反射入口需保留。
-keep class fi.iki.elonen.** { *; }
-keep class org.java_websocket.** { *; }

# Media3 Session 的系统组件与 Parcelable。
-keep class androidx.media3.session.** { *; }

# 应用 Android 组件名由 Manifest 引用。
-keep class com.mytv.cn.MainActivity { *; }
-keep class com.mytv.cn.MyApplication { *; }
-keep class com.mytv.cn.CrashActivity { *; }
-keep class com.mytv.cn.PipActionReceiver { *; }

-dontwarn org.slf4j.**
-dontwarn java.beans.**
