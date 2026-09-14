-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keep class org.bouncycastle.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-keep class androidx.work.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
