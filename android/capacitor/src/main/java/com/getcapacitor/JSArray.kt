package com.getcapacitor

import org.json.JSONArray
import org.json.JSONException

open class JSArray : JSONArray {
    constructor() : super()

    @Throws(JSONException::class)
    constructor(json: String) : super(json)

    constructor(copyFrom: Collection<*>?) : super(copyFrom)

    @Throws(JSONException::class)
    constructor(array: Any?) : super(array)

    @Suppress("UNCHECKED_CAST")
    @Throws(JSONException::class)
    fun <E> toList(): MutableList<E> {
        val items = ArrayList<E>()
        for (i in 0 until length()) {
            val o = get(i)
            try {
                items.add(o as E)
            } catch (ex: Exception) {
                throw JSONException("Not all items are instances of the given type")
            }
        }
        return items
    }

    companion object {
        /**
         * Create a new JSArray without throwing a error
         */
        @JvmStatic
        fun from(array: Any?): JSArray? {
            try {
                return JSArray(array)
            } catch (ex: JSONException) {
            }
            return null
        }
    }
}
