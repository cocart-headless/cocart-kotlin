package com.cocart.sdk.resources

/**
 * The sort orders CoCart accepts for products. Sort by price with [PRICE_ASC] or
 * [PRICE_DESC]: CoCart rejects `price`. [RELEVANCE] and [RAND] need CoCart Starter.
 */
enum class ProductOrderBy(val value: String) {
    DATE("date"),
    ID("id"),
    MENU_ORDER("menu_order"),
    INCLUDE("include"),
    TITLE("title"),
    SLUG("slug"),
    NAME("name"),
    ALPHABETICAL("alphabetical"),
    REVERSE_ALPHA("reverse_alpha"),
    POPULARITY("popularity"),
    SALES("sales"),
    RATING("rating"),
    REVIEW_COUNT("review_count"),
    BY_STOCK("by_stock"),
    ON_SALE_FIRST("on_sale_first"),
    FEATURED_FIRST("featured_first"),
    PRICE_ASC("price_asc"),
    PRICE_DESC("price_desc"),
    RELEVANCE("relevance"),
    RAND("rand"),
}

/** Sort direction. */
enum class SortOrder(val value: String) {
    ASC("asc"),
    DESC("desc"),
}

/** A product's stock status. */
enum class StockStatus(val value: String) {
    IN_STOCK("instock"),
    OUT_OF_STOCK("outofstock"),
    ON_BACKORDER("onbackorder"),
}
