package android.util

// Shadows android.util.Log on the unit test classpath; the runtime calls these as JVM statics.
object Log {
    @JvmStatic
    fun d(tag: String?, msg: String?): Int {
        println("DEBUG: $tag: $msg")
        return 0
    }

    @JvmStatic
    fun i(tag: String?, msg: String?): Int {
        println("INFO: $tag: $msg")
        return 0
    }

    @JvmStatic
    fun w(tag: String?, msg: String?): Int {
        println("WARN: $tag: $msg")
        return 0
    }

    @JvmStatic
    fun e(tag: String?, msg: String?): Int {
        println("ERROR: $tag: $msg")
        return 0
    }

    @JvmStatic
    fun v(tag: String?, msg: String?): Int {
        println("VERBOSE: $tag: $msg")
        return 0
    }
}
