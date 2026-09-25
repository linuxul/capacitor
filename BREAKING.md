# Breaking changes in this fork

This fork of Capacitor 8 narrows what the runtime supports so that its internals can be plain Kotlin and Swift. Apps and plugins written for upstream Capacitor need the changes below.

## 9.0: changes from fork 8.5.3

This section is for apps and plugins that already run on fork 8.5.3. The sections after it describe how the fork differs from upstream Capacitor.

### Plugin listeners

- Removing a listener releases its callback in the bridge. Before, the callback stayed registered for the lifetime of the page, and each `remove()` registered another one, so events that native still sent to a removed listener were delivered to it. `removeAllListeners()` releases the callbacks of all listeners of that plugin.
- `removeListener` is sent to native as a call that expects no answer (native never answered it).
- When a listener cannot be registered because the call cannot reach native, `addListener()` rejects. Before, the event listener was called with `(null, error)` and the returned promise never settled.
- Removed, deprecated since Capacitor 3: the synchronous `remove()` on the promise returned by `addListener()` (await the promise, then call `remove()` on the handle), and passing a callback as the `options` argument of `Capacitor.nativeCallback()`.

### CapacitorHttp in the WebView

With `CapacitorHttp` enabled, the bridge replaces `fetch` and `XMLHttpRequest`. Their behaviour is now closer to the browser's:

- `fetch` no longer modifies the `init` object or the headers passed to it, and a `Request` object with a body can be fetched from the local server.
- An aborted `AbortSignal` rejects `fetch` with an `AbortError`. The native request itself is not cancelled.
- Responses with status 204, 205 or 304 (and 101, 103) have a `null` body instead of making `fetch` throw. A status a `Response` cannot represent is reported as a `TypeError`, like a network error.
- `Blob` bodies are sent base64 encoded, the same way as `File`. `ArrayBuffer` and other binary views are sent like a `Uint8Array`. They were serialized as JSON before.
- `XMLHttpRequest` is a subclass of the WebView's implementation, so `xhr instanceof XMLHttpRequest` holds again and the shared prototype is no longer rewritten.
- A synchronous `XMLHttpRequest` (`open(method, url, false)`) keeps the WebView implementation instead of silently becoming asynchronous. `open()` accepts a `URL` object and can be called twice.
- A request that fails in native (or whose body cannot be read) ends with an `error` event and status `0`, instead of hanging or reporting `undefined`. A response that arrives after `abort()` is ignored.
- `getResponseHeader()` is case-insensitive and returns `null` for a missing header. `getAllResponseHeaders()` returns lower-cased, sorted names without `Set-Cookie`. A request header set twice is combined (`a, b`) instead of overwritten.

### Web implementations

- `WebPlugin` takes an optional map of event names to event data, `WebPlugin<{ networkStatusChange: ConnectionStatus }>`. With it, `notifyListeners`, `hasListeners` and `registerWindowListener` only accept those events and data. Without it nothing changes.
- `ListenerCallback` is `(event: T) => void` (with `T` defaulting to `any`). It was declared as `(err: any, ...args: any[]) => void`, although listeners always receive the event data as their only argument.
- `PluginCallback` declares its data as `PluginResultData | null`: callback-style calls pass `null` data together with the error when they fail, which the type did not say.
- `HttpResponse` and the `CapacitorHttp` methods take an optional type for the response data: `CapacitorHttp.get<User[]>(...)` returns `HttpResponse<User[]>`. The default stays `any`.
- `SystemBars` on web resolves without doing anything, as before. This is now intentional and documented; the web implementation used to create an "unavailable" error and drop it.
- A `WebPlugin` listener that removes itself while it is being notified no longer makes the next listener be skipped.
- After the last listener of an event is removed, events sent with `retainUntilConsumed` are retained again and delivered to the next listener.
- `CapacitorHttp` on web joins array URL parameters correctly (`tag=a&tag=b&page=2`, not `tag=a&tag=b&&page=2`).
- `CapacitorCookies.getCookies()` on web no longer throws on a cookie without `=`; it is reported with an empty value.

### iOS console and window errors

- An `Error` logged on iOS reaches the Xcode console as `Name: message` and its stack instead of `{}`. An object that refers to itself no longer drops the whole message.
- `window.onerror` reports errors whose first argument is an `Event` (for example a failed resource load) instead of throwing inside the handler.

### Android runtime

- A call that is not kept alive settles once. The first `resolve`, `reject`, `unimplemented`, `unavailable` or `errorCallback` answers JavaScript; every later one is dropped and logged as a warning ("Ignoring … already settled"), including the bridge's own rejection of a method that resolved and then threw. `keepAlive` calls still resolve any number of times.
- `requestPermissionForAlias(es)` with an empty alias array rejects with "No permission alias was provided". Aliases that map to no Android permission string (`strings = []`) no longer leave the call pending: the named `@PermissionCallback` runs right away, on the main thread like a prompt result, and such aliases report `granted`.
- An exception thrown by a `@PermissionCallback` or `@ActivityCallback` rejects the saved call instead of leaving the promise pending. Without a saved call the callback still runs with `null`; declare it `PluginCall?` if you handle that case.
- `requestPermissions` with only unknown aliases rejects; it no longer also resolves.
- `CapacitorCookies.setCookie` without `key` or `value`, and `deleteCookie` without `key`, only reject; they no longer write a cookie named `null` and resolve.
- `removeAllListeners` (the plugin method, and the reset of the bridge) releases the saved `addListener` calls.
- Plugin listeners and saved calls are safe to use from several threads (for example a network callback thread and the main thread) at once.
- Replies to JavaScript are posted on the main thread, as `WebMessage` requires, instead of being sent from whatever thread settled the call.
- WebView permission prompts (camera, microphone, geolocation, file-input capture) are queued so that each one is answered; a second prompt no longer makes the first one hang. A cancelled system request denies the prompt instead of granting it.
- Local server: a malformed or unsatisfiable `Range` header is ignored and the file is served whole with 200 instead of crashing the app. Asset, file and content streams are closed after the WebView reads them instead of leaking a file descriptor each.
- `HttpRequestHandler.request` no longer stores the connection in `call.data` under `activeCapacitorHttpUrlConnection`, and always disconnects it.
- `FileUtils.getFileUrlForUri` returns `null` instead of throwing when the provider gives no cursor or no display name.
- `Bridge.triggerJSEvent` and the other `trigger*JSEvent` functions quote their string arguments as JavaScript string literals, so quotes or line breaks in them no longer break or inject script. The `data` argument of `triggerJSEvent` must still be JSON.
- `Logger.error(message)`, `PluginConfig.getString(key)`, `PluginConfig.getArray(key)` and `InternalUtils.getPackageInfo(pm, packageName)` are callable from Java, as described under "API changes" below.
- `<input type="file" capture>` opens the camera app. The library manifest declares `<queries>` for `android.media.action.IMAGE_CAPTURE` and `VIDEO_CAPTURE`, which merge into the app; without them Android 11 and later hid the camera app from the runtime and capture always fell back to the file picker.
- An invalid `server.url` (for example `192.168.1.5:8100` without a scheme) stops the app with an `IllegalArgumentException` ("Provided server url is invalid: …") when the bridge is created, instead of a `NullPointerException` later.
- An empty `backgroundColor` is ignored like any other invalid color instead of crashing the app at startup; `WebColor.parseColor("")` throws `IllegalArgumentException`.
- A request for a path made only of slashes (`https://localhost//`) no longer crashes the app. In html5mode it gets `index.html`; otherwise the local server does not answer it.
- `WebView.setServerBasePath`/`setServerAssetPath` without `path` reject with "Must provide a path" and leave the served path alone, instead of serving from `null` until the app restarts.
- `CapacitorHttp` requests with `params` keep the URL fragment out of the query (`?q=1#r` plus a param used to reach the server as `?q=1&page=2r`).
- `FileUtils.getFileUrlForUri` returns `null` for a media document it cannot resolve (for example a PDF from the picker's Documents category) instead of throwing.

### iOS runtime

- A call that is not kept alive settles once: `resolve`, `reject`, `unimplemented` and `unavailable` send only the first result; later ones are dropped and logged ("already settled"). A call with `keepAlive = true` may send any number of results. Calling `successHandler`/`errorHandler` directly is not guarded.
- An error result carries `save: keepAlive`, as successful results always did and as on Android, so the page releases a callback-style call after an error ends it. A plugin that keeps sending results after a reject must set `keepAlive = true` first.
- `removeAllListeners` releases the bridge's saved listener calls.
- Plugins are registered and consulted in a fixed order: built-in plugins, then `packageClassList` from `capacitor.config.json` in listed order, then plugins registered with `registerPluginInstance`/`registerPluginType`. `shouldOverrideLoad(_:)` and `handleWKWebViewURLAuthenticationChallenge` are asked in this order and the first answer wins; the order used to change between launches.
- A call to a plugin name that is not registered only loads a class whose Obj-C name is that `jsName`, that is a `CAPPlugin` conforming to `CAPBridgedPlugin` and not a `CAPInstancePlugin`, and that is not registered yet. Calling a registered plugin by its class identifier (for example `CAPCookiesPlugin`) rejects with `UNIMPLEMENTED` instead of replacing the registered instance.
- `Range` requests on the app scheme: `bytes=a-b`, `bytes=a-` and `bytes=-n` return 206, with the end clamped to the file; `bytes=-n` returns the last n bytes (it used to return the first n). A well-formed range that selects no byte of the file returns 416 with `Content-Range: bytes */<size>`. A malformed header, another unit, or several ranges return the whole file (200) instead of crashing the app.
- Scheme tasks always complete: a request without a URL, a missing file or a non-HTTP response fails the task instead of leaving it open.
- The `WebView` plugin methods and `CapacitorCookies.clearCookies` settle instead of never answering.
- `CapacitorHttp` turns number and boolean `params` into strings instead of crashing, rejects a non-HTTP response, and rejects malformed FormData or non-base64 `file` data before sending anything.
- `alert`, `confirm` and `prompt` are shown from the topmost view controller. When they cannot be shown, the page gets the dismissed answer (`false` for confirm, `null` for prompt) instead of hanging.
- Decoding a short array into a fixed-size type with `call.decode` throws `DecodingError.valueNotFound` instead of crashing, and nested decoding errors carry their coding path.
- `UIColor.capacitor.color(argb:)` scales each channel to 0...1; it used to pass 0–255 through, so every non-zero channel came out at full intensity.
- Strings in the scripts the bridge evaluates (callback ids, plugin and method names, messages) are JSON-encoded, so quotes or line breaks in them no longer break the script.

### Kotlin plugin API

A `@PluginMethod` may be a `suspend` function, may choose its thread, and may throw to reject:

- `@PluginMethod(thread = PluginThread.MAIN)` runs the method on the main thread (the default, `PluginThread.PLUGIN`, is the bridge's plugin thread). What it throws rejects the call instead of crashing the app.
- `public suspend fun name(call: PluginCall)` may return `Unit` or a `JSObject`. It starts and resumes on its thread, and returning resolves the call (with the `JSObject`, or without data if the method did not answer the call itself). Suspend methods cannot use `RETURN_CALLBACK`, which is rejected at registration; a `RETURN_NONE` suspend method is not answered. When the page reloads or the bridge is destroyed, running suspend calls are rejected with "The plugin call was cancelled"; the method itself is not interrupted and its later result is dropped.
- `throw PluginException(message, code, data, cause)` from a plugin method, a `@PermissionCallback` or an `@ActivityCallback` rejects the call with that message, code and data.
- `requestPermissionsFor(vararg aliases): Map<String, PermissionState>` asks for permissions from a suspend method without a `@PermissionCallback`.

No kotlinx.coroutines dependency is needed: `suspendCoroutine` is in the Kotlin standard library. The callback style of 8.5.3 keeps working.

Before (8.5.3):

```kotlin
@PluginMethod
fun take(call: PluginCall) {
    if (getPermissionState("camera") != PermissionState.GRANTED) {
        requestPermissionForAlias("camera", call, "cameraPermsCallback")
        return
    }
    capture(call)
}

@PermissionCallback
private fun cameraPermsCallback(call: PluginCall) {
    if (getPermissionState("camera") != PermissionState.GRANTED) {
        call.reject("Camera permission was denied", "DENIED")
        return
    }
    capture(call)
}

private fun capture(call: PluginCall) {
    activity.runOnUiThread {
        camera.capture { uri, error ->
            if (error != null) call.reject("Capture failed", ex = error)
            else call.resolve(JSObject().put("path", uri.toString()))
        }
    }
}
```

After (9.0):

```kotlin
@PluginMethod(thread = PluginThread.MAIN)
suspend fun take(call: PluginCall): JSObject {
    if (requestPermissionsFor("camera")["camera"] != PermissionState.GRANTED) {
        throw PluginException("Camera permission was denied", code = "DENIED")
    }
    val uri = suspendCoroutine { c ->
        camera.capture { uri, error -> if (error != null) c.resumeWithException(error) else c.resume(uri) }
    }
    return JSObject().put("path", uri.toString())
}
```

### Android plugin API changes

- `PluginCall.callbackId`, `pluginId` and `methodName` are `String`. A message from the page without them is dropped and logged.
- These parameters are `String` instead of `String?`: `Bridge.getPlugin`, `Bridge.callPluginMethod`, `PluginHandle.invoke` (its `call` is non-null too), `Plugin.notifyListeners` and `hasListeners` (event name), `Plugin.getPermissionState`, the callback name of `Plugin.startActivityForResult`, `CapConfig.getPluginConfiguration`, `PermissionHelper.hasDefinedPermission`. Every call site in the official and community plugins already passes a non-null value. Java callers that pass `null` get a `NullPointerException`.
- `addListener` without an `eventName` rejects the call, as on iOS.
- Removed, unused by the official and community plugins: `PluginCall.successCallback` (use `resolve`), `AppUUID`, `JSObject.putSafe` (use `put`), `Bridge.logToJs`, `HttpRequestHandler.ProgressEmitter`, `FileUtils.Type`, and the `AssetUtil` instance API (`getInstance`, `parse`, `getResId`, `getIconFromUri`). `AssetUtil` is an object: from Java, `AssetUtil.INSTANCE.getResourceID(...)`.
- Deprecated, to be removed in 10.0: `PluginCall.errorCallback` (use `reject`).
- `CapacitorHttp` declares no permissions: its `HttpWrite`/`HttpRead` aliases asked for storage permissions that apps cannot be granted on API 33 and later.
- `SystemBars.setStyle/show/hide` run on the main thread and `CapacitorCookies.getCookies` is a suspend method; their results are unchanged.
- `@PluginMethod` has a `thread` element; the consumer ProGuard rules keep `com.getcapacitor.PluginThread`.
- `Bridge.appUrl` and `Bridge.localUrl` are `String` instead of `String?`.
- `HttpRequestHandler.request(call, method, null)` works without a bridge; it used to throw when the call had no User-Agent header.
- `HttpURLConnectionBuilder` without `setUrl`/`setHeaders`, and `Bridge.Builder(fragment).create()` for a detached fragment, throw `IllegalStateException` with a message instead of `NullPointerException`.
- New Android apps register `clean` with `tasks.register` and delete `rootProject.layout.buildDirectory`, and set `ignoreAssetsPattern` in `androidResources` instead of `aaptOptions`, so the template builds without Gradle deprecation warnings. Existing apps can make the same two edits; `cap migrate` does not.

### CLI

- The CLI collects and sends no usage data. Upstream Capacitor sends metrics, including the dependency specs of the app (which for this fork are the release tarball URLs), to an upstream service. `npx cap telemetry` is kept so scripts that call it keep working, but it only reports that nothing is collected.
- `npx cap init` no longer offers to create an Ionic account.
- The hidden `create` and `plugin:generate` commands are removed. They only pointed at the upstream `npm init` templates, which install upstream Capacitor.
- The generated `ios/App/CapApp-SPM/Package.swift` declares at least iOS 17, even if the Xcode project still targets an older version, because SwiftPM refuses to link the runtime otherwise.
- `npx cap migrate` removes the installed `@capacitor/*` packages (except the CLI) before reinstalling them, as it was meant to. The removal silently did nothing before.
- The repository no longer contains the npm, Maven Central and CocoaPods publishing scripts and workflows. Releases are the GitHub release tarballs described under [Installing](#installing).

## Installing

The fork is not published to npm. Its packages keep the `@capacitor/*` names, and each version is attached to a GitHub release as tarballs, which an app installs by URL:

```json
"dependencies": {
  "@capacitor/core": "https://github.com/linuxul/capacitor/releases/download/8.5.3/capacitor-core-8.5.3.tgz",
  "@capacitor/android": "https://github.com/linuxul/capacitor/releases/download/8.5.3/capacitor-android-8.5.3.tgz",
  "@capacitor/ios": "https://github.com/linuxul/capacitor/releases/download/8.5.3/capacitor-ios-8.5.3.tgz"
},
"devDependencies": {
  "@capacitor/cli": "https://github.com/linuxul/capacitor/releases/download/8.5.3/capacitor-cli-8.5.3.tgz"
}
```

Installing `@capacitor/*` by version from npm gets upstream Capacitor instead. `npx cap migrate` leaves a Capacitor package alone when it points at a tarball, a `file:` path or git, and only pins the ones that come from the registry.

## Supported platforms

- **iOS 17** is the minimum deployment target.
- **Android 13 (API 33)** is the minimum SDK.

`npx cap migrate` raises both for an existing app. To do it by hand:

- iOS: set `IPHONEOS_DEPLOYMENT_TARGET = 17.0` in `ios/App/App.xcodeproj/project.pbxproj` and `platform :ios, '17.0'` in `ios/App/Podfile`.
- Android: set `minSdkVersion = 33` in `android/variables.gradle`.

## Cordova is not supported

Cordova plugins are no longer loaded on either platform, and the CLI no longer generates anything for them.

- A package that only has a `plugin.xml` (or a `cordova` key in its `package.json`) is reported as skipped by `cap sync`, `cap update` and `cap ls`. It is never an error.
- The `cordova` and `ios.cordovaLinkerFlags` options in `capacitor.config` are ignored. A warning is printed if `cordova` is still set.
- `cordova.js` and `cordova_plugins.js` are no longer copied into the web assets, and `window.cordova` is no longer defined by the bridge. A leftover `<script src="cordova.js">` tag is harmless: the request is answered with an empty script on Android and the 404 is not logged on iOS.
- `DisableDeploy` and `KeepRunning` came from Cordova's `config.xml` preferences and are gone, together with the `keepRunning` field on `BridgeActivity` that mirrored the latter. The runtime behaves as it did with their defaults.

### Updating an existing Android app

`cap update android` stops with an error while these lines are still present. Remove them:

```groovy
// android/settings.gradle
include ':capacitor-cordova-android-plugins'
project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')

// android/app/build.gradle
repositories {
    flatDir {
        dirs '../capacitor-cordova-android-plugins/src/main/libs', 'libs'
    }
}
implementation project(':capacitor-cordova-android-plugins')
```

If the app loads `.aar` files from `app/libs` through that `flatDir` block, keep a `flatDir { dirs 'libs' }` entry. The `android/capacitor-cordova-android-plugins` directory is deleted by the CLI.

### Updating an existing iOS app

- Remove `pod 'CapacitorCordova', ...` from `ios/App/Podfile`.
- Remove the `config.xml` reference from the Xcode project's resources. The CLI no longer creates that file and warns when the reference is still there.

### Cleartext traffic

The CLI used to enable `usesCleartextTraffic` through the generated Cordova module whenever `server.cleartext` was set, including in release builds. New projects instead get `android/app/src/debug/AndroidManifest.xml`, which allows cleartext in **debug builds only**, so live reload keeps working. `server.cleartext` no longer has any effect on release builds; declare `android:usesCleartextTraffic` in your own manifest if you need it there. Existing apps should add the same debug manifest:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:usesCleartextTraffic="true" />
</manifest>
```

## Bridge callback ids

The `callbackId` that ties a native call to its JavaScript promise is a random version 4 UUID made with `crypto.getRandomValues`, instead of a counter that went up by one from a random start. A pending id can therefore no longer be guessed from an earlier one. Nothing changes for plugins: both runtimes already treated the id as an opaque string, and `"-1"` still marks a call that expects no response. Code that parsed the id as a number, which the runtimes never did, would break.

This is not a defence against script injection. A script that runs in the page can call plugins directly whatever the ids look like; restrict `server.allowNavigation`, set a Content-Security-Policy and do not load remote content to address that.

### Bridge dispatch failures

Calls to a missing plugin or method now reject their JavaScript promise with `UNIMPLEMENTED` on both platforms. Android also rejects when plugin loading or the plugin thread fails; an exception thrown by a plugin method rejects the call instead of crashing the handler thread. If the WebView cannot post a message to the native bridge, the JavaScript callback or promise receives the transport error and its saved callback is released. Handle these rejections in app code instead of relying on a call to remain pending.

## iOS plugins

### Objective-C plugins are not supported

The runtime contains no Objective-C. The `CAP_PLUGIN`, `CAP_PLUGIN_METHOD` and `CAP_PLUGIN_CONFIG` macros, the `<Capacitor/Capacitor.h>` umbrella header and the `CAPBridgedJSTypes` accessors are gone, and the CLI only scans `.swift` files. A plugin is a Swift class:

```swift
@objc(EchoPlugin)
public class EchoPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "EchoPlugin"
    public let jsName = "Echo"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "echo", returnType: .promise)
    ]

    @objc func echo(_ call: CAPPluginCall) {
        call.resolve(["value": call.getString("value") ?? ""])
    }
}
```

What still matters from the Objective-C runtime: the class needs `@objc(Name)` because the CLI registers plugins by that name, and plugin methods need `@objc` because the bridge calls them by selector.

### API changes

- `CAPPluginMethod(name:returnType:)` takes `CAPPluginMethod.ReturnType` (`.promise`, `.callback`, `.none`). The `CAPPluginReturnPromise`, `CAPPluginReturnCallback` and `CAPPluginReturnNone` constants are removed.
- `CAPPlugin` subclasses that declare their own initializer must also declare `required init()`.
- `import Capacitor` no longer brings UIKit, WebKit and the other frameworks along, because the Objective-C umbrella header that re-exported them is gone. Import what a file uses, for example `import UIKit` for `UIApplication`.
- `shouldOverrideLoad(_:)` returns `Bool?` instead of `NSNumber?`.
- `CAPBridgeProtocol` is a Swift protocol and no longer `@objc`. `InstanceConfiguration` is a struct.
- `injectScriptBeforeLoad(path:)` is a plain `CAPBridgeProtocol` requirement with a no-op default implementation, not an `@objc optional` one. Call `bridge?.injectScriptBeforeLoad(path: path)`; the optional-method form `bridge?.injectScriptBeforeLoad?(path: path)` no longer compiles.
- Removed with the Cordova layer: the `cordovaConfiguration` parameter of `InstanceDescriptor.init(at:configuration:)` and `CapacitorBridge.init`, the `InstanceDescriptor.cordovaConfiguration` property, `cordovaDeployDisabled` on `InstanceDescriptor` and `InstanceConfiguration`, and the `InstanceWarning.missingCordovaFile` and `.invalidCordovaFile` cases.
- `InstanceLoggingBehavior` is an enum rather than an option set.
- `CAPPluginCall.options` is a `JSObject`.
- `CAPPluginCall` is immutable except for `keepAlive`: `callbackId`, `methodName`, `options`, `successHandler` and `errorHandler` are `let`.
- `InstanceConfiguration.pluginConfigurations` is a `JSObject`. `InstanceDescriptor.pluginConfigurations` is unchanged: it stays an untyped dictionary so a host app can assign raw JSON into it, and `normalize()` still coerces it.
- `addListener` without an `eventName` rejects the call instead of crashing.
- Removed deprecated API: `CAPBridge`, `CAPFileManager`, `JSDate`, `JSResultBody`, the `CAPNotifications` enum; `getWebView`, `isSimulator`, `isDevMode`, `getStatusBarVisible`, `getStatusBarStyle`, `getUserInterfaceStyle`, `getLocalUrl`, `getSavedCall`, `releaseCall(callbackId:)`, `presentVC`, `dismissVC`, `modulePrint`, `httpsInterceptorStartIdentifier` and the status bar setters on the bridge; `isSaved`, `save()`, `hasOption` and the initializer without a method name on `CAPPluginCall`; `getConfigValue`, `supportsPopover`, `getBool(_:field:defaultValue:)`, `getString(_:field:defaultValue:)` and `init(bridge:pluginId:pluginName:)` on `CAPPlugin`; `getAny` on `JSValueContainer`; `legacyConfig`, `getPluginConfigValue`, `getValue` and `getString` on the instance configuration; the `PluginCallErrorData` and `PluginResultData` typealiases (use `PluginCallResultData`).

### Swift Package Manager

`@capacitor/ios` ships a source `Package.swift` and the app consumes it by path, through `ios/App/CapApp-SPM/symlinks/capacitor-swift-pm`. The CLI recreates that link on every `cap sync`/`cap update`; add `App/CapApp-SPM/symlinks` to `ios/.gitignore`. The upstream `capacitor-swift-pm` binary release is not used.

- A plugin's `Package.swift` must declare `platforms: [.iOS(.v17)]` or later, otherwise Xcode refuses to link it against the runtime.
- A plugin may keep depending on `https://github.com/ionic-team/capacitor-swift-pm.git`: the local package takes over for the whole graph because it has the same identity. SwiftPM prints a "Conflicting identity" warning for this and says it will become an error in a future version, so plugins you maintain should depend on the runtime by path instead.

## Android plugins

The runtime is written in Kotlin. The app template applies the Kotlin Gradle plugin and creates `MainActivity.kt`; an existing `MainActivity.java` that extends `BridgeActivity` keeps working. The library follows the app's `kotlin_version` (set in the root `build.gradle` of the template) so both use one Kotlin Gradle plugin.

### Writing plugins

- A `@PluginMethod` function must be public and take exactly one `PluginCall`, or be a `suspend` function of one `PluginCall` (since 9.0, see "Kotlin plugin API"). A function whose JVM signature is anything else, for example one with a value class parameter or a second argument, is rejected with an `InvalidPluginException` when the plugin is registered. `internal` is not rejected, because the JVM signature still matches, but Kotlin mangles the name to `yourMethod$yourModule` so JavaScript can never address it: the method is silently missing from the plugin. `@PermissionCallback` and `@ActivityCallback` functions may be private but not `internal`, for the same mangling reason.
- `@NativePlugin` and the request-code based permission and activity result flow are removed, together with `@CapacitorPlugin(requestCodes = ...)`. Use `@PermissionCallback` with `requestPermissionForAlias`/`requestAllPermissions`, and `@ActivityCallback` with `startActivityForResult(call, intent, "callbackName")`.
- `BridgeActivity` no longer overrides `onRequestPermissionsResult` and `onActivityResult`; results always go through the AndroidX activity result registry.
- In a module that mixes Java and Kotlin, a Java class that writes `@PluginMethod(returnType = PluginMethod.RETURN_NONE)` and is subclassed from Kotlin crashes the Kotlin compiler, because the constant lives in the companion of a Kotlin annotation. Use the literal (`"none"`, `"callback"`, `"promise"`) in the Java class, or write the class in Kotlin.

### API changes

- `PluginCall`: the getters take an optional default (`getString(name, defaultValue = null)` and likewise for `getInt`, `getLong`, `getFloat`, `getDouble`, `getBoolean`, `getObject`, `getArray`), and `reject` is a single function, `reject(msg, code = null, ex = null, data = null)`. A call that passed an exception or a data object as the second positional argument no longer compiles; name it instead: `call.reject("msg", ex = e)`. `isKeptAlive()`/`setKeepAlive()` are the `keepAlive` property. These functions are `@JvmOverloads`, so `call.getString("x")`, `call.resolve()` and `call.reject("msg")` still work from Java.
- `Plugin`: `notifyListeners(eventName, data, retainUntilConsumed = false)` is one function (also `@JvmOverloads`). `bridge` is a public `lateinit` property, and `getContext()`, `getActivity()`, `getConfig()`, `getAppId()` and `getLogTag()` are final properties, so they can no longer be overridden. The lifecycle hooks (`load`, `handleOnStart`, ...) are unchanged.
- Utility classes are Kotlin objects without static methods. From Kotlin nothing changes (`Logger.debug(...)`, `JSObject.fromJSONObject(...)`); from Java they are reached through `INSTANCE` or `Companion`: `Logger.INSTANCE.debug(...)`, `JSObject.Companion.fromJSONObject(...)`. This applies to `Logger`, `FileUtils`, `JSONUtils`, `PermissionHelper`, `WebColor`, `InternalUtils`, `HostMask`, `HttpRequestHandler`, `AssetUtil`, `CapConfig.load*`, `JSObject.fromJSONObject`, `JSArray.from` and `PermissionState.byState`.
- `CapConfig` is immutable and its flags are properties: `config.isHTML5Mode`, `config.isLoggingEnabled`, and so on (unchanged from Java: `config.isHTML5Mode()`). `CapConfig.Builder` is unchanged.
- `Logger.config` and `Logger.init(config)` are replaced by `Logger.loggingEnabled`. Log output goes through `LogSink`, with `AndroidLogSink` as the default.
- `JSObject` keys are non-null; passing a `null` key now throws instead of being ignored. `getInteger` and `getJSObject` take an optional default.
- `RouteProcessor` is a `fun interface`. `ProcessedRoute.isIgnoreAssetPath()` is `getIgnoreAssetPath()` from Java.
- No longer public: `JSExport`, `JSInjector`, `UriMatcher`, `AndroidProtocolHandler`, `MimeType`, `InvalidPluginException`, `InvalidPluginMethodException`, `FileUtils.readFileFromAssets`, `FileUtils.readFileFromDisk`, `HttpRequestHandler.isOneOf` and the `PluginConfig` constructor. `PluginInvocationException` and `JSExportException` are gone entirely: nothing threw either one, and `JSExport.getBridgeJS` no longer declares a checked exception.
- Removed deprecated API: `saveCall`, `freeSavedCall`, `getSavedCall`, `getConfigValue`, `hasDefinedPermissions`, `hasDefinedRequiredPermissions`, `hasPermission`, `hasRequiredPermissions`, `pluginRequestAllPermissions`, `pluginRequestPermission`, `pluginRequestPermissions`, `handleRequestPermissionsResult`, `handleOnActivityResult` and `startActivityForResult(call, intent, int)` on `Plugin`; `hasOption`, `save`, `isSaved` and `isReleased` on `PluginCall`; the public `CapConfig` constructor and its untyped getters; `Bridge.CAPACITOR_HTTPS_INTERCEPTOR_START`, `Bridge.isDeployDisabled`, `Bridge.shouldKeepRunning` and the public `Bridge` constructor (use `Bridge.Builder`); `PathHandler.getResponseHeaders`, `PathHandler.getCharset` and the `charset` constructor parameter, and the protected `PathHandler.mimeType` field.
