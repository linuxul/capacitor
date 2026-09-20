package com.getcapacitor

import org.json.JSONArray
import org.json.JSONException

public open class JSArray : JSONArray {
    public constructor() : super()

    public constructor(json: String) : super(json)

    public constructor(copyFrom: Collection<*>?) : super(copyFrom)

    public constructor(array: Any?) : super(array)

    /**
     * Returns the entries as a list, casting each one to [E].
     *
     * The cast is unchecked: [E] is erased, so a wrong element type surfaces as a
     * ClassCastException at the call site, not here.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <E> toList(): List<E> {
        val items = ArrayList<E>()
        for (i in 0 until length()) {
            items.add(get(i) as E)
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
