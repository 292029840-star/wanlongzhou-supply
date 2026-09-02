# 本项目未开启混淆（minifyEnabled false），保留默认规则即可
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
