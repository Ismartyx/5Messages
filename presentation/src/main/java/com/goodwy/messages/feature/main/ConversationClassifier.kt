package com.goodwy.messages.feature.main

import android.content.Context
import com.goodwy.messages.model.Conversation
import java.util.Locale

object ConversationClassifier {

    fun normalizeDigits(text: String): String {
        return text
            .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3').replace('۴', '4')
            .replace('۵', '5').replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
            .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
            .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')
            .replace('ي', 'ی').replace('ك', 'ک')
    }

    fun isPersonalMobileNumber(address: String): Boolean {
        val cleaned = address.replace(" ", "").replace("-", "")
        if (cleaned.startsWith("09000") || cleaned.startsWith("+989000") || cleaned.startsWith("989000") ||
            cleaned.startsWith("0999") || cleaned.startsWith("+98999")) {
            return false
        }
        return Regex("^(\\+98|0098|98|0)?9\\d{9}$").matches(cleaned)
    }

    fun classifyConversation(context: Context, conv: Conversation): MessageCategory {
        val manualPrefs = context.getSharedPreferences("manual_categories", Context.MODE_PRIVATE)
        val address = conv.recipients.firstOrNull()?.address?.trim() ?: ""

        // 0. اولویت اول: دسته‌بندی دستی که کاربر در گوشی انتخاب کرده است
        val manualByThread = manualPrefs.getString("thread_${conv.id}", null)
        if (manualByThread != null) {
            try { return MessageCategory.valueOf(manualByThread) } catch (_: Exception) {}
        }
        if (address.isNotEmpty()) {
            val manualByAddr = manualPrefs.getString("addr_$address", null)
            if (manualByAddr != null) {
                try { return MessageCategory.valueOf(manualByAddr) } catch (_: Exception) {}
            }
        }

        val rawBody = conv.lastMessage?.body ?: ""
        val body = normalizeDigits(rawBody).toLowerCase(Locale.ROOT)
        val addrLower = address.toLowerCase(Locale.ROOT)
        val contactNameLower = normalizeDigits(conv.recipients.firstOrNull()?.contact?.name ?: "").toLowerCase(Locale.ROOT)

        // 1. تشخیص جامع رمزها و کدهای تایید (OTP)
        val otpKeywords = listOf(
            "رمز پویا", "رمزدوم", "رمز دوم", "رمز یکبار", "رمزیکبار", "رمز مصرف",
            "کد تایید", "کد تأیید", "کدتایید", "کد ورود", "کدورود", "کد فعال", "کدفعال",
            "کد امنیتی", "کد شناسایی", "کد احراز", "کد اعتبارسنجی", "کد محرمانه", "کد ثبت",
            "کد شما", "کد:", "رمز:", "کد ", "رمز ",
            "verification", "verify", "otp", "security code", "auth code", "login code",
            "activation code", "pin code", "passcode", "one-time", "code:", "code is"
        )
        val hasShortOtpDigits = Regex("\\b\\d{4,8}\\b").containsMatchIn(body)
        val hasContact = conv.recipients.any { it.contact != null }

        if (!hasContact && (otpKeywords.any { body.contains(it) } && hasShortOtpDigits ||
            body.contains("رمز پویا") || body.contains("کد تایید") || body.contains("کد ورود") || body.contains("verification") || body.contains("otp"))) {
            return MessageCategory.OTP
        }

        // 2. پیام‌های بانکی
        val bankKeywords = listOf(
            "بانک", "واریز", "برداشت", "مانده", "موجودی", "حساب",
            "شبا", "ساتنا", "پایا", "تسهیلات", "بلوبانک",
            "رسالت", "ملت", "ملی", "صادرات", "تجارت", "سپه", "پاسارگاد", "سامان", "پارسیان", "مسکن", "کشاورزی", "آینده", "رفاه", "شهر", "دی", "سینا", "گردشگری", "کارآفرین", "اقتصاد نوین",
            "کارت به کارت", "سود", "قسط", "چک", "صیادی"
        )
        val bankSenders = listOf("bank", "resalat", "mellat", "melli", "saderat", "tejarat", "sepah", "pasargad", "saman", "parsian", "blu", "ayandeh", "refah", "maskan", "keshavarzi", "gardeshgari", "enbank", "karafarin")
        val isBankSender = bankSenders.any { addrLower.contains(it) || contactNameLower.contains(it) }
        val isBankBody = bankKeywords.any { body.contains(it) } && (!isPersonalMobileNumber(address) || isBankSender)
        if (isBankSender || isBankBody) {
            return MessageCategory.BANK
        }

        // 3. مخاطبین ذخیره‌شده
        if (hasContact) return MessageCategory.CONTACTS

        // 4. شماره‌های موبایل شخصی ناشناس
        if (isPersonalMobileNumber(address)) {
            return MessageCategory.UNKNOWN
        }

        // 5. اگر از سرشماره بود و فقط یک کد ۴ تا ۶ رقمی در یک متن کوتاه داشت، باز هم رمز و کد است
        if (hasShortOtpDigits && body.length < 110) {
            return MessageCategory.OTP
        }

        // 6. متفرقه
        return MessageCategory.OTHER
    }
}
