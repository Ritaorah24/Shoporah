package com.rita.ecommerceplatform.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Image type from the file's first bytes (checkpoint 2.2, Step 8). Plain logic: no Spring. */
class ImageTypeDetectorTest {

    @Test
    fun `JPEG, PNG and WebP are recognised from their first bytes`() {
        assertEquals(ImageType.JPEG, ImageTypeDetector.detect(TestImages.JPEG))
        assertEquals(ImageType.PNG, ImageTypeDetector.detect(TestImages.PNG))
        assertEquals(ImageType.WEBP, ImageTypeDetector.detect(TestImages.WEBP))
    }

    @Test
    fun `anything else is refused, whatever it is called`() {
        assertNull(ImageTypeDetector.detect(TestImages.GIF))
        assertNull(ImageTypeDetector.detect(TestImages.SVG))
        assertNull(ImageTypeDetector.detect("just some text, named photo.jpg".toByteArray()))
        assertNull(ImageTypeDetector.detect(ByteArray(0)))
        // Too short to be a real JPEG / WebP header.
        assertNull(ImageTypeDetector.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(ImageTypeDetector.detect("RIFF1234WAVE".toByteArray()))
    }
}

/** Small byte arrays that start like real files of each type (enough for the type check; not viewable images). */
object TestImages {
    val JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10) + "JFIF".toByteArray()
    val PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
    val WEBP = "RIFF".toByteArray() + bytes(0x24, 0x00, 0x00, 0x00) + "WEBPVP8 ".toByteArray()
    val GIF = "GIF89a".toByteArray() + bytes(0x01, 0x00)
    val SVG = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
