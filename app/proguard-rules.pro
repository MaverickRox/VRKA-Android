# Narrowed ProGuard / R8 rules (v5.1.5)

# Jackson reflectively deserializes yt-dlp JSON output into com.yausername.youtubedl_android.mapper.* models.
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-keep @interface com.fasterxml.jackson.annotation.** { *; }

# Commons Compress ExtraFieldUtils reflectively invokes 0-arg constructors on ZipExtraField implementations.
-keepclassmembers class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}

# Keep only the BouncyCastle JCA provider entry point and dynamic SPI implementations (digests,
# asymmetric signature/key factories, DRBG) needed by OpenPGP and X.509/PKCS#7 verification,
# allowing R8 to strip unused PQC, OER, LDAP, symmetric ciphers, and unrelated modules.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD5** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.RIPEMD160** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA1** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA224** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA256** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA384** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA512** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.DSA** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.dsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EC** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.ec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EdEC** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.x509.** { *; }
-keep class org.bouncycastle.jcajce.provider.drbg.DRBG** { *; }

-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# Explicit Room and WorkManager instantiable targets (supplements work-runtime / room-runtime
# consumer rules and removes the broad androidx.work.** { *; } keep).
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
