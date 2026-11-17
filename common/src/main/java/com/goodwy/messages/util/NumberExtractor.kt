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
        "پیگیری", "مرجع", "حساب", "شبا", "کارت",
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

    fun cleanText(str: String): String {
        val ascii = toAsciiDigits(str)
        return ascii.replace(Regex("[\u200B-\u200F\u202A-\u202E\u2060-\u2069\uFEFF]"), "")
            .replace('\u00A0', ' ')
    }

    fun extract(text: String): List<ExtractedNumber> {
        if (text.isBlank()) return emptyList()

        val normalizedText = cleanText(text)
        val lowerText = normalizedText.toLowerCase()
        val hasOtpKeyword = OTP_KEYWORDS.any { lowerText.contains(it) }

        val results = mutableListOf<ExtractedNumber>()
        val seenCopyValues = mutableSetOf<String>()

        fun addResult(display: String, copy: String, type: NumberType) {
            if (copy.isNotBlank() && seenCopyValues.add(copy)) {
                results.add(ExtractedNumber(display, copy, type))
            }
        }

        // 1. Check for Sheba / IBAN with IR: IR + 24 digits (supports optional spaces or dashes)
        val ibanWithIrRegex = Regex("(?<![a-zA-Z0-9])IR\\s*(?:[0-9][\\s-]*){24}(?![0-9])", RegexOption.IGNORE_CASE)
        for (match in ibanWithIrRegex.findAll(normalizedText)) {
            val raw = match.value.toUpperCase()
            val cleanDigits = raw.substring(2).replace(Regex("[\\s-]"), "")
            if (cleanDigits.length == 24) {
                val fullIban = "IR$cleanDigits"
                addResult(fullIban, fullIban, NumberType.IBAN)
            }
        }

        // 2. Check for Bank Card (16 digits with optional spaces or dashes)
        val cardRegex = Regex("(?<!\\d)(?:\\d[\\s-]*){16}(?!\\d)")
        for (match in cardRegex.findAll(normalizedText)) {
            val raw = match.value
            val clean = raw.replace(Regex("[\\s-]"), "")
            if (clean.length == 16) {
                if (!seenCopyValues.any { it.contains(clean) }) {
                    val displayFormatted = clean.chunked(4).joinToString(" ")
                    addResult(displayFormatted, clean, NumberType.CARD)
                }
            }
        }

        // 3. Check for Sheba without IR (24 digits, standalone or grouped)
        val iban24Regex = Regex("(?<!\\d)(?:\\d[\\s-]*){24}(?!\\d)")
        for (match in iban24Regex.findAll(normalizedText)) {
            val raw = match.value
            val clean = raw.replace(Regex("[\\s-]"), "")
            if (clean.length == 24) {
                if (!seenCopyValues.any { it.contains(clean) }) {
                    addResult(clean, clean, NumberType.IBAN)
                }
            }
        }

        // 4. Check for numbers enclosed in brackets/parentheses/quotes: (...), [...], {...}, «...», etc.
        val enclosedRegex = Regex("[\\(\\[\\{«（<\"'‘“]\\s*([0-9\\s\\-_/]{3,32})\\s*[\\)\\]\\}»）>\"'’”]")
        for (match in enclosedRegex.findAll(normalizedText)) {
            val inner = match.groupValues[1]
            val clean = inner.replace(Regex("[\\s\\-_/]"), "")
            if (clean.length in 3..32 && clean.all { it.isDigit() }) {
                if (!seenCopyValues.any { it.contains(clean) || clean.contains(it) }) {
                    val type = when {
                        clean.length == 24 -> NumberType.IBAN
                        clean.length == 16 -> NumberType.CARD
                        clean.length in 3..8 -> NumberType.OTP
                        else -> NumberType.GENERAL
                    }
                    addResult(clean, clean, type)
                }
            }
        }

        // 5. Check for formatted OTP codes like 123-456 or 123 456
        val formattedOtpRegex = Regex("(?<!\\d)\\d{3,4}[ -]\\d{3,4}(?!\\d)")
        for (match in formattedOtpRegex.findAll(normalizedText)) {
            val raw = match.value
            val clean = raw.replace(Regex("[ -]"), "")
            if (clean.length in 6..8 && clean.all { it.isDigit() }) {
                if (!seenCopyValues.any { it.contains(clean) || clean.contains(it) }) {
                    addResult(clean, clean, NumberType.OTP)
                }
            }
        }

        // 6. Check for standalone numbers (4 to 32 digits, or 3 digits if OTP keyword present)
        val minDigits = if (hasOtpKeyword) 3 else 4
        val standaloneRegex = Regex("(?<!\\d)\\d{$minDigits,32}(?!\\d)")
        for (match in standaloneRegex.findAll(normalizedText)) {
            val num = match.value
            if (seenCopyValues.any { it.contains(num) } || seenCopyValues.contains(num)) {
                continue
            }

            val type = when {
                num.length == 24 -> NumberType.IBAN
                num.length == 16 -> NumberType.CARD
                hasOtpKeyword && num.length in 3..8 -> NumberType.OTP
                num.length in 4..8 && results.isEmpty() -> NumberType.OTP
                else -> NumberType.GENERAL
            }
            addResult(num, num, type)
        }

        return results.take(6)
    }
}
