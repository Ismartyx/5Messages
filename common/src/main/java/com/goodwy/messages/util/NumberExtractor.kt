package com.goodwy.messages.util

object NumberExtractor {

    private val PERSIAN_ARABIC_DIGITS = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
    )

    private val OTP_KEYWORDS = listOf(
        "کد", "رمز", "تایید", "تأیید", "فعالسازی", "فعال سازی", "یکبار مصرف", "یکبارمصرف", "پویا", "ورود", "شناسه",
        "code", "otp", "pin", "verification", "passcode", "password", "token"
    )

    enum class NumberType {
        OTP,
        CARD,
        IBAN,
        GENERAL
    }

    data class ExtractedNumber(
        val displayValue: String,
        val copyValue: String,
        val type: NumberType
    )

    fun toAsciiDigits(str: String): String {
        val sb = StringBuilder(str.length)
        for (ch in str) {
            sb.append(PERSIAN_ARABIC_DIGITS[ch] ?: ch)
        }
        return sb.toString()
    }

    fun extract(text: String): List<ExtractedNumber> {
        if (text.isBlank()) return emptyList()

        val normalizedText = toAsciiDigits(text)
        val lowerText = normalizedText.toLowerCase()
        val hasOtpKeyword = OTP_KEYWORDS.any { lowerText.contains(it) }

        val results = mutableListOf<ExtractedNumber>()
        val seenCopyValues = mutableSetOf<String>()

        // 1. Check for Sheba / IBAN: IR + 24 digits (supports glued letters/names)
        val ibanRegex = Regex("(?<![a-zA-Z0-9])IR[0-9]{24}(?![0-9])", RegexOption.IGNORE_CASE)
        for (match in ibanRegex.findAll(normalizedText)) {
            val iban = match.value.toUpperCase()
            if (seenCopyValues.add(iban)) {
                results.add(ExtractedNumber(iban, iban, NumberType.IBAN))
            }
        }

        // 2. Check for Bank Card (16 digits, with optional spaces or dashes, supports glued letters/names)
        val cardRegex = Regex("(?<!\\d)(?:(?:\\d{4}[ -]?){3}\\d{4}|\\d{16})(?!\\d)")
        for (match in cardRegex.findAll(normalizedText)) {
            val raw = match.value
            val clean = raw.replace(" ", "").replace("-", "")
            if (clean.length == 16 && seenCopyValues.add(clean)) {
                val displayFormatted = clean.chunked(4).joinToString(" ")
                results.add(ExtractedNumber(displayFormatted, clean, NumberType.CARD))
            }
        }

        // 3. Check for standalone numbers/OTPs (4 to 12 digits, supports glued letters/words)
        val numberRegex = Regex("(?<!\\d)\\d{4,12}(?!\\d)")
        for (match in numberRegex.findAll(normalizedText)) {
            val num = match.value
            if (seenCopyValues.any { it.contains(num) } || seenCopyValues.contains(num)) {
                continue
            }
            seenCopyValues.add(num)

            val type = when {
                hasOtpKeyword && num.length in 4..8 -> NumberType.OTP
                num.length in 4..8 && results.isEmpty() -> NumberType.OTP
                else -> NumberType.GENERAL
            }
            results.add(ExtractedNumber(num, num, type))
        }

        return results.take(3)
    }
}
