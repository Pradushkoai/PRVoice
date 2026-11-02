# JEBML uses reflection
-keepclassmembers class * extends org.ebml.Element {
    <init>(...);
}

# Glance finds the widgets to update by the class name of their GlanceAppWidget. If R8 merges
# NowPlayingWidget and ShelfWidget into one class, updating one turns the other into it.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget

# libtorrent4j reaches its swig wrappers through jni; shrinking or renaming them breaks the
# native calls of the torrent engine
-keep class org.libtorrent4j.** { *; }
-keep class org.libtorrent4j.swig.** { *; }
