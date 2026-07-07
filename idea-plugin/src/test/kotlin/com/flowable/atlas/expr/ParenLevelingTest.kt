package com.flowable.atlas.expr

import org.junit.Assert.assertEquals
import org.junit.Test

class ParenLevelingTest {

    private fun levels(text: String) = ParenLeveling.levels(text).map { it.level }

    @Test
    fun flatPairIsLevelZero() {
        // "test && (test2)" — the single pair is level 0.
        assertEquals(listOf(0, 0), levels("test && (test2)"))
    }

    @Test
    fun matchingPairsShareLevelAndNestingStepsUp() {
        // "((a)+(b))" parens in order: ( ( ) ( ) )
        assertEquals(listOf(0, 1, 1, 1, 1, 0), levels("((a)+(b))"))
    }

    @Test
    fun deepNestingStepsUpThenDown() {
        // "((((x))))" → levels rise 0..3 on the way in and mirror back out; the annotator maps
        // level % paletteSize to a colour, so level 5 would reuse colour 0.
        assertEquals(listOf(0, 1, 2, 3, 3, 2, 1, 0), levels("((((x))))"))
    }

    @Test
    fun unmatchedCloseDoesNotGoNegative() {
        assertEquals(listOf(0, 0, 0), levels(")()"))
    }
}
