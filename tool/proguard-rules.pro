# UniFFI's Kotlin bindings use JNA for the FFI. JNA's native library
# (libjnidispatch.so) looks up fields/methods on these classes by name via
# JNI, invisible to R8 — without keeps the release build dies at process
# start with UnsatisfiedLinkError ("Can't obtain peer field ID for class
# com.sun.jna.Pointer").
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# The generated UniFFI bridge registers callbacks and dispatch tables that
# native code reaches by exact name — never shrink or rename it.
-keep class com.lightphone.calls.server.bridge.** { *; }

# Netty's optional integrations (JFR metrics, log4j 1.x/2.x bridges,
# BlockHound, BoringSSL tcnative, management beans) are compile-only —
# none exist on an Android runtime.
-dontwarn io.netty.internal.tcnative.**
-dontwarn java.lang.management.**
-dontwarn jdk.jfr.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.**
