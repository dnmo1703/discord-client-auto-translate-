-keep class dev.discordru.StartupProvider { *; }
-keep class dev.discordru.InlineTranslation {
    public static android.view.View wrap(android.widget.TextView);
    public static void bind(android.widget.TextView, java.lang.String);
}
-keep class dev.discordru.LoopbackProxy {
    public static java.net.ProxySelector clientSelector();
    private static native <methods>;
}
-keep class dev.discordru.TranslatorToRussian {
    private static native <methods>;
}
-repackageclasses dev.discordru.internal
