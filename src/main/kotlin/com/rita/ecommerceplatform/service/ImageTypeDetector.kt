package com.rita.ecommerceplatform.service

/** The image types products may have. SVG is not one: it can carry scripts. */
enum class ImageType(val contentType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
}

/**
 * Tells an image's real type from its first bytes (its "magic number"), never from the file name or the
 * Content-Type the client sent, so a renamed file of another kind is recognised for what it is.
 */
object ImageTypeDetector {

    private val JPEG_START = bytes(0xFF, 0xD8, 0xFF)
    private val PNG_START = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    /** The type, or null if it is none of the allowed ones. */
    fun detect(content: ByteArray): ImageType? = when {
        content.startsWith(JPEG_START) -> ImageType.JPEG
        content.startsWith(PNG_START) -> ImageType.PNG
        // RIFF <4-byte size> WEBP
        content.startsWith(RIFF) && content.size >= 12 && content.copyOfRange(8, 12).contentEquals(WEBP) -> ImageType.WEBP
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
