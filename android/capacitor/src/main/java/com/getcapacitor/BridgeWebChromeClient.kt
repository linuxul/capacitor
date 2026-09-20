package com.getcapacitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.getcapacitor.util.PermissionHelper
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom WebChromeClient handler, required for showing dialogs, confirms, etc. in our
 * WebView instance.
 *
 * The framework does not annotate these callbacks, so every reference parameter is taken as nullable.
 */
open class BridgeWebChromeClient(private val bridge: Bridge) : WebChromeClient() {
    private fun interface PermissionListener {
        fun onPermissionSelect(isGranted: Boolean)
    }

    private fun interface ActivityResultListener {
        fun onActivityResult(result: ActivityResult)
    }

    // Registered in the constructor on purpose: launchers must be registered before the owner is STARTED.
    private val permissionLauncher: ActivityResultLauncher<Array<String>>
    private val activityLauncher: ActivityResultLauncher<Intent>
    private var permissionListener: PermissionListener? = null
    private var activityListener: ActivityResultListener? = null

    init {
        val permissionCallback =
            ActivityResultCallback<Map<String, Boolean>> { isGranted ->
                val listener = permissionListener
                if (listener != null) {
                    var granted = true
                    for (permission in isGranted.entries) {
                        if (!permission.value) granted = false
                    }
                    listener.onPermissionSelect(granted)
                }
            }

        permissionLauncher = bridge.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), permissionCallback)
        activityLauncher =
            bridge.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                activityListener?.onActivityResult(result)
            }
    }

    /**
     * Render web content in `view`.
     *
     * Both this method and [onHideCustomView] are required for
     * rendering web content in full screen.
     *
     * See the [onShowCustomView() docs](https://developer.android.com/reference/android/webkit/WebChromeClient#onShowCustomView(android.view.View,%20android.webkit.WebChromeClient.CustomViewCallback)).
     */
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        callback?.onCustomViewHidden()
        super.onShowCustomView(view, callback)
    }

    /**
     * Render web content in the original Web View again.
     *
     * Do not remove this method--see [onShowCustomView].
     */
    override fun onHideCustomView() {
        super.onHideCustomView()
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return

        val permissionList = ArrayList<String>()
        if (request.resources.contains("android.webkit.resource.VIDEO_CAPTURE")) {
            permissionList.add(Manifest.permission.CAMERA)
        }
        if (request.resources.contains("android.webkit.resource.AUDIO_CAPTURE")) {
            permissionList.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            permissionList.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissionList.isNotEmpty()) {
            val permissions = permissionList.toTypedArray()
            permissionListener =
                PermissionListener { isGranted ->
                    if (isGranted) {
                        request.grant(request.resources)
                    } else {
                        request.deny()
                    }
                }
            permissionLauncher.launch(permissions)
        } else {
            request.grant(request.resources)
        }
    }

    /**
     * Show the browser alert modal
     */
    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        if (bridge.activity.isFinishing) {
            return true
        }
        // Without a view or a result there is nothing to show or answer; defer to the default handling.
        if (view == null || result == null) return false

        val builder = AlertDialog.Builder(view.context)
        builder
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                result.confirm()
            }
            .setOnCancelListener { dialog ->
                dialog.dismiss()
                result.cancel()
            }

        val dialog = builder.create()

        dialog.show()

        return true
    }

    /**
     * Show the browser confirm modal
     */
    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        if (bridge.activity.isFinishing) {
            return true
        }
        // Without a view or a result there is nothing to show or answer; defer to the default handling.
        if (view == null || result == null) return false

        val builder = AlertDialog.Builder(view.context)

        builder
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                result.confirm()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                result.cancel()
            }
            .setOnCancelListener { dialog ->
                dialog.dismiss()
                result.cancel()
            }

        val dialog = builder.create()

        dialog.show()

        return true
    }

    /**
     * Show the browser prompt modal
     */
    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
        if (bridge.activity.isFinishing) {
            return true
        }
        // Without a view or a result there is nothing to show or answer; defer to the default handling.
        if (view == null || result == null) return false

        val builder = AlertDialog.Builder(view.context)
        val input = EditText(view.context)

        builder
            .setMessage(message)
            .setView(input)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()

                // trim { it <= ' ' } is java.lang.String.trim().
                val inputText1 = input.text.toString().trim { it <= ' ' }
                result.confirm(inputText1)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                result.cancel()
            }
            .setOnCancelListener { dialog ->
                dialog.dismiss()
                result.cancel()
            }

        val dialog = builder.create()

        dialog.show()

        return true
    }

    /**
     * Handle the browser geolocation permission prompt
     */
    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        super.onGeolocationPermissionsShowPrompt(origin, callback)
        Logger.debug("onGeolocationPermissionsShowPrompt: DOING IT HERE FOR ORIGIN: $origin")
        val geoPermissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

        if (!PermissionHelper.hasPermissions(bridge.context, geoPermissions)) {
            permissionListener =
                PermissionListener { isGranted ->
                    if (isGranted) {
                        callback?.invoke(origin, true, false)
                    } else {
                        val coarsePermission = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (PermissionHelper.hasPermissions(bridge.context, coarsePermission)) {
                            callback?.invoke(origin, true, false)
                        } else {
                            callback?.invoke(origin, false, false)
                        }
                    }
                }
            permissionLauncher.launch(geoPermissions)
        } else {
            // permission is already granted
            callback?.invoke(origin, true, false)
            Logger.debug("onGeolocationPermissionsShowPrompt: has required permission")
        }
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        // Returning false tells the WebView the callback will not be invoked.
        if (filePathCallback == null || fileChooserParams == null) return false

        val acceptTypes = listOf(*fileChooserParams.acceptTypes)
        val captureEnabled = fileChooserParams.isCaptureEnabled
        val capturePhoto = captureEnabled && acceptTypes.contains("image/*")
        val captureVideo = captureEnabled && acceptTypes.contains("video/*")
        if (capturePhoto || captureVideo) {
            if (isMediaCaptureSupported()) {
                showMediaCaptureOrFilePicker(filePathCallback, fileChooserParams, captureVideo)
            } else {
                permissionListener =
                    PermissionListener { isGranted ->
                        if (isGranted) {
                            showMediaCaptureOrFilePicker(filePathCallback, fileChooserParams, captureVideo)
                        } else {
                            Logger.warn(Logger.tags("FileChooser"), "Camera permission not granted")
                            filePathCallback.onReceiveValue(null)
                        }
                    }
                val camPermission = arrayOf(Manifest.permission.CAMERA)
                permissionLauncher.launch(camPermission)
            }
        } else {
            showFilePicker(filePathCallback, fileChooserParams)
        }

        return true
    }

    private fun isMediaCaptureSupported(): Boolean {
        val permissions = arrayOf(Manifest.permission.CAMERA)
        return (
            PermissionHelper.hasPermissions(bridge.context, permissions) ||
                !PermissionHelper.hasDefinedPermission(bridge.context, Manifest.permission.CAMERA)
        )
    }

    private fun showMediaCaptureOrFilePicker(
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
        isVideo: Boolean,
    ) {
        val shown =
            if (isVideo) {
                showVideoCapturePicker(filePathCallback)
            } else {
                showImageCapturePicker(filePathCallback)
            }
        if (!shown) {
            Logger.warn(Logger.tags("FileChooser"), "Media capture intent could not be launched. Falling back to default file picker.")
            showFilePicker(filePathCallback, fileChooserParams)
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun showImageCapturePicker(filePathCallback: ValueCallback<Array<Uri>>): Boolean {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(bridge.activity.packageManager) == null) {
            return false
        }

        val imageFileUri: Uri
        try {
            imageFileUri = createImageFileUri()
        } catch (ex: Exception) {
            Logger.error("Unable to create temporary media capture file: " + ex.message)
            return false
        }
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageFileUri)
        takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activityListener =
            ActivityResultListener { activityResult ->
                var result: Array<Uri>? = null
                if (activityResult.resultCode == Activity.RESULT_OK) {
                    result = arrayOf(imageFileUri)
                }
                filePathCallback.onReceiveValue(result)
            }
        activityLauncher.launch(takePictureIntent)

        return true
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun showVideoCapturePicker(filePathCallback: ValueCallback<Array<Uri>>): Boolean {
        val takeVideoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        if (takeVideoIntent.resolveActivity(bridge.activity.packageManager) == null) {
            return false
        }

        activityListener =
            ActivityResultListener { activityResult ->
                var result: Array<Uri>? = null
                if (activityResult.resultCode == Activity.RESULT_OK) {
                    // The Java original threw on a missing result intent and reported { null } for a missing uri;
                    // both now report "no file".
                    val videoUri = activityResult.data?.data
                    if (videoUri != null) {
                        result = arrayOf(videoUri)
                    }
                }
                filePathCallback.onReceiveValue(result)
            }
        activityLauncher.launch(takeVideoIntent)

        return true
    }

    private fun showFilePicker(filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams) {
        val intent = fileChooserParams.createIntent()
        if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        // The Java original threw on an intent without a type; that case now counts as "does not start with a dot".
        val typeStartsWithDot = intent.type?.startsWith(".") == true
        if (fileChooserParams.acceptTypes.size > 1 || typeStartsWithDot) {
            val validTypes = getValidTypes(fileChooserParams.acceptTypes)
            intent.putExtra(Intent.EXTRA_MIME_TYPES, validTypes)
            if (typeStartsWithDot) {
                intent.type = validTypes[0]
            }
        }
        try {
            activityListener =
                ActivityResultListener { activityResult ->
                    val result: Array<Uri>?
                    val resultIntent = activityResult.data
                    val clipData = resultIntent?.clipData
                    if (activityResult.resultCode == Activity.RESULT_OK && clipData != null) {
                        val numFiles = clipData.itemCount
                        result = Array(numFiles) { i -> clipData.getItemAt(i).uri }
                    } else {
                        result = FileChooserParams.parseResult(activityResult.resultCode, resultIntent)
                    }
                    filePathCallback.onReceiveValue(result)
                }
            activityLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            filePathCallback.onReceiveValue(null)
        }
    }

    private fun getValidTypes(currentTypes: Array<String>): Array<String> {
        val validTypes = ArrayList<String>()
        val mtm = MimeTypeMap.getSingleton()
        for (mime in currentTypes) {
            if (mime.startsWith(".")) {
                val extension = mime.substring(1)
                val extensionMime = mtm.getMimeTypeFromExtension(extension)
                if (extensionMime != null && !validTypes.contains(extensionMime)) {
                    validTypes.add(extensionMime)
                }
            } else if (!validTypes.contains(mime)) {
                validTypes.add(mime)
            }
        }
        return validTypes.toTypedArray()
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (consoleMessage == null) return true

        val tag = Logger.tags("Console")
        val message: String? = consoleMessage.message()
        if (message != null && isValidMsg(message)) {
            // The FORMAT default locale is what String.format(String, ...) used implicitly.
            val msg =
                String.format(
                    Locale.getDefault(Locale.Category.FORMAT),
                    "File: %s - Line %d - Msg: %s",
                    consoleMessage.sourceId(),
                    consoleMessage.lineNumber(),
                    consoleMessage.message(),
                )
            val level = consoleMessage.messageLevel().name
            if ("ERROR".equals(level, ignoreCase = true)) {
                Logger.error(tag, msg, null)
            } else if ("WARNING".equals(level, ignoreCase = true)) {
                Logger.warn(tag, msg)
            } else if ("TIP".equals(level, ignoreCase = true)) {
                Logger.debug(tag, msg)
            } else {
                Logger.info(tag, msg)
            }
        }
        return true
    }

    open fun isValidMsg(msg: String): Boolean =
        !(msg.contains("%cresult %c") || msg.contains("%cnative %c") || msg.equals("console.groupEnd", ignoreCase = true))

    @Throws(IOException::class)
    private fun createImageFileUri(): Uri {
        val activity: Activity = bridge.activity
        val photoFile = createImageFile(activity)
        return FileProvider.getUriForFile(activity, bridge.context.packageName + ".fileprovider", photoFile)
    }

    @Throws(IOException::class)
    private fun createImageFile(activity: Activity): File {
        // Create an image file name
        // The FORMAT default locale is what the single-argument SimpleDateFormat constructor used implicitly.
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault(Locale.Category.FORMAT)).format(Date())
        val imageFileName = "JPEG_" + timeStamp + "_"
        val storageDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }
}
