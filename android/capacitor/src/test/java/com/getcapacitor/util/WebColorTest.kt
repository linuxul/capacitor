package com.getcapacitor.util

import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any

/**
 * Parsing the `#RRGGBB` and `#RRGGBBAA` colors of the config and of plugin options.
 */
class WebColorTest {
    private lateinit var color: MockedStatic<Color>
    private val parsed = ArrayList<String>()

    @Before
    fun setUp() {
        // Color.parseColor is Android's; record what it is asked to parse.
        color = mockStatic(Color::class.java)
        color.`when`<Int> { Color.parseColor(any()) }.thenAnswer {
            parsed.add(it.getArgument(0))
            0
        }
    }

    @After
    fun tearDown() {
        color.close()
    }

    @Test
    fun anEmptyStringIsInvalid() {
        // It threw a StringIndexOutOfBoundsException, which callers that catch IllegalArgumentException, such as the
        // bridge applying an empty backgroundColor, did not catch.
        assertThrows(IllegalArgumentException::class.java) { WebColor.parseColor("") }
    }

    @Test
    fun colorsOfOtherLengthsAreInvalid() {
        for (value in listOf("#", "#fff", "12345", "#1234567", "#1234567890")) {
            assertThrows(value, IllegalArgumentException::class.java) { WebColor.parseColor(value) }
        }
        assertEquals(emptyList<String>(), parsed)
    }

    @Test
    fun rgbIsParsedAsItIs() {
        WebColor.parseColor("#336699")
        WebColor.parseColor("336699")

        assertEquals(listOf("#336699", "#336699"), parsed)
    }

    @Test
    fun rgbaIsReorderedToAndroidsArgb() {
        WebColor.parseColor("#33669980")

        assertEquals(listOf("#80336699"), parsed)
    }
}
