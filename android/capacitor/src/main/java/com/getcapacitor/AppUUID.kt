package com.getcapacitor

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.UUID

object AppUUID {
    private const val KEY = "CapacitorAppUUID"

    @JvmStatic
    @Throws(Exception::class)
    fun getAppUUID(activity: AppCompatActivity): String? {
        assertAppUUID(activity)
        return readUUID(activity)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun regenerateAppUUID(activity: AppCompatActivity) {
        try {
            val uuid = generateUUID()
            writeUUID(activity, uuid)
        } catch (ex: NoSuchAlgorithmException) {
            throw Exception("Capacitor App UUID could not be generated.")
        }
    }

    @Throws(Exception::class)
    private fun assertAppUUID(activity: AppCompatActivity) {
        val uuid = readUUID(activity)
        if (uuid == "") {
            regenerateAppUUID(activity)
        }
    }

    @Throws(NoSuchAlgorithmException::class)
    private fun generateUUID(): String {
        val salt = MessageDigest.getInstance("SHA-256")
        salt.update(UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8))
        return bytesToHex(salt.digest())
    }

    private fun readUUID(activity: AppCompatActivity): String? {
        val sharedPref = activity.getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getString(KEY, "")
    }

    private fun writeUUID(activity: AppCompatActivity, uuid: String) {
        val sharedPref = activity.getPreferences(Context.MODE_PRIVATE)
        val editor = sharedPref.edit()
        editor.putString(KEY, uuid)
        editor.apply()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexArray = "0123456789ABCDEF".toByteArray(StandardCharsets.US_ASCII)
        val hexChars = ByteArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars, StandardCharsets.UTF_8)
    }
}
