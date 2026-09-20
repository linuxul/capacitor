package com.getcapacitor

import com.getcapacitor.annotation.CapacitorPlugin
import java.lang.reflect.InvocationTargetException

/**
 * PluginHandle is an instance of a plugin that has been registered
 * and indexed. Think of it as a Plugin instance with extra metadata goodies
 */
public class PluginHandle
    private constructor(public val pluginClass: Class<out Plugin>, private val bridge: Bridge) {
        private val pluginMethods: MutableMap<String, PluginMethodHandle> = HashMap()

        public val id: String

        public val pluginAnnotation: CapacitorPlugin

        // Both public constructors assign this before returning, so it is never observed unset from outside.
        public lateinit var instance: Plugin
            private set

        init {
            val pluginAnnotation =
                pluginClass.getAnnotation(CapacitorPlugin::class.java)
                    ?: throw InvalidPluginException("No @CapacitorPlugin annotation found for plugin " + pluginClass.name)

            id =
                if (pluginAnnotation.name != "") {
                    pluginAnnotation.name
                } else {
                    pluginClass.simpleName
                }

            this.pluginAnnotation = pluginAnnotation

            indexMethods()
        }

        public constructor(bridge: Bridge, pluginClass: Class<out Plugin>) : this(pluginClass, bridge) {
            load()
        }

        public constructor(bridge: Bridge, plugin: Plugin) : this(plugin.javaClass, bridge) {
            loadInstance(plugin)
        }

        public val methods: Collection<PluginMethodHandle>
            get() = pluginMethods.values

        public fun load(): Plugin {
            if (this::instance.isInitialized) {
                return instance
            }

            try {
                instance = pluginClass.getDeclaredConstructor().newInstance()
                return loadInstance(instance)
            } catch (ex: Exception) {
                throw PluginLoadException("Unable to load plugin instance. Ensure plugin is publicly accessible")
            }
        }

        public fun loadInstance(plugin: Plugin): Plugin {
            instance = plugin
            instance.pluginHandle = this
            instance.bridge = bridge
            instance.load()
            instance.initializeActivityLaunchers()
            return instance
        }

        /**
         * Call a method on a plugin.
         * @param methodName the name of the method to call
         * @param call the constructed PluginCall with parameters from the caller
         * @throws InvalidPluginMethodException if no method was found on that plugin
         */
        public fun invoke(methodName: String?, call: PluginCall?) {
            if (!this::instance.isInitialized) {
                // Can throw PluginLoadException
                load()
            }

            val methodMeta =
                pluginMethods[methodName]
                    ?: throw InvalidPluginMethodException("No method " + methodName + " found for plugin " + pluginClass.name)

            methodMeta.method.invoke(instance, call)
        }

        /**
         * Index all the known callable methods for a plugin for faster
         * invocation later
         */
        private fun indexMethods() {
            val methods = pluginClass.methods

            for (methodReflect in methods) {
                val method = methodReflect.getAnnotation(PluginMethod::class.java) ?: continue

                // Fail early instead of at invoke() time: the bridge always calls method(PluginCall).
                val parameterTypes = methodReflect.parameterTypes
                if (parameterTypes.size != 1 || parameterTypes[0] != PluginCall::class.java) {
                    throw InvalidPluginException(
                        "Invalid @PluginMethod " + pluginClass.name + "." + methodReflect.name +
                            ": it must be a public function taking a single PluginCall (not suspend/internal)",
                    )
                }

                val methodMeta = PluginMethodHandle(methodReflect, method)
                pluginMethods[methodReflect.name] = methodMeta
            }
        }
    }
