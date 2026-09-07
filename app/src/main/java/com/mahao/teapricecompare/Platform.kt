package com.mahao.teapricecompare

enum class Platform(val packageName: String, val displayName: String) {
    JD("com.jingdong.app.mall", "京东"),
    MEITUAN("com.sankuai.meituan", "美团券"),
    MEITUAN_DELIVERY("com.sankuai.meituan", "美团外卖"),
    MEITUAN_PICKUP("com.sankuai.meituan", "美团自取");

    companion object {
        fun fromPackageName(packageName: String): Platform? =
            entries.firstOrNull { it.packageName == packageName }
    }
}
