package com.getcapacitor;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.getcapacitor.android.R;
import java.util.ArrayList;
import java.util.List;

public class BridgeActivity extends AppCompatActivity {

    protected Bridge bridge;
    protected boolean keepRunning = true;
    protected CapConfig config;

    protected int activityDepth = 0;
    protected List<Class<? extends Plugin>> initialPlugins = new ArrayList<>();
    protected final Bridge.Builder bridgeBuilder = new Bridge.Builder(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridgeBuilder.setInstanceState(savedInstanceState);
        getApplication().setTheme(R.style.AppTheme_NoActionBar);
        setTheme(R.style.AppTheme_NoActionBar);
        try {
            setContentView(R.layout.capacitor_bridge_layout_main);
        } catch (Exception ex) {
            setContentView(R.layout.no_webview);
            return;
        }

        PluginManager loader = new PluginManager(getAssets());

        try {
            bridgeBuilder.addPlugins(loader.loadPluginClasses());
        } catch (PluginLoadException ex) {
            Logger.error("Error loading plugins.", ex);
        }

        this.load();
    }

    protected void load() {
        Logger.debug("Starting BridgeActivity");

        bridge = bridgeBuilder.addPlugins(initialPlugins).setConfig(config).create();

        this.onNewIntent(getIntent());
    }

    public void registerPlugin(Class<? extends Plugin> plugin) {
        bridgeBuilder.addPlugin(plugin);
    }

    public void registerPlugins(List<Class<? extends Plugin>> plugins) {
        bridgeBuilder.addPlugins(plugins);
    }

    public Bridge getBridge() {
        return this.bridge;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (bridge != null) {
            bridge.saveInstanceState(outState);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        activityDepth++;
        if (this.bridge != null) {
            this.bridge.onStart();
            Logger.debug("App started");
        }
    }

    @Override
    public void onRestart() {
        super.onRestart();
        if (this.bridge != null) {
            this.bridge.onRestart();
            Logger.debug("App restarted");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (bridge != null) {
            bridge.getApp().fireStatusChange(true);
            this.bridge.onResume();
            Logger.debug("App resumed");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (bridge != null) {
            this.bridge.onPause();
            Logger.debug("App paused");
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (bridge != null) {
            activityDepth = Math.max(0, activityDepth - 1);
            if (activityDepth == 0) {
                bridge.getApp().fireStatusChange(false);
            }

            this.bridge.onStop();
            Logger.debug("App stopped");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (this.bridge != null) {
            this.bridge.onDestroy();
            Logger.debug("App destroyed");
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (this.bridge != null) {
            this.bridge.onDetachedFromWindow();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (this.bridge == null || intent == null) {
            return;
        }

        this.bridge.onNewIntent(intent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (this.bridge == null) {
            return;
        }

        this.bridge.onConfigurationChanged(newConfig);
    }
}
