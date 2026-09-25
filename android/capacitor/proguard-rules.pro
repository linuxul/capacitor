# Rules applied to apps that consume this library.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Plugins are discovered by reflection: the bridge reads @CapacitorPlugin and @PluginMethod at runtime,
# including annotation members that are left at their default value.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Plugins are instantiated through their no-argument constructor and their methods are looked up by name
-keep @com.getcapacitor.annotation.CapacitorPlugin public class * {
    public <init>();
    @com.getcapacitor.annotation.PermissionCallback <methods>;
    @com.getcapacitor.annotation.ActivityCallback <methods>;
    @com.getcapacitor.PluginMethod public <methods>;
    @android.webkit.JavascriptInterface <methods>;
}

-keep public class * extends com.getcapacitor.Plugin { *; }

# @PluginMethod(thread = ...) is read at runtime, which looks the enum constant up by its name.
-keep enum com.getcapacitor.PluginThread { *; }
