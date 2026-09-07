package com.mahao.teapricecompare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeituanSelectorsTest {

    @Test
    fun resultListBelongsToMeituanSearchResults() {
        assertTrue(
            MeituanSelectors.isSearchResultList(
                packageName = "com.sankuai.meituan",
                resourceId = "com.sankuai.meituan:id/f2c",
            ),
        )
        assertFalse(
            MeituanSelectors.isSearchResultList(
                packageName = "com.jingdong.app.mall",
                resourceId = "com.sankuai.meituan:id/f2c",
            ),
        )
        assertFalse(
            MeituanSelectors.isSearchResultList(
                packageName = "com.sankuai.meituan",
                resourceId = "com.sankuai.meituan:id/search_edit_text",
            ),
        )
    }

    @Test
    fun deliveryResultPageUsesItsOwnList() {
        assertTrue(
            MeituanSelectors.isDeliverySearchResult(
                packageName = "com.sankuai.meituan",
                resourceId = "com.sankuai.meituan:id/s9d",
            ),
        )
        assertTrue(
            MeituanSelectors.isDeliverySearchResult(
                packageName = "com.sankuai.meituan",
                resourceId = "com.sankuai.meituan:id/result_view_pager",
            ),
        )
        assertFalse(
            MeituanSelectors.isDeliverySearchResult(
                packageName = "com.sankuai.meituan",
                resourceId = "com.sankuai.meituan:id/f2c",
            ),
        )
    }

    @Test
    fun generalHomeSearchIsNotDeliveryHome() {
        assertTrue(
            MeituanSelectors.isDeliverySearchHomeMarker(
                resourceId = "com.sankuai.meituan:id/homepage_search_icon",
                contentDescription = "搜索框",
            ),
        )
        assertFalse(
            MeituanSelectors.isDeliverySearchHomeMarker(
                resourceId = "com.sankuai.meituan:id/search_layout_area",
                contentDescription = "芝士意面 搜索框，点击可搜索",
            ),
        )
        assertFalse(
            MeituanSelectors.isDeliverySearchHomeMarker(
                resourceId = null,
                contentDescription = "搜索框",
            ),
        )
    }

    @Test
    fun storePageHasStoreTitleAndProductTab() {
        assertTrue(
            MeituanSelectors.isStorePage(
                texts = listOf("蜜雪冰城（川图路店）0", "定商品", "商家推荐"),
                storeKeyword = "蜜雪冰城",
            ),
        )
        assertFalse(
            MeituanSelectors.isStorePage(
                texts = listOf("蜜雪冰城(川图路店)", "芋圆葡萄（特价团）"),
                storeKeyword = "蜜雪冰城",
            ),
        )
    }

    @Test
    fun deliveryEntryMatchesOnlyTheExactLabel() {
        assertTrue(MeituanSelectors.isDeliveryEntryLabel("外卖", null))
        assertTrue(MeituanSelectors.isDeliveryEntryLabel(null, "外卖"))
        assertFalse(MeituanSelectors.isDeliveryEntryLabel("外卖红包", null))
        assertFalse(MeituanSelectors.isDeliveryEntryLabel("美团外卖", null))
    }

    @Test
    fun mainDeliveryEntryRequiresClickableChannelView() {
        assertTrue(MeituanSelectors.isMainDeliveryEntryCandidate(null, "外卖", true, 381, 603))
        assertFalse(MeituanSelectors.isMainDeliveryEntryCandidate("外卖", null, false, 381, 603))
        assertFalse(MeituanSelectors.isMainDeliveryEntryCandidate("外卖", null, true, 2184, 2328))
    }

    @Test
    fun deliveryStorePageUsesDishTabInsteadOfVoucherMarker() {
        assertTrue(
            MeituanSelectors.isDeliveryStorePage(
                texts = listOf("蜜雪冰城(川图路店)", "点菜", "冰鲜柠檬水"),
                storeKeyword = "蜜雪冰城",
            ),
        )
        assertFalse(
            MeituanSelectors.isDeliveryStorePage(
                texts = listOf("蜜雪冰城(川图路店)", "定商品", "冰鲜柠檬水"),
                storeKeyword = "蜜雪冰城",
            ),
        )
    }

    @Test
    fun deliveryCandidateMatchesLoadedStoreTitle() {
        assertTrue(
            MeituanSelectors.isDeliveryStoreCandidate(
                text = "霸王茶姬（上海川沙96广场店）",
                storeKeyword = "霸王茶姬",
                top = 1083,
                width = 592,
                height = 61,
            ),
        )
        assertFalse(
            MeituanSelectors.isDeliveryStoreCandidate(
                text = "霸王茶姬",
                storeKeyword = "霸王茶姬",
                top = 300,
                width = 592,
                height = 61,
            ),
        )
    }

    @Test
    fun specPopupMatchesCapturedSelectedSpecAndCloseMarker() {
        assertTrue(MeituanSelectors.isSpecPopupMarker("已选规格：标准、正常冰、七分糖(推荐)", null))
        assertTrue(MeituanSelectors.isSpecPopupMarker(null, "关闭选规格"))
        assertFalse(MeituanSelectors.isSpecPopupMarker("选规格", null))
        assertTrue(MeituanSelectors.isSpecAddToCart("加入购物车", null))
        assertFalse(MeituanSelectors.isSpecAddToCart("关闭", null))
    }

    @Test
    fun minimumOrderTextIsDetected() {
        assertTrue(MeituanSelectors.isMinimumOrderText("差¥8.2起送"))
        assertTrue(MeituanSelectors.isMinimumOrderText("¥18起送"))
        assertFalse(MeituanSelectors.isMinimumOrderText("去结算"))
    }

    @Test
    fun cartDrawerMatchesCapturedAddedItemsPage() {
        assertTrue(MeituanSelectors.isCartDrawerMarker("已加购商品", null))
        assertTrue(MeituanSelectors.isCartDrawerMarker(null, "已加购商品"))
        assertFalse(MeituanSelectors.isCartDrawerMarker("购物车", null))
        assertTrue(MeituanSelectors.isDeliveryDrawerTab("外送", 230, 1235))
        assertTrue(MeituanSelectors.isPickupDrawerTab("自取", 764, 1235))
        assertFalse(MeituanSelectors.isDeliveryDrawerTab("外送", 144, 156))
        assertTrue(MeituanSelectors.isCartSummaryMarker("明细", null))
        assertTrue(MeituanSelectors.isCartSummaryMarker("到手约¥4.78", null))
        assertFalse(MeituanSelectors.isCartSummaryMarker("商品详情", null))
    }

    @Test
    fun merchantDistanceIsExtractedFromStoreText() {
        assertEquals("距您 904m", MeituanSelectors.extractMerchantDistance(listOf("美团快送", "距您 904m")))
        assertEquals("距您 1.2公里", MeituanSelectors.extractMerchantDistance(listOf("距您 1.2 公里")))
    }

    @Test
    fun cartRowRequiresProductQuantityMinusAndKnownStore() {
        assertTrue(
            MeituanSelectors.isCartProductRow(
                productName = "芝芝莓莓",
                quantityText = "2",
                hasMinusControl = true,
                storeName = "喜茶人民广场店",
                expectedStoreKeyword = "喜茶",
            ),
        )
        assertFalse(
            MeituanSelectors.isCartProductRow(
                productName = "芝芝莓莓",
                quantityText = "2",
                hasMinusControl = true,
                storeName = "蜜雪冰城浦江店",
                expectedStoreKeyword = "喜茶",
            ),
        )
        assertFalse(
            MeituanSelectors.isCartProductRow(
                productName = "未知行",
                quantityText = "2",
                hasMinusControl = false,
                storeName = "喜茶人民广场店",
                expectedStoreKeyword = "喜茶",
            ),
        )
        assertFalse(
            MeituanSelectors.isCartProductRow(
                productName = null,
                quantityText = "2",
                hasMinusControl = true,
                storeName = "喜茶人民广场店",
                expectedStoreKeyword = "喜茶",
            ),
        )
    }

    @Test
    fun emptyCartMarkersAreRecognizedButOrdinaryCartTextIsNot() {
        assertTrue(MeituanSelectors.isEmptyCartMarker("购物车是空的", null))
        assertTrue(MeituanSelectors.isEmptyCartMarker(null, "暂无商品"))
        assertFalse(MeituanSelectors.isEmptyCartMarker("购物车", null))
    }

    @Test
    fun cartMinusControlRequiresAnExplicitDecreaseMeaning() {
        assertTrue(MeituanSelectors.isCartMinusControl("减", null, null))
        assertTrue(MeituanSelectors.isCartMinusControl(null, "减少商品", null))
        assertTrue(MeituanSelectors.isCartMinusControl(null, null, "com.sankuai.meituan:id/minus"))
        assertFalse(MeituanSelectors.isCartMinusControl("删除", null, null))
    }
}
