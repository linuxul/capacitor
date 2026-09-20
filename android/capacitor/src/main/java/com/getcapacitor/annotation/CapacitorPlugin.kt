package com.getcapacitor.annotation

/**
 * Base annotation for all Plugins
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class CapacitorPlugin(
    /**
     * A custom name for the plugin, otherwise uses the
     * simple class name.
     */
    val name: String = "",
    /**
     * Permissions this plugin needs, in order to make permission requests
     * easy if the plugin only needs basic permission prompting
     */
    val permissions: Array<Permission> = [],
)
