package com.alhaq.amniquest.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OneShotToolsTest {

    @Test
    fun padTokenIds_emptyInput_returnsEosFollowedByPadding() {
        val result = padTokenIds(intArrayOf(), maxLength = 64, padTokenId = 0L, eosTokenId = 1L)
        assertEquals(64, result.size)
        assertEquals(1L, result[0]) // EOS token at first position
        for (i in 1 until 64) {
            assertEquals("Expected padding at index $i", 0L, result[i])
        }
    }

    @Test
    fun padTokenIds_shortInputWithoutEos_appendsEosAndPads() {
        val input = intArrayOf(101, 202, 303)
        val result = padTokenIds(input, maxLength = 8, padTokenId = 0L, eosTokenId = 1L)
        assertEquals(8, result.size)
        assertArrayEquals(
            longArrayOf(101L, 202L, 303L, 1L, 0L, 0L, 0L, 0L),
            result
        )
    }

    @Test
    fun padTokenIds_inputAlreadyEndingWithEos_doesNotDuplicateEos() {
        val input = intArrayOf(101, 202, 1) // 1 is EOS
        val result = padTokenIds(input, maxLength = 6, padTokenId = 0L, eosTokenId = 1L)
        assertEquals(6, result.size)
        assertArrayEquals(
            longArrayOf(101L, 202L, 1L, 0L, 0L, 0L),
            result
        )
    }

    @Test
    fun padTokenIds_inputLengthEqualsMaxLengthMinusOne_placesEosAtLastIndex() {
        val input = IntArray(63) { it + 10 }
        val result = padTokenIds(input, maxLength = 64, padTokenId = 0L, eosTokenId = 1L)
        assertEquals(64, result.size)
        for (i in 0 until 63) {
            assertEquals((i + 10).toLong(), result[i])
        }
        assertEquals(1L, result[63])
    }

    @Test
    fun padTokenIds_inputLongerThanMaxLength_truncatesAndEndsWithEos() {
        val input = IntArray(100) { it + 5 }
        val result = padTokenIds(input, maxLength = 64, padTokenId = 0L, eosTokenId = 1L)
        assertEquals(64, result.size)
        for (i in 0 until 63) {
            assertEquals((i + 5).toLong(), result[i])
        }
        assertEquals(1L, result[63])
    }

    @Test
    fun padTokenIds_inputExactlyMaxLengthEndingWithEos_preservesAll() {
        val input = IntArray(64) { it + 2 }
        input[63] = 1 // Set last to EOS
        val result = padTokenIds(input, maxLength = 64, padTokenId = 0L, eosTokenId = 1L)
        assertEquals(64, result.size)
        for (i in 0 until 63) {
            assertEquals((i + 2).toLong(), result[i])
        }
        assertEquals(1L, result[63])
    }

    @Test
    fun padTokenIds_defaultParameters_matchGemmaSiglipSpec() {
        val input = intArrayOf(50, 60)
        val result = padTokenIds(input)
        assertEquals(64, result.size)
        assertEquals(50L, result[0])
        assertEquals(60L, result[1])
        assertEquals(1L, result[2]) // Default EOS = 1L
        assertEquals(0L, result[3]) // Default PAD = 0L
        assertEquals(0L, result[63])
    }

    @Test(expected = IllegalArgumentException::class)
    fun padTokenIds_zeroMaxLength_throwsIllegalArgumentException() {
        padTokenIds(intArrayOf(1, 2, 3), maxLength = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun padTokenIds_negativeMaxLength_throwsIllegalArgumentException() {
        padTokenIds(intArrayOf(1, 2, 3), maxLength = -5)
    }

    @Test
    fun padTokenIds_customPadAndEos_appliesCorrectly() {
        val input = intArrayOf(42)
        val result = padTokenIds(input, maxLength = 4, padTokenId = 999L, eosTokenId = 777L)
        assertArrayEquals(longArrayOf(42L, 777L, 999L, 999L), result)
    }

    @Test
    fun padTokenIds_singleEosOnlyInput_preservesWithoutDuplicate() {
        val input = intArrayOf(1)
        val result = padTokenIds(input, maxLength = 4, padTokenId = 0L, eosTokenId = 1L)
        assertArrayEquals(longArrayOf(1L, 0L, 0L, 0L), result)
    }

    @Test
    fun padTokenIds_intOverloadAndEdgeLengths_behaveCorrectly() {
        val intOverloadResult = padTokenIds(intArrayOf(7, 8), maxLength = 5, padTokenId = 0, eosTokenId = 1)
        assertArrayEquals(longArrayOf(7L, 8L, 1L, 0L, 0L), intOverloadResult)

        val singleSlot = padTokenIds(intArrayOf(42, 43), maxLength = 1, padTokenId = 0L, eosTokenId = 1L)
        assertArrayEquals(longArrayOf(1L), singleSlot)

        val exactWithoutEos = IntArray(4) { it + 10 }
        val truncatedForEos = padTokenIds(exactWithoutEos, maxLength = 4, padTokenId = 0L, eosTokenId = 1L)
        assertArrayEquals(longArrayOf(10L, 11L, 12L, 1L), truncatedForEos)

        val eosInMiddle = intArrayOf(10, 1, 20)
        val preservedWithTrailingEos = padTokenIds(eosInMiddle, maxLength = 5, padTokenId = 0L, eosTokenId = 1L)
        assertArrayEquals(longArrayOf(10L, 1L, 20L, 1L, 0L), preservedWithTrailingEos)
    }

    @Test
    fun normalizeArgbPixelsToChw_producesExpectedPlanarNormalizedValues() {
        // 2x2 image: [Black, White, Pure Red, Pure Green]
        val pixels = intArrayOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt()
        )
        val chw = normalizeArgbPixelsToChw(pixels, width = 2, height = 2)
        assertEquals(12, chw.size)

        // R plane (indices 0..3): black=-1, white=+1, red=+1, green=-1
        assertArrayEquals(floatArrayOf(-1f, 1f, 1f, -1f), chw.copyOfRange(0, 4), 1e-5f)
        // G plane (indices 4..7): black=-1, white=+1, red=-1, green=+1
        assertArrayEquals(floatArrayOf(-1f, 1f, -1f, 1f), chw.copyOfRange(4, 8), 1e-5f)
        // B plane (indices 8..11): black=-1, white=+1, red=-1, green=-1
        assertArrayEquals(floatArrayOf(-1f, 1f, -1f, -1f), chw.copyOfRange(8, 12), 1e-5f)

        // Mid-gray channel 0x80 = 128 -> ((128/255) - 0.5) / 0.5
        val expectedMid = ((128f / 255f) - 0.5f) / 0.5f
        val blueAndGray = normalizeArgbPixelsToChw(
            pixels = intArrayOf(0xFF0000FF.toInt(), 0xFF808080.toInt()),
            width = 2,
            height = 1
        )
        assertEquals(-1f, blueAndGray[0], 1e-5f) // R of blue
        assertEquals(expectedMid, blueAndGray[1], 1e-5f) // R of gray
        assertEquals(1f, blueAndGray[4], 1e-5f) // B of blue
        assertEquals(expectedMid, blueAndGray[5], 1e-5f) // B of gray
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeArgbPixelsToChw_insufficientPixelArray_throwsIllegalArgumentException() {
        normalizeArgbPixelsToChw(intArrayOf(0, 0), width = 2, height = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeArgbPixelsToChw_nonPositiveStd_throwsIllegalArgumentException() {
        normalizeArgbPixelsToChw(intArrayOf(0), width = 1, height = 1, std = 0f)
    }

    @Test
    fun sentencePieceProcessor_handlesMissingNativeLibraryGracefullyOnJvm() {
        assertFalse(SentencePieceProcessor.isModelLoaded())
        assertEquals(-1, SentencePieceProcessor.loadModel("/nonexistent/tokenizer.model"))
        assertFalse(SentencePieceProcessor.isModelLoaded())
    }
}
