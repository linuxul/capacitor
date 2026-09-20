package android.text

// Shadows android.text.TextUtils on the unit test classpath; the runtime calls these as JVM statics.
object TextUtils {
    @JvmStatic
    fun join(delimiter: CharSequence?, tokens: Iterable<*>): String {
        val sb = StringBuilder()
        val it = tokens.iterator()
        if (it.hasNext()) {
            sb.append(it.next())
            while (it.hasNext()) {
                sb.append(delimiter)
                sb.append(it.next())
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun isEmpty(str: CharSequence?): Boolean = str == null || str.length == 0
}
