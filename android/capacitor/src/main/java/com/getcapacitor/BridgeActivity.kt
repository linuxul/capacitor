package com.getcapacitor

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.getcapacitor.android.R

open class BridgeActivity : AppCompatActivity() {
    // These stay JVM fields (not properties): Java subclasses read and assign them directly.
    @JvmField
    protected var bridge: Bridge? = null

    @JvmField
    protected var keepRunning = true

    @JvmField
    protected var config: CapConfig? = null

    @JvmField
    protected var activityDepth = 0

    @JvmField
    protected var initialPlugins: MutableList<Class<out Plugin>> = ArrayList()

    @JvmField
    protected val bridgeBuilder = Bridge.Builder(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridgeBuilder.setInstanceState(savedInstanceState)
        application.setTheme(R.style.AppTheme_NoActionBar)
        setTheme(R.style.AppTheme_NoActionBar)
        try {
            setContentView(R.layout.capacitor_bridge_layout_main)
        } catch (ex: Exception) {
            setContentView(R.layout.no_webview)
            return
        }

        val loader = PluginManager(assets)

        try {
            bridgeBuilder.addPlugins(loader.loadPluginClasses())
        } catch (ex: PluginLoadException) {
            Logger.error("Error loading plugins.", ex)
        }

        load()
    }

    protected open fun load() {
        Logger.debug("Starting BridgeActivity")

        bridge = bridgeBuilder.addPlugins(initialPlugins).setConfig(config).create()

        // Activity.getIntent() is a platform type; the Java original ignored a null intent here.
        intent?.let { onNewIntent(it) }
    }

    open fun registerPlugin(plugin: Class<out Plugin>) {
        bridgeBuilder.addPlugin(plugin)
    }

    open fun registerPlugins(plugins: List<Class<out Plugin>>) {
        bridgeBuilder.addPlugins(plugins)
    }

    open fun getBridge(): Bridge? = bridge

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        bridge?.saveInstanceState(outState)
    }

    public override fun onStart() {
        super.onStart()
        activityDepth++
        bridge?.let {
            it.onStart()
            Logger.debug("App started")
        }
    }

    public override fun onRestart() {
        super.onRestart()
        bridge?.let {
            it.onRestart()
            Logger.debug("App restarted")
        }
    }

    public override fun onResume() {
        super.onResume()
        bridge?.let {
            it.app.fireStatusChange(true)
            it.onResume()
            Logger.debug("App resumed")
        }
    }

    public override fun onPause() {
        super.onPause()
        bridge?.let {
            it.onPause()
            Logger.debug("App paused")
        }
    }

    public override fun onStop() {
        super.onStop()
        bridge?.let {
            activityDepth = maxOf(0, activityDepth - 1)
            if (activityDepth == 0) {
                it.app.fireStatusChange(false)
            }

            it.onStop()
            Logger.debug("App stopped")
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        bridge?.let {
            it.onDestroy()
            Logger.debug("App destroyed")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bridge?.onDetachedFromWindow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        bridge?.onNewIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        bridge?.onConfigurationChanged(newConfig)
    }
}
