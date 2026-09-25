package com.getcapacitor;

import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * A plugin written in Java, to keep the annotations usable from Java. The return type is a literal: see
 * BREAKING.md on PluginMethod.RETURN_NONE in Java classes.
 */
@CapacitorPlugin(name = "JavaAnnotated")
public class JavaAnnotatedPlugin extends Plugin {

    @PluginMethod(returnType = "none")
    public void fireAndForget(PluginCall call) {}

    @PluginMethod(thread = PluginThread.MAIN)
    public void present(PluginCall call) {
        call.resolve();
    }
}
