package com.getcapacitor

import org.json.JSONArray
import org.json.JSONException

public open class JSArray : JSONArray {
    public constructor() : super()

    public constructor(json: String) : super(json)

    public constructor(copyFrom: Collection<*>?) : super(copyFrom)

    public constructor(array: Any?) : super(array)

    @Suppress("UNCHECKED_CAST")
    public fun <E> toList(): MutableList<E> {
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

    public companion object {
        /**
         * Create a new JSArray without throwing a error
         */
        public fun from(array: Any?): JSArray? {
            try {
                return JSArray(array)
            } catch (ex: JSONException) {
            }
            return null
        }
    }
}
