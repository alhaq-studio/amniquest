package com.alhaq.amniquest.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun formatSiglipPrompt_variousInputs_formatsCorrectly() {
        assertEquals("this is a photo.", OneShotTools.formatSiglipPrompt(""))
        assertEquals("this is a photo.", OneShotTools.formatSiglipPrompt("   "))
        assertEquals("this is a photo of a dog.", OneShotTools.formatSiglipPrompt("a dog"))
        assertEquals("this is a photo of a red apple.", OneShotTools.formatSiglipPrompt("  a red apple.  "))
        assertEquals("this is a photo of a clean desk.", OneShotTools.formatSiglipPrompt("this is a photo of a clean desk"))
        assertEquals("a photo of mountains.", OneShotTools.formatSiglipPrompt("a photo of mountains"))
    }

    @Test
    fun sigmoid_calibratedValues_returnsExpectedProbabilities() {
        assertEquals(0.5f, OneShotTools.sigmoid(0.0f), 1e-5f)
        org.junit.Assert.assertTrue(OneShotTools.sigmoid(2.0f) > 0.85f)
        org.junit.Assert.assertTrue(OneShotTools.sigmoid(-2.0f) < 0.15f)
    }

    @Test
    fun l2Normalize_computesUnitLengthVector() {
        val vector = floatArrayOf(3.0f, 4.0f)
        val normalized = OneShotTools.l2Normalize(vector)
        assertEquals(2, normalized.size)
        assertEquals(0.6f, normalized[0], 1e-5f)
        assertEquals(0.8f, normalized[1], 1e-5f)

        var normSq = 0.0
        for (v in normalized) normSq += (v * v)
        assertEquals(1.0, normSq, 1e-5)
    }

    @Test
    fun cosineSimilarity_unitAndOrthogonalVectors_computesCorrectSimilarity() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v3 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val v4 = floatArrayOf(-1.0f, 0.0f, 0.0f)

        assertEquals(1.0f, OneShotTools.cosineSimilarity(v1, v2), 1e-5f)
        assertEquals(0.0f, OneShotTools.cosineSimilarity(v1, v3), 1e-5f)
        assertEquals(-1.0f, OneShotTools.cosineSimilarity(v1, v4), 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cosineSimilarity_mismatchedDimensions_throwsIllegalArgumentException() {
        OneShotTools.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f))
    }

    @Test
    fun kotlinSentencePieceModel_fromEmptyBytes_returnsNull() {
        val model = KotlinSentencePieceModel.fromBytes(ByteArray(0))
        org.junit.Assert.assertNull(model)
    }

    @Test
    fun kotlinSentencePieceModel_syntheticProtobuf_encodesTokensCorrectly() {
        // Construct a minimal valid ModelProto binary protobuf:
        // field 1 (SentencePiece): piece="\u2581cat", score=-1.0f, type=NORMAL (1)
        val pieceBytes = "\u2581cat".toByteArray(Charsets.UTF_8)
        val piecePayload = java.io.ByteArrayOutputStream().apply {
            // field 1 (string piece): tag = (1 shl 3) or 2 = 10
            write(10)
            write(pieceBytes.size)
            write(pieceBytes)
            // field 2 (float score): tag = (2 shl 3) or 5 = 21
            write(21)
            val bits = java.lang.Float.floatToIntBits(-1.0f)
            write(bits and 0xFF)
            write((bits ushr 8) and 0xFF)
            write((bits ushr 16) and 0xFF)
            write((bits ushr 24) and 0xFF)
            // field 3 (type): tag = (3 shl 3) or 0 = 24
            write(24)
            write(1) // NORMAL
        }.toByteArray()

        val fullProto = java.io.ByteArrayOutputStream().apply {
            // Outer field 1: tag = (1 shl 3) or 2 = 10
            write(10)
            write(piecePayload.size)
            write(piecePayload)
        }.toByteArray()

        val model = KotlinSentencePieceModel.fromBytes(fullProto)
        org.junit.Assert.assertNotNull(model)
        val tokens = model!!.encode("cat")
        assertEquals(1, tokens.size)
        assertEquals(0, tokens[0]) // first piece has tokenId 0
    }

    @Test
    fun cleanFeatureText_metaQualifiers_removesNonVisualInstructions() {
        assertEquals(
            "a cloud in the sky",
            OneShotTools.cleanFeatureText("a cloud in the sky, any shape")
        )
        assertEquals(
            "a cloud in the sky",
            OneShotTools.cleanFeatureText("in the photo, a cloud in the sky")
        )
        assertEquals(
            "a cloud in the sky",
            OneShotTools.cleanFeatureText("a cloud in the sky (any shape)")
        )
        assertEquals(
            "a balanced rock or a rock stack",
            OneShotTools.cleanFeatureText("a balanced rock or a rock stack, clearly visible")
        )
        assertEquals(
            "a balanced rock or a rock stack",
            OneShotTools.cleanFeatureText("clearly visible, a balanced rock or a rock stack")
        )
        assertEquals(
            "a coffee mug",
            OneShotTools.cleanFeatureText("this is a photo of a coffee mug, any size, in the picture")
        )
        assertEquals(
            "red running shoes",
            OneShotTools.cleanFeatureText("red running shoes, any kind; clearly visible")
        )
        assertEquals(
            "a cat",
            OneShotTools.cleanFeatureText("photo of a cat")
        )
        assertEquals(
            "a dog",
            OneShotTools.cleanFeatureText("an image of a dog")
        )
    }

    @Test
    fun splitCompoundCandidates_disjunctionsAndSlashes_splitsIntoVisualCandidates() {
        val rockCandidates = OneShotTools.splitCompoundCandidates("a balanced rock or a rock stack")
        org.junit.Assert.assertTrue(rockCandidates.contains("a balanced rock"))
        org.junit.Assert.assertTrue(rockCandidates.contains("a rock stack"))
        org.junit.Assert.assertTrue(rockCandidates.contains("a balanced rock or a rock stack"))

        val slashCandidates = OneShotTools.splitCompoundCandidates("apple/banana/cherry")
        org.junit.Assert.assertTrue(slashCandidates.contains("apple"))
        org.junit.Assert.assertTrue(slashCandidates.contains("banana"))
        org.junit.Assert.assertTrue(slashCandidates.contains("cherry"))

        val cloudCandidates = OneShotTools.splitCompoundCandidates("a cloud in the sky, any shape")
        assertEquals(listOf("a cloud in the sky"), cloudCandidates)

        val parensCandidates = OneShotTools.splitCompoundCandidates("rock stack or (balanced rock)")
        org.junit.Assert.assertTrue(parensCandidates.contains("rock stack"))
        org.junit.Assert.assertTrue(parensCandidates.contains("balanced rock"))
    }

    @Test
    fun expandFeatureToPrompts_cloudAndRock_producesCanonicalZeroShotPrompts() {
        val cloudPrompts = OneShotTools.expandFeatureToPrompts("a cloud in the sky, any shape")
        assertEquals(
            listOf(
                "a photo of a cloud in the sky.",
                "close up photo of a cloud in the sky."
            ),
            cloudPrompts
        )

        val rockPrompts = OneShotTools.expandFeatureToPrompts("a balanced rock or a rock stack")
        org.junit.Assert.assertTrue(rockPrompts.contains("a photo of a balanced rock."))
        org.junit.Assert.assertTrue(rockPrompts.contains("close up photo of a balanced rock."))
        org.junit.Assert.assertTrue(rockPrompts.contains("a photo of a rock stack."))
        org.junit.Assert.assertTrue(rockPrompts.contains("close up photo of a rock stack."))

        // Test with "this is a picture of" prefix
        val picturePrompts = OneShotTools.expandFeatureToPrompts("this is a picture of a cloud in the sky")
        assertEquals(
            listOf(
                "a photo of a cloud in the sky.",
                "close up photo of a cloud in the sky."
            ),
            picturePrompts
        )
    }

    @Test
    fun formatSiglipPrompt_cleansAndLowercases() {
        assertEquals(
            "this is a photo of a cloud in the sky.",
            OneShotTools.formatSiglipPrompt("a cloud in the sky")
        )
        assertEquals(
            "a photo of a coffee mug.",
            OneShotTools.formatSiglipPrompt("a photo of a coffee mug")
        )
        assertEquals(
            "this is a photo of mountains.",
            OneShotTools.formatSiglipPrompt("mountains")
        )
        assertEquals(
            "this is a photo.",
            OneShotTools.formatSiglipPrompt("")
        )
    }

    @Test
    fun computeContrastiveScore_positiveAndNegativeLogits_producesExpectedProbabilities() {
        // High genuine match vs low negative baselines:
        // L_pos = -4.0, L_neg = [-8.0, -9.0, -10.0, -8.5]
        val strongLogit = -4.0f
        val negLogits = floatArrayOf(-8.0f, -9.0f, -10.0f, -8.5f)
        val strongScore = OneShotTools.computeContrastiveScore(strongLogit, negLogits, tau = 1.0f)
        org.junit.Assert.assertTrue("Expected strong score > 0.85, got $strongScore", strongScore > 0.85f)

        // Distractor / dark photo vs negative baselines:
        // L_pos = -9.5, L_neg = [-7.0, -8.0, -8.5, -7.5]
        val weakLogit = -9.5f
        val weakNegLogits = floatArrayOf(-7.0f, -8.0f, -8.5f, -7.5f)
        val weakScore = OneShotTools.computeContrastiveScore(weakLogit, weakNegLogits, tau = 1.0f)
        org.junit.Assert.assertTrue("Expected weak score < 0.20, got $weakScore", weakScore < 0.20f)

        // Equal logits: 1 positive and 4 identical negatives -> 1 / (1 + 4) = 0.20
        val equalLogit = -5.0f
        val equalNegLogits = floatArrayOf(-5.0f, -5.0f, -5.0f, -5.0f)
        val equalScore = OneShotTools.computeContrastiveScore(equalLogit, equalNegLogits, tau = 1.0f)
        assertEquals(0.20f, equalScore, 1e-4f)
    }

    @Test
    fun computeContrastiveScore_edgeCases_handlesNaNAndInfinities() {
        assertEquals(0.0f, OneShotTools.computeContrastiveScore(Float.NaN, floatArrayOf(-5.0f)), 1e-6f)
        assertEquals(0.0f, OneShotTools.computeContrastiveScore(Float.NEGATIVE_INFINITY, floatArrayOf(-5.0f)), 1e-6f)
        assertEquals(1.0f, OneShotTools.computeContrastiveScore(Float.POSITIVE_INFINITY, floatArrayOf(-5.0f)), 1e-6f)
    }

    @Test
    fun computeContrastiveScore_emptyNegatives_fallsBackToSigmoid() {
        val score0 = OneShotTools.computeContrastiveScore(0.0f, floatArrayOf())
        assertEquals(0.50f, score0, 1e-4f)

        val scorePos = OneShotTools.computeContrastiveScore(2.0f, floatArrayOf())
        assertEquals(OneShotTools.sigmoid(2.0f), scorePos, 1e-4f)
    }
}
