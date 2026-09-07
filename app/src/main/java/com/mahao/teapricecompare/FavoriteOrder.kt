package com.mahao.teapricecompare

/** One platform's search inputs needed to locate the same drink there. */
data class PlatformTarget(
    val storeKeyword: String,
    val productKeyword: String,
)

/** A saved "store + drink" combo the user wants to re-compare across platforms. */
data class FavoriteOrder(
    val id: String,
    val name: String,
    val targets: Map<Platform, PlatformTarget>,
    val lastComparison: ComparisonSnapshot? = null,
)
