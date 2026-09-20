package com.getcapacitor.annotation

/**
 * Marks the function that receives the result of a permission request started with
 * `requestPermissionForAlias`, `requestPermissionForAliases` or `requestAllPermissions`.
 *
 * The function takes a single `PluginCall`, and its name is what the caller passes as
 * `callbackName`, so renaming it breaks that caller. It may be private, but not internal:
 * Kotlin mangles the JVM name of an internal function, and the launcher is registered under
 * the function's JVM name.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
public annotation class PermissionCallback
