package com.goodwy.messages.feature.main

import android.content.Context
import androidx.recyclerview.widget.ItemTouchHelper
import com.goodwy.messages.R
import com.goodwy.messages.common.Navigator
import com.goodwy.messages.common.base.QkViewModel
import com.goodwy.messages.common.util.TrashManager
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
import io.reactivex.Completable
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

    private var currentCategoryId: String = "CONTACTS"
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
            currentCategoryId = savedOrder.first().id
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

        try {
            // Clean up any accidental SAVED_NOTES category assignments
            val manualEdit = manualPrefs.edit()
            var modified = false
            for ((key, value) in manualPrefs.all) {
                if (value == "SAVED_NOTES") {
                    manualEdit.remove(key)
                    modified = true
                }
            }
            if (modified) {
                manualEdit.apply()
            }
            val legacyPrefs = context.getSharedPreferences("manual_categories", Context.MODE_PRIVATE)
            val legEdit = legacyPrefs.edit()
            var legMod = false
            for ((key, value) in legacyPrefs.all) {
                if (value == "SAVED_NOTES") {
                    legEdit.remove(key)
                    legMod = true
                }
            }
            if (legMod) {
                legEdit.apply()
            }

            // Clean up expired trash
            val retentionDays = prefs.trashRetention.get()
            val expired = TrashManager.getTrashedItems(context).filter {
                if (retentionDays <= 0) false
                else it.trashedAt < System.currentTimeMillis() - (retentionDays.toLong() * 24L * 3600L * 1000L)
            }.map { it.threadId }
            if (expired.isNotEmpty()) {
                deleteConversations.execute(expired)
                TrashManager.removeTrashed(context, expired)
            }
        } catch (_: Exception) {}

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

        if (permissionManager.hasContacts() && permissionManager.hasReadSms()) {
            Completable.fromAction {
                syncRepository.mergeContactConversations()
            }
            .subscribeOn(Schedulers.io())
            .subscribe({}, {})
        }

        ratingManager.addSession()
        markAllSeen.execute(Unit)
    }

    fun getSavedCategoryOrder(): List<CategoryItem> {
        return CategoryManager.getCategories(context)
    }

    fun saveCategoryOrder(order: List<CategoryItem>) {
        try {
            CategoryManager.saveCategories(context, order)
            refreshInboxCategory()
        } catch (_: Exception) {}
    }

    fun setCategory(categoryId: String) {
        currentCategoryId = categoryId
        refreshInboxCategory(forceInbox = true)
    }

    fun setManualCategoryForConversations(threadIds: List<Long>, categoryId: String) {
        try {
            val editor = manualPrefs.edit()
            val legacyEditor = context.getSharedPreferences("manual_categories", Context.MODE_PRIVATE).edit()
            Realm.getDefaultInstance().use { realm ->
                threadIds.forEach { threadId ->
                    editor.putString("thread_$threadId", categoryId)
                    legacyEditor.putString("thread_$threadId", categoryId)
                    val conv = realm.where(Conversation::class.java).equalTo("id", threadId).findFirst()
                    val addr = conv?.recipients?.firstOrNull()?.address?.trim()
                    if (!addr.isNullOrEmpty()) {
                        editor.putString("addr_$addr", categoryId)
                        legacyEditor.putString("addr_$addr", categoryId)
                    }
                }
            }
            editor.apply()
            legacyEditor.apply()
            refreshInboxCategory()
        } catch (_: Exception) {}
    }

    fun renameCategory(id: String, newName: String) {
        if (CategoryManager.renameCategory(context, id, newName)) {
            refreshInboxCategory()
        }
    }

    fun addCategory(name: String): CategoryItem? {
        val item = CategoryManager.addCategory(context, name)
        if (item != null) {
            currentCategoryId = item.id
            refreshInboxCategory(forceInbox = true)
        }
        return item
    }

    fun deleteCategory(id: String) {
        if (CategoryManager.deleteCategory(context, id)) {
            if (currentCategoryId == id) {
                currentCategoryId = "CONTACTS"
            }
            refreshInboxCategory(forceInbox = true)
        }
    }

    fun moveCategoryPosition(from: Int, to: Int) {
        CategoryManager.moveCategoryPosition(context, from, to)
        refreshInboxCategory()
    }

    private fun refreshInboxCategory(forceInbox: Boolean = false) {
        try {
            val (data, counts) = queryCategoryDataAndCounts(currentCategoryId)
            newState {
                when {
                    forceInbox -> copy(page = Inbox(data = data, categoryId = currentCategoryId, unreadCounts = counts))
                    page is Inbox -> copy(page = page.copy(data = data, categoryId = currentCategoryId, unreadCounts = counts))
                    else -> this
                }
            }
        } catch (_: Exception) {}
    }

    private fun queryCategoryDataAndCounts(selectedCategoryId: String): Pair<RealmResults<Conversation>, Map<String, Int>> {
        val realm = Realm.getDefaultInstance()
        realm.refresh()

        val allActive = realm.where(Conversation::class.java)
                .notEqualTo("id", 0L)
                .equalTo("archived", false)
                .equalTo("blocked", false)
                .isNotEmpty("recipients")
                .findAll()

        val matchingIds = mutableListOf<Long>()
        val unreadCounts = mutableMapOf<String, Int>()
        val trashedIds = TrashManager.getTrashedThreadIds(context)

        for (conv in allActive) {
            if (!conv.isValid) continue
            if (conv.lastMessage == null && conv.draft.isEmpty()) continue
            if (trashedIds.contains(conv.id)) continue

            val catId = classifyConversation(conv)
            if (catId == selectedCategoryId) {
                matchingIds.add(conv.id)
            }
            if (conv.unread) {
                unreadCounts[catId] = (unreadCounts[catId] ?: 0) + 1
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

    private fun classifyConversation(conv: Conversation): String {
        return ConversationClassifier.classifyConversation(context, conv)
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
                            state.page is Trash && state.page.selected > 0 -> view.clearSelection()
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
                        NavItem.TRASH -> refreshTrashPage()
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
                .filter { itemId -> itemId == R.id.restore }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    TrashManager.restoreConversations(context, conversations)
                    view.clearSelection()
                    refreshTrashPage()
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

                        is Trash -> {
                            val page = state.page.copy(selected = selected)
                            newState { copy(page = page) }
                        }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.confirmDeleteIntent
                .withLatestFrom(state) { conversations, state -> Pair(conversations, state.page) }
                .autoDisposable(view.scope())
                .subscribe { (conversations, page) ->
                    if (page is Trash) {
                        deleteConversations.execute(conversations)
                        TrashManager.removeTrashed(context, conversations)
                        view.clearSelection()
                        refreshTrashPage()
                    } else {
                        TrashManager.moveToTrash(context, conversations)
                        view.clearSelection()
                        refreshInboxCategory()
                        view.showTrashSnackbar(conversations)
                    }
                }

        view.undoTrashIntent
                .withLatestFrom(state) { _, state -> state.page }
                .autoDisposable(view.scope())
                .subscribe { page ->
                    if (page is Inbox) {
                        refreshInboxCategory()
                    } else if (page is Trash) {
                        refreshTrashPage()
                    }
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

    fun refreshTrashPage() {
        try {
            val trashedIds = TrashManager.getTrashedThreadIds(context).toLongArray()
            val data = if (trashedIds.isEmpty()) {
                conversationRepo.getConversations(-1L)
            } else {
                conversationRepo.getConversations(*trashedIds)
            }
            newState { copy(page = Trash(data = data)) }
        } catch (_: Exception) {}
    }

    fun emptyTrash() {
        val ids = TrashManager.getTrashedThreadIds(context).toList()
        if (ids.isNotEmpty()) {
            deleteConversations.execute(ids)
            TrashManager.removeTrashed(context, ids)
        }
        refreshTrashPage()
    }

}
