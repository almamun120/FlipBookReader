# Room / Parcelable models
-keep class com.bookflip.reader.models.** { *; }
-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }
# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
