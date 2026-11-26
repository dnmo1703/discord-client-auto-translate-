-keep class dev.discordru.StartupProvider { *; }
-keep class dev.discordru.InlineTranslation {
    public static android.view.View wrap(android.widget.TextView);
    public static void bind(android.widget.TextView, java.lang.String);
}
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
-repackageclasses dev.discordru.internal
