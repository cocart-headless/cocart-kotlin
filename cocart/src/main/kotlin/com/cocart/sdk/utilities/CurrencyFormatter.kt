package com.cocart.sdk.utilities

class CurrencyFormatter {
    fun format(amount: Long, currency: Map<String, Any?>): String {
        val symbol = currency["symbol"] as? String ?: "$"
        val decimals = (currency["decimals"] as? Number)?.toInt() ?: 2
        val decimalSeparator = currency["decimal_separator"] as? String ?: "."
        val thousandSeparator = currency["thousand_separator"] as? String ?: ","
        val position = currency["position"] as? String ?: "left"

        val formatted = formatDecimalString(amount, decimals, decimalSeparator, thousandSeparator)
        return when (position) {
            "right" -> "$formatted$symbol"
            "left_space" -> "$symbol $formatted"
            "right_space" -> "$formatted $symbol"
            else -> "$symbol$formatted"
        }
    }

    fun formatDecimal(amount: Long, currency: Map<String, Any?>): String {
        val decimals = (currency["decimals"] as? Number)?.toInt() ?: 2
        val decimalSeparator = currency["decimal_separator"] as? String ?: "."
        val thousandSeparator = currency["thousand_separator"] as? String ?: ","
        return formatDecimalString(amount, decimals, decimalSeparator, thousandSeparator)
    }

    private fun formatDecimalString(
        amount: Long,
        decimals: Int,
        decimalSeparator: String,
        thousandSeparator: String
    ): String {
        val divisor = Math.pow(10.0, decimals.toDouble()).toLong()
        val whole = amount / divisor
        val fraction = Math.abs(amount % divisor)

        val wholeStr = buildString {
            val wholeDigits = whole.toString()
            wholeDigits.reversed().chunked(3).joinToString(thousandSeparator) { it.reversed() }
                .also { append(it.reversed()) }
        }

        return if (decimals > 0) {
            val fractionStr = fraction.toString().padStart(decimals, '0')
            "$wholeStr$decimalSeparator$fractionStr"
        } else {
            wholeStr
        }
    }
}
