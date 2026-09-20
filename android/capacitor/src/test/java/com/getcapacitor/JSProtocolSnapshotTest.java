package com.getcapacitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.webkit.WebView;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * Pins the JS that the native side generates for the WebView and the message format it
 * sends back, since native-bridge.js and @capacitor/core depend on both.
 *
 * Run with UPDATE_SNAPSHOTS=1 to rewrite the files under src/test/resources/snapshots.
 */
public class JSProtocolSnapshotTest {

    @CapacitorPlugin(name = "Snapshot")
    public static class SnapshotPlugin extends Plugin {

        @PluginMethod
        public void echo(PluginCall call) {}

        @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
        public void watch(PluginCall call) {}

        @PluginMethod(returnType = PluginMethod.RETURN_NONE)
        public void fire(PluginCall call) {}
    }

    @Test
    public void globalJS() throws Exception {
        assertSnapshot("global-js.txt", JSExport.getGlobalJS(null, true, false));
    }

    @Test
    public void pluginJS() throws Exception {
        PluginHandle handle = new PluginHandle(mock(Bridge.class), new SnapshotPlugin());
        assertSnapshot("plugin-js.txt", sortMethods(JSExport.getPluginJS(Collections.singletonList(handle))));
    }

    /**
     * Plugin methods are indexed in a HashMap from Class.getMethods(), neither of which has a
     * guaranteed order, so sort the generated method blocks and headers before comparing.
     */
    private static String sortMethods(String pluginJS) throws Exception {
        String trailer = "\n})(window);\n";
        String headersPrefix = "\nwindow.Capacitor.PluginHeaders = ";
        int trailerAt = pluginJS.indexOf(trailer);
        int headersAt = pluginJS.indexOf(headersPrefix);

        String[] blocks = pluginJS.substring(0, trailerAt).split("\n(?=t\\[')");
        Arrays.sort(blocks, 1, blocks.length);

        JSONArray headers = new JSONArray(pluginJS.substring(headersAt + headersPrefix.length(), pluginJS.length() - 1));
        List<String> methods = new ArrayList<>();
        JSONArray methodHeaders = headers.getJSONObject(0).getJSONArray("methods");
        for (int i = 0; i < methodHeaders.length(); i++) {
            JSONObject method = methodHeaders.getJSONObject(i);
            methods.add(method.getString("name") + " -> " + method.optString("rtype", "(none)"));
        }
        Collections.sort(methods);

        return (
            String.join("\n", blocks) +
            trailer +
            "\nPluginHeaders for " +
            headers.getJSONObject(0).getString("name") +
            ":\n" +
            String.join("\n", methods)
        );
    }

    @Test
    public void injectedScript() throws Exception {
        assertSnapshot("injected-script.txt", newInjector("MISC").getScriptString());
        assertSnapshot("injected-script-no-misc.txt", newInjector(null).getScriptString());
    }

    @Test
    public void successResponseMessage() throws Exception {
        PluginResult result = new PluginResult();
        result.put("value", "hello");

        JSONObject message = sendResponse(false, result, null);

        assertEquals(6, message.length());
        assertFalse(message.getBoolean("save"));
        assertEquals("42", message.getString("callbackId"));
        assertEquals("Snapshot", message.getString("pluginId"));
        assertEquals("echo", message.getString("methodName"));
        assertTrue(message.getBoolean("success"));
        assertEquals("hello", message.getJSONObject("data").getString("value"));
    }

    @Test
    public void errorResponseMessage() throws Exception {
        PluginResult error = new PluginResult();
        error.put("message", "nope");

        JSONObject message = sendResponse(true, null, error);

        assertEquals(6, message.length());
        assertTrue(message.getBoolean("save"));
        assertFalse(message.getBoolean("success"));
        assertEquals("nope", message.getJSONObject("error").getString("message"));
    }

    private static JSInjector newInjector(String miscJS) {
        return new JSInjector("GLOBAL", "BRIDGE", "PLUGINS", "LOCAL_URL", miscJS);
    }

    private static JSONObject sendResponse(boolean keepAlive, PluginResult success, PluginResult error) throws Exception {
        CapConfig config = mock(CapConfig.class);
        when(config.isUsingLegacyBridge()).thenReturn(true);
        Bridge bridge = mock(Bridge.class);
        when(bridge.getConfig()).thenReturn(config);
        WebView webView = mock(WebView.class);

        try (MockedStatic<WebViewFeature> feature = mockStatic(WebViewFeature.class)) {
            feature.when(() -> WebViewFeature.isFeatureSupported(anyString())).thenReturn(false);

            MessageHandler handler = new MessageHandler(bridge, webView);
            PluginCall call = new PluginCall(handler, "Snapshot", "42", "echo", new JSObject());
            call.setKeepAlive(keepAlive);
            handler.sendResponseMessage(call, success, error);
        }

        ArgumentCaptor<Runnable> posted = ArgumentCaptor.forClass(Runnable.class);
        verify(webView).post(posted.capture());
        posted.getValue().run();

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(webView).evaluateJavascript(script.capture(), any());

        String prefix = "window.Capacitor.fromNative(";
        String js = script.getValue();
        assertTrue(js, js.startsWith(prefix) && js.endsWith(")"));
        return new JSONObject(js.substring(prefix.length(), js.length() - 1));
    }

    private static void assertSnapshot(String name, String actual) throws IOException {
        File file = new File("src/test/resources/snapshots", name);
        if (System.getenv("UPDATE_SNAPSHOTS") != null) {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), actual.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!file.exists()) {
            fail("Missing snapshot " + file + ". Run the tests with UPDATE_SNAPSHOTS=1 to create it.");
        }
        assertEquals(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), actual);
    }
}
