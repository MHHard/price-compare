package com.mahao.teapricecompare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BundleCalculatorTest {

    @Test
    fun choosesCombinationWithSmallestOvershootThenFewestItems() {
        val candidates = listOf(
            ProductCandidate("椰果", 2.0, isAddable = true),
            ProductCandidate("芝士蛋糕", 6.0, isAddable = true),
            ProductCandidate("奶茶", 9.0, isAddable = true),
        )

        assertEquals(
            listOf("椰果", "芝士蛋糕"),
            BundleCalculator.choose(candidates, gap = 8.0)?.items?.map { it.name },
        )
    }

    @Test
    fun excludesTargetAndUnavailableCandidates() {
        val candidates = listOf(
            ProductCandidate("空山栀子", 12.0, isTarget = true, isAddable = true),
            ProductCandidate("套餐", 30.0, isAddable = false),
            ProductCandidate("小料", 8.0, isAddable = true),
        )

        assertEquals(listOf("小料"), BundleCalculator.choose(candidates, gap = 8.0)?.items?.map { it.name })
    }

    @Test
    fun returnsNullWhenNoCombinationReachesGap() {
        val candidates = listOf(
            ProductCandidate("空山栀子", 12.0, isTarget = true, isAddable = true),
            ProductCandidate("套餐", 30.0, isAddable = false),
        )

        assertNull(BundleCalculator.choose(candidates, gap = 8.0))
        assertNull(BundleCalculator.choose(candidates, gap = 0.0))
    }
}
