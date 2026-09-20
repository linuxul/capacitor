package com.getcapacitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JSObjectTest {

    @Test
    fun getStringReturnsNull_WhenJSObject_IsConstructed_WithNoInitialJSONObject() {
        val jsObject = JSObject()

        val actualValue = jsObject.getString("should be null")

        assertNull(actualValue)
    }

    @Test
    fun getStringReturnsExpectedValue_WhenJSObject_IsConstructed_WithAValidJSONObject() {
        val jsObject = JSObject("{\"thisKeyExists\": \"this is the key value\"}")

        val expectedValue = jsObject.getString("thisKeyExists")
        val actualValue = "this is the key value"

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getStringReturnsDefaultValue_WhenJSObject_IsConstructed_WithNoInitialJSONObject() {
        val jsObject = JSObject()

        val expectedValue = jsObject.getString("thisKeyDoesNotExist", "default value")
        val actualValue = "default value"

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getStringReturnsDefaultValue_WhenJSObject_IsConstructed_WithAValueAsInteger() {
        val jsObject = JSObject("{\"thisKeyExists\": 1}")

        val expectedValue = jsObject.getString("thisKeyExists", "default value")
        val actualValue = "default value"

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getIntegerReturnsNull_WhenJSObject_IsConstructed_WithNoInitialJSONObject() {
        val jsObject = JSObject()

        val actualValue = jsObject.getInteger("should be null")

        assertNull(actualValue)
    }

    @Test
    fun getIntegerReturnsExpectedValue_WhenJSObject_IsConstructed_WithAValidJSONObject() {
        val jsObject = JSObject("{\"thisKeyExists\": 1}")

        val expectedValue = jsObject.getInteger("thisKeyExists")
        val actualValue = 1

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getIntegerReturnsDefaultValue_WhenJSObject_IsConstructed_WithNoInitialJSONObject() {
        val jsObject = JSObject()

        val expectedValue = jsObject.getInteger("thisKeyDoesNotExist", 1)
        val actualValue = 1

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getStringReturnsDefaultValue_WhenJSObject_IsConstructed_WithAValueAsString() {
        val jsObject = JSObject("{\"thisKeyExists\": \"not an integer\"}")

        val expectedValue = jsObject.getInteger("thisKeyExists", 1)
        val actualValue = 1

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getBoolReturnsNull_WhenJSObject_IsConstructed_WithNoInitialJSONObject() {
        val jsObject = JSObject()

        val actualValue = jsObject.getBool("should be null")

        assertNull(actualValue)
    }

    @Test
    fun getBoolReturnsExpectedValue_WhenJSObject_IsConstructed_WithAValidJSONObject() {
        val jsObject = JSObject("{\"thisKeyExists\": true}")

        val expectedValue = jsObject.getBool("thisKeyExists")
        val actualValue = true

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getBooleanReturnsDefaultValue_WhenJSObject_IsConstructed_WithNoInitialJSONObject() {
        val jsObject = JSObject()

        val expectedValue = jsObject.getBoolean("thisKeyDoesNotExist", true)
        val actualValue = true

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getBooleanReturnsDefaultValue_WhenJSObject_IsConstructed_WithAValueAsString() {
        val jsObject = JSObject("{\"thisKeyExists\": \"not an integer\"}")

        val expectedValue = jsObject.getBoolean("thisKeyExists", true)
        val actualValue = true

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getJSObjectReturnsNull_WhenJSObject_IsConstructed_WithNoInitialJSONObject() {
        val jsObject = JSObject()

        val actualValue = jsObject.getJSObject("should be null")

        assertNull(actualValue)
    }

    @Test
    fun getJsObjectReturnsExpectedValue_WhenJSObject_IsConstructed_WithAValidJSONObject() {
        val jsObject = JSObject("{\"thisKeyExists\": { \"innerObjectKey\": \"innerObjectValue\" }}")

        val actualValue = jsObject.getJSObject("thisKeyExists")!!.getString("innerObjectKey")
        val expectedValue = "innerObjectValue"

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun getJSObjectReturnsDefaultValue_WhenJSObject_IsConstructed_WithNoInitialJSONObject() {
        val jsObject = JSObject()

        val actualValue =
            jsObject
                .getJSObject("thisKeyExists", JSObject("{\"thisKeyExists\": \"default string\"}"))!!
                .getString("thisKeyExists")
        val expectedValue = "default string"

        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun putBoolean_AddsValueToJSObject_UnderCorrectKey() {
        val jsObject = JSObject()
        jsObject.put("bool", true)

        val actualValue = jsObject.getBool("bool")

        assertTrue(actualValue!!)
    }

    @Test
    fun putInteger_AddsValueToJSObject_UnderCorrectKey() {
        val jsObject = JSObject()
        jsObject.put("integer", 1)

        val expectedValue = 1
        val actualValue = jsObject.getInteger("integer")

        assertEquals(actualValue, expectedValue)
    }

    @Test
    fun putLong_AddsValueToJSObject_UnderCorrectKey() {
        val jsObject = JSObject()
        jsObject.put("long", 1L)

        val expectedValue: Long? = 1L
        val actualValue: Long? = jsObject.getLong("long")

        assertEquals(actualValue, expectedValue)
    }

    @Test
    fun putDouble_AddsValueToJSObject_UnderCorrectKey() {
        val jsObject = JSObject()
        jsObject.put("double", 1.0)

        val expectedValue: Double? = 1.0
        val actualValue: Double? = jsObject.getDouble("double")

        assertEquals(actualValue, expectedValue)
    }

    @Test
    fun putString_AddsValueToJSObject_UnderCorrectKey() {
        val jsObject = JSObject()
        jsObject.put("string", "test")

        val expectedValue = "test"
        val actualValue = jsObject.getString("string")

        assertEquals(actualValue, expectedValue)
    }

    @Test
    fun putNullString_RemovesKey() {
        val jsObject = JSObject()
        jsObject.put("string", "test")
        jsObject.put("string", null as String?)

        assertFalse(jsObject.has("string"))
        assertNull(jsObject.getString("string"))
    }

    @Test
    fun putNullObject_RemovesKey() {
        val jsObject = JSObject()
        jsObject.put("object", JSObject())
        jsObject.put("object", null as Any?)

        assertFalse(jsObject.has("object"))
    }

    @Test
    fun putJSONNull_KeepsKeyAsNull() {
        val jsObject = JSObject()
        jsObject.put("value", JSONObject.NULL)

        assertTrue(jsObject.has("value"))
        assertTrue(jsObject.isNull("value"))
        assertEquals("{\"value\":null}", jsObject.toString())
    }

    @Test
    fun putBoxedInteger_AddsValueToJSObject_UnderCorrectKey() {
        val jsObject = JSObject()
        val boxed: Int? = 7
        jsObject.put("integer", boxed)

        assertEquals(7, jsObject.getInteger("integer"))
        assertEquals("{\"integer\":7}", jsObject.toString())
    }

    @Test
    fun putNestedJSObject_IsReturnedAsJSObject() {
        val child = JSObject().put("name", "child")
        val jsObject = JSObject().put("child", child)

        assertEquals("child", jsObject.getJSObject("child")!!.getString("name"))
        assertEquals("{\"child\":{\"name\":\"child\"}}", jsObject.toString())
    }

    @Test
    fun putIsChainable_AcrossOverloads() {
        val jsObject =
            JSObject()
                .put("a", true)
                .put("b", 1)
                .put("c", 2L)
                .put("d", 1.5)
                .put("e", "s")
                .put("f", "o" as Any)

        assertEquals(6, jsObject.length())
    }
}
