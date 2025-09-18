# Keep WebRTC and osmdroid classes that rely on reflection
-keep class org.webrtc.** { *; }
-keep class org.osmdroid.** { *; }

# Retain DJI SDK models when available
-keep class dji.** { *; }
