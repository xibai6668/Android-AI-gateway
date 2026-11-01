# Compose 运行时由 R8 规则自动处理；这里只保留必须反射/序列化的成员。
-keepclassmembers class dev.aigw.core.trae.** { <fields>; }
-keep class dev.aigw.core.store.** { *; }
-dontwarn org.nanohttpd.**

# Tink（androidx.security.crypto 的依赖）引用了仅编译期存在的 JSR-305 注解
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
