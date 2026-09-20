package com.getcapacitor

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.UUID

public object AppUUID {
    private const val KEY = "CapacitorAppUUID"
    private const val HEX_CHARS = "0123456789ABCDEF"

    public fun getAppUUID(activity: AppCompatActivity): String {
        assertAppUUID(activity)
        return readUUID(activity)
    }

    public fun regenerateAppUUID(activity: AppCompatActivity) {
        try {
            val uuid = generateUUID()
            writeUUID(activity, uuid)
        } catch (ex: NoSuchAlgorithmException) {
            throw Exception("Capacitor App UUID could not be generated.", ex)
        }
    }

    private fun assertAppUUID(activity: AppCompatActivity) {
        val uuid = readUUID(activity)
        if (uuid == "") {
            regenerateAppUUID(activity)
        }
    }

    private fun generateUUID(): String {
        val salt = MessageDigest.getInstance("SHA-256")
        salt.update(UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8))
        return bytesToHex(salt.digest())
    }

    private fun readUUID(activity: AppCompatActivity): String {
        val sharedPref = activity.getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getString(KEY, "") ?: ""
    }

    private fun writeUUID(activity: AppCompatActivity, uuid: String) {
        val sharedPref = activity.getPreferences(Context.MODE_PRIVATE)
        val editor = sharedPref.edit()
        editor.putString(KEY, uuid)
        editor.apply()
    }

    private fun bytesToHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX_CHARS[v ushr 4])
            append(HEX_CHARS[v and 0x0F])
        }
    }
}
