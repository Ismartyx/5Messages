package com.goodwy.messages.feature.main

import android.content.Context
import androidx.recyclerview.widget.ItemTouchHelper
import com.goodwy.messages.R
import com.goodwy.messages.common.Navigator
import com.goodwy.messages.common.base.QkViewModel
import com.goodwy.messages.extensions.anyOf
import com.goodwy.messages.extensions.asObservable
import com.goodwy.messages.extensions.mapNotNull
import com.goodwy.messages.interactor.DeleteConversations
import com.goodwy.messages.interactor.MarkAllSeen
import com.goodwy.messages.interactor.MarkArchived
import com.goodwy.messages.interactor.MarkPinned
import com.goodwy.messages.interactor.MarkRead
import com.goodwy.messages.interactor.MarkUnarchived
import com.goodwy.messages.interactor.MarkUnpinned
import com.goodwy.messages.interactor.MarkUnread
import com.goodwy.messages.interactor.MigratePreferences
import com.goodwy.messages.interactor.SyncContacts
import com.goodwy.messages.interactor.SyncMessages
import com.goodwy.messages.listener.ContactAddedListener
import com.goodwy.messages.manager.BillingManager
import com.goodwy.messages.manager.ChangelogManager
import com.goodwy.messages.manager.PermissionManager
import com.goodwy.messages.manager.RatingManager
import com.goodwy.messages.model.Conversation
import com.goodwy.messages.model.SyncLog
import com.goodwy.messages.repository.ConversationRepository
import com.goodwy.messages.repository.SyncRepository
import com.goodwy.messages.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.schedulers.Schedulers
import io.realm.Realm
import io.realm.RealmResults
import io.realm.Sort
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MainViewModel @Inject constructor(
    billingManager: BillingManager,
    contactAddedListener: ContactAddedListener,
    markAllSeen: MarkAllSeen,
    migratePreferences: MigratePreferences,
    syncRepository: SyncRepository,
    private val context: Context,
    private val changelogManager: ChangelogManager,
    private val conversationRepo: ConversationRepository,
    private val deleteConversations: DeleteConversations,
    private val markArchived: MarkArchived,
    private val markPinned: MarkPinned,
    private val markRead: MarkRead,
    private val markUnarchived: MarkUnarchived,
    private val markUnpinned: MarkUnpinned,
    private val markUnread: MarkUnread,
    private val navigator: Navigator,
    private val permissionManager: PermissionManager,
    private val prefs: Preferences,
    private val ratingManager: RatingManager,
    private val syncContacts: SyncContacts,
    private val syncMessages: SyncMessages
) : QkViewModel<MainView, MainState>(MainState()) {

    private var currentCategory: MessageCategory = MessageCategory.CONTACTS
    private val allInboxConversations = conversationRepo.getConversations()
    private val manualPrefs by lazy {
        context.getSharedPreferences("manual_message_categories", Context.MODE_PRIVATE)
    }

    init {
        disposables += deleteConversations
        disposables += markAllSeen
        disposables += markArchived
        disposables += markUnarchived
        disposables += migratePreferences
        disposables += syncContacts
        disposables += syncMessages

        val savedOrder = getSavedCategoryOrder()
        if (savedOrder.isNotEmpty()) {
            currentCategory = savedOrder.first()
        }
        refreshInboxCategory()

        disposables += allInboxConversations.asObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .filter { it.isLoaded && it.isValid }
                .subscribe({ refreshInboxCategory() }, {})

        disposables += syncRepository.syncProgress
                .sample(16, TimeUnit.MILLISECONDS)
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ syncing ->
                    newState { copy(syncing = syncing) }
                    if (syncing is SyncRepository.SyncProgress.Idle) {
                        refreshInboxCategory()
                    }
                }, {})

        disposables += billingManager.upgradeStatus
                .subscribe({ upgraded -> newState { copy(upgraded = upgraded) } }, {})

        disposables += ratingManager.shouldShowRating
                .subscribe({ show -> newState { copy(showRating = show) } }, {})

        migratePreferences.execute(Unit)

        val hasAnyConversations = try {
            Realm.getDefaultInstance().use { realm ->
                realm.where(Conversation::class.java).count() > 0L
            }
        } catch (e: Exception) { false }

        val lastSync = try {
            Realm.getDefaultInstance().use { realm ->
                realm.where(SyncLog::class.java)?.max("date")?.toLong() ?: 0L
            }
        } catch (e: Exception) { 0L }

        if ((lastSync == 0L || !hasAnyConversations) && permissionManager.isDefaultSms() && permissionManager.hasReadSms() && permissionManager.hasContacts()) {
            syncMessages.execute(Unit)
        }

        if (permissionManager.hasContacts()) {
            disposables += contactAddedListener.listen()
                    .debounce(1, TimeUnit.SECONDS)
                    .subscribeOn(Schedulers.io())
                    .subscribe({ syncContacts.execute(Unit) }, {})
        }

        ratingManager.addSession()
        markAllSeen.execute(Unit)
    }

    fun getSavedCategoryOrder(): List<MessageCategory> {
        return try {
            val raw = manualPrefs.getString("tab_order", null)
            if (raw.isNullOrBlank()) return MessageCategory.values().toList()
            val parsed = raw.split(",").mapNotNull { name ->
                try { MessageCategory.valueOf(name) } catch (e: Exception) { null }
            }
            if (parsed.size == MessageCategory.values().size) parsed else MessageCategory.values().toList()
        } catch (e: Exception) {
            MessageCategory.values().toList()
        }
    }

    fun saveCategoryOrder(order: List<MessageCategory>) {
        try {
            manualPrefs.edit().putString("tab_order", order.joinToString(",") { it.name }).apply()
            refreshInboxCategory()
        } catch (_: Exception) {}
    }

    fun setCategory(category: MessageCategory) {
        currentCategory = category
        refreshInboxCategory(forceInbox = true)
    }

    fun setManualCategoryForConversations(threadIds: List<Long>, category: MessageCategory) {
        try {
            val editor = manualPrefs.edit()
            Realm.getDefaultInstance().use { realm ->
                threadIds.forEach { threadId ->
                    editor.putString("thread_$threadId", category.name)
                    val conv = realm.where(Conversation::class.java).equalTo("id", threadId).findFirst()
                    val addr = conv?.recipients?.firstOrNull()?.address?.trim()
                    if (!addr.isNullOrEmpty()) {
                        editor.putString("addr_$addr", category.name)
                    }
                }
            }
            editor.apply()
            refreshInboxCategory()
        } catch (_: Exception) {}
    }

    private fun refreshInboxCategory(forceInbox: Boolean = false) {
        try {
            val (data, counts) = queryCategoryDataAndCounts(currentCategory)
            newState {
                when {
                    forceInbox -> copy(page = Inbox(data = data, category = currentCategory, unreadCounts = counts))
                    page is Inbox -> copy(page = page.copy(data = data, category = currentCategory, unreadCounts = counts))
                    else -> this
                }
            }
        } catch (_: Exception) {}
    }

    private fun queryCategoryDataAndCounts(selectedCategory: MessageCategory): Pair<RealmResults<Conversation>, Map<MessageCategory, Int>> {
        val realm = Realm.getDefaultInstance()
        realm.refresh()

        val allActive = realm.where(Conversation::class.java)
                .notEqualTo("id", 0L)
                .equalTo("archived", false)
                .equalTo("blocked", false)
                .isNotEmpty("recipients")
                .findAll()

        val matchingIds = mutableListOf<Long>()
        val unreadCounts = mutableMapOf(
                MessageCategory.CONTACTS to 0,
                MessageCategory.UNKNOWN to 0,
                MessageCategory.BANK to 0,
                MessageCategory.OTP to 0,
                MessageCategory.OTHER to 0
        )

        for (conv in allActive) {
            if (conv.lastMessage == null && conv.draft.isEmpty()) continue

            val cat = classifyConversation(conv)
            if (cat == selectedCategory) {
                matchingIds.add(conv.id)
            }
            if (conv.unread) {
                unreadCounts[cat] = (unreadCounts[cat] ?: 0) + 1
            }
        }

        val idsArray = if (matchingIds.isEmpty()) longArrayOf(-1L) else matchingIds.toLongArray()

        val results = realm.where(Conversation::class.java)
                .anyOf("id", idsArray)
                .sort(
                        arrayOf("pinned", "draft", "lastMessage.date"),
                        arrayOf(Sort.DESCENDING, Sort.DESCENDING, Sort.DESCENDING)
                )
                .findAllAsync()

        return Pair(results, unreadCounts)
    }

    private fun normalizeDigits(text: String): String {
        return text
            .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3').replace('۴', '4')
            .replace('۵', '5').replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
            .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
            .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')
            .replace('ي', 'ی').replace('ك', 'ک')
    }

    private fun classifyConversation(conv: Conversation): MessageCategory {
        val address = conv.recipients.firstOrNull()?.address?.trim() ?: ""

        // 0. اولویت اول: دسته‌بندی دستی که کاربر انتخاب کرده است
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

    private fun isPersonalMobileNumber(address: String): Boolean {
        val cleaned = address.replace(" ", "").replace("-", "")
        if (cleaned.startsWith("09000") || cleaned.startsWith("+989000") || cleaned.startsWith("989000") ||
            cleaned.startsWith("0999") || cleaned.startsWith("+98999")) {
            return false
        }
        return Regex("^(\\+98|0098|98|0)?9\\d{9}$").matches(cleaned)
    }

    override fun bindView(view: MainView) {
        super.bindView(view)

        when {
            !permissionManager.isDefaultSms() -> view.requestDefaultSms()
            !permissionManager.hasReadSms() || !permissionManager.hasContacts() -> view.requestPermissions()
        }

        val permissions = view.activityResumedIntent
                .filter { resumed -> resumed }
                .observeOn(Schedulers.io())
                .map { Triple(permissionManager.isDefaultSms(), permissionManager.hasReadSms(), permissionManager.hasContacts()) }
                .distinctUntilChanged()
                .share()

        permissions
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext { (defaultSms, smsPermission, contactPermission) ->
                    newState { copy(defaultSms = defaultSms, smsPermission = smsPermission, contactPermission = contactPermission) }
                    refreshInboxCategory()
                }
                .autoDisposable(view.scope())
                .subscribe()

        permissions
                .skip(1)
                .filter { it.first && it.second && it.third }
                .take(1)
                .autoDisposable(view.scope())
                .subscribe { syncMessages.execute(Unit) }

        view.onNewIntentIntent
                .autoDisposable(view.scope())
                .subscribe { intent ->
                    when (intent.getStringExtra("screen")) {
                        "compose" -> navigator.showConversation(intent.getLongExtra("threadId", 0))
                        "blocking" -> navigator.showBlockedConversations()
                    }
                }

        view.changelogMoreIntent
                .autoDisposable(view.scope())
                .subscribe { navigator.showChangelog() }

        view.queryChangedIntent
                .debounce(200, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .map { query -> query.trim() }
                .withLatestFrom(state) { query, state ->
                    if (query.isEmpty() && state.page is Searching) {
                        refreshInboxCategory(forceInbox = true)
                    }
                    query
                }
                .filter { query -> query.length >= 2 }
                .distinctUntilChanged()
                .doOnNext {
                    newState {
                        val page = (page as? Searching) ?: Searching()
                        copy(page = page.copy(loading = true))
                    }
                }
                .observeOn(Schedulers.io())
                .map(conversationRepo::searchConversations)
                .autoDisposable(view.scope())
                .subscribe { data -> newState { copy(page = Searching(loading = false, data = data)) } }

        view.activityResumedIntent
                .filter { resumed -> resumed }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext { refreshInboxCategory() }
                .autoDisposable(view.scope())
                .subscribe()

        view.activityResumedIntent
                .filter { resumed -> !resumed }
                .switchMap {
                    prefs.keyChanges
                            .filter { key -> key.contains("theme") }
                            .map { true }
                            .mergeWith(prefs.autoColor.asObservable().skip(1))
                            .mergeWith(prefs.grayAvatar.asObservable().skip(1))
                            .mergeWith(prefs.separator.asObservable().skip(1))
                            .doOnNext { view.themeChanged() }
                            .takeUntil(view.activityResumedIntent.filter { resumed -> resumed })
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.composeIntent
                .autoDisposable(view.scope())
                .subscribe { navigator.showCompose() }

        view.homeIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        state.page is Searching -> view.clearSearch()
                        state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                        state.page is Archived && state.page.selected > 0 -> view.clearSelection()

                        else -> newState { copy(drawerOpen = true) }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.drawerOpenIntent
                .autoDisposable(view.scope())
                .subscribe { open -> newState { copy(drawerOpen = open) } }

        view.navigationIntent
                .withLatestFrom(state) { drawerItem, state ->
                    newState { copy(drawerOpen = false) }
                    when (drawerItem) {
                        NavItem.BACK -> when {
                            state.drawerOpen -> Unit
                            state.page is Searching -> view.clearSearch()
                            state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                            state.page is Archived && state.page.selected > 0 -> view.clearSelection()
                            state.page !is Inbox -> {
                                refreshInboxCategory(forceInbox = true)
                            }
                            else -> newState { copy(hasError = true) }
                        }
                        NavItem.BACKUP -> navigator.showBackup()
                        NavItem.SCHEDULED -> navigator.showScheduled()
                        NavItem.BLOCKING -> navigator.showBlockedConversations()
                        NavItem.SETTINGS -> navigator.showSettings()
                        NavItem.PLUS -> navigator.showQksmsPlusActivity("main_menu")
                        NavItem.HELP -> navigator.showSupport()
                        NavItem.INVITE -> navigator.showInvite()
                        else -> Unit
                    }
                    drawerItem
                }
                .distinctUntilChanged()
                .doOnNext { drawerItem ->
                    when (drawerItem) {
                        NavItem.INBOX -> refreshInboxCategory(forceInbox = true)
                        NavItem.ARCHIVED -> newState { copy(page = Archived(data = conversationRepo.getConversations(true))) }
                        else -> Unit
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.archive }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markArchived.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.unarchive }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markUnarchived.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.delete }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    view.showDeleteDialog(conversations)
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.add }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations -> conversations }
                .doOnNext { view.clearSelection() }
                .filter { conversations -> conversations.size == 1 }
                .map { conversations -> conversations.first() }
                .mapNotNull(conversationRepo::getConversation)
                .map { conversation -> conversation.recipients }
                .mapNotNull { recipients -> recipients[0]?.address?.takeIf { recipients.size == 1 } }
                .doOnNext(navigator::addContact)
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.pin }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markPinned.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.unpin }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markUnpinned.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.read }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markRead.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.unread }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markUnread.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.block }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    view.showBlockingDialog(conversations, true)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.plusBannerIntent
                .autoDisposable(view.scope())
                .subscribe {
                    newState { copy(drawerOpen = false) }
                    navigator.showQksmsPlusActivity("main_banner")
                }

        view.rateIntent
                .autoDisposable(view.scope())
                .subscribe {
                    navigator.showRating()
                    ratingManager.rate()
                }

        view.dismissRatingIntent
                .autoDisposable(view.scope())
                .subscribe { ratingManager.dismiss() }

        view.conversationsSelectedIntent
                .withLatestFrom(state) { selection, state ->
                    val conversations = selection.mapNotNull(conversationRepo::getConversation)
                    val add = conversations.firstOrNull()
                            ?.takeIf { conversations.size == 1 }
                            ?.takeIf { conversation -> conversation.recipients.size == 1 }
                            ?.recipients?.first()
                            ?.takeIf { recipient -> recipient.contact == null } != null
                    val pin = conversations.sumBy { if (it.pinned) -1 else 1 } >= 0
                    val read = conversations.sumBy { if (!it.unread) -1 else 1 } >= 0
                    val selected = selection.size

                    when (state.page) {
                        is Inbox -> {
                            val page = state.page.copy(addContact = add, markPinned = pin, markRead = read, selected = selected)
                            newState { copy(page = page) }
                        }

                        is Archived -> {
                            val page = state.page.copy(addContact = add, markPinned = pin, markRead = read, selected = selected)
                            newState { copy(page = page) }
                        }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.confirmDeleteIntent
                .autoDisposable(view.scope())
                .subscribe { conversations ->
                    deleteConversations.execute(conversations)
                    view.clearSelection()
                }

        view.swipeConversationIntent
                .autoDisposable(view.scope())
                .subscribe { (threadId, direction) ->
                    val action = if (direction == ItemTouchHelper.RIGHT) prefs.swipeRight.get() else prefs.swipeLeft.get()
                    when (action) {
                        Preferences.SWIPE_ACTION_ARCHIVE -> markArchived.execute(listOf(threadId)) { view.showArchivedSnackbar() }
                        Preferences.SWIPE_ACTION_DELETE -> view.showDeleteDialog(listOf(threadId))
                        Preferences.SWIPE_ACTION_BLOCK -> view.showBlockingDialog(listOf(threadId), true)
                        Preferences.SWIPE_ACTION_CALL -> conversationRepo.getConversation(threadId)?.recipients?.firstOrNull()?.address?.let(navigator::makePhoneCall)
                        Preferences.SWIPE_ACTION_READ -> markRead.execute(listOf(threadId))
                        Preferences.SWIPE_ACTION_UNREAD -> markUnread.execute(listOf(threadId))
                    }
                }

        view.undoArchiveIntent
                .withLatestFrom(view.swipeConversationIntent) { _, pair -> pair.first }
                .autoDisposable(view.scope())
                .subscribe { threadId -> markUnarchived.execute(listOf(threadId)) }

        view.snackbarButtonIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        !state.defaultSms -> view.requestDefaultSms()
                        !state.smsPermission -> view.requestPermissions()
                        !state.contactPermission -> view.requestPermissions()
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()
    }

}
