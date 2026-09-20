package com.getcapacitor

import java.util.Locale

/**
 * Represents the state of a permission
 *
 * @since 3.0.0
 */
enum class PermissionState(private val state: String) {
    GRANTED("granted"),
    DENIED("denied"),
    PROMPT("prompt"),
    PROMPT_WITH_RATIONALE("prompt-with-rationale"),
    ;

    override fun toString(): String = state

    companion object {
        @JvmStatic
        fun byState(state: String): PermissionState = valueOf(state.uppercase(Locale.ROOT).replace('-', '_'))
    }
}
