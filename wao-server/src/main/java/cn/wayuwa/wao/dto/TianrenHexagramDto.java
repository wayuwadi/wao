package cn.wayuwa.wao.dto;

/** 文王卦序中的一卦及其上下卦结构。 */
public record TianrenHexagramDto(
        int number,
        String name,
        String symbol,
        TianrenTrigramDto upper,
        TianrenTrigramDto lower) {
}
