# Compose 运行时由 R8 规则自动处理；这里只保留必须反射/序列化的成员。
-keepclassmembers class dev.aigw.core.trae.** { <fields>; }
-keep class dev.aigw.core.store.** { *; }

# IPC 桥（Messenger）用 Gson 反射序列化数据类跨进程传递：
#  -keepattributes Signature：否则 List<X>/Map<K,V> 的泛型签名丢失，反序列化会退化成 LinkedTreeMap；
#  -keepclassmembers <fields>：字段只经反射读写，不保会被 R8 当作未使用裁掉。
-keepattributes Signature
-keepclassmembers class dev.aigw.core.** { <fields>; }
-keepclassmembers class dev.aigw.app.ipc.** { <fields>; }
-dontwarn org.nanohttpd.**

# Tink（androidx.security.crypto 的依赖）引用了仅编译期存在的 JSR-305 注解
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
