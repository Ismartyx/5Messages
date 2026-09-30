package com.goodwy.messages.feature.main

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.DragEvent
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewStub
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.goodwy.messages.R
import com.goodwy.messages.common.Navigator
import com.goodwy.messages.common.androidxcompat.drawerOpen
import com.goodwy.messages.common.base.QkThemedActivity
import com.goodwy.messages.common.util.extensions.autoScrollToStart
import com.goodwy.messages.common.util.extensions.dismissKeyboard
import com.goodwy.messages.common.util.extensions.scrapViews
import com.goodwy.messages.common.util.extensions.setBackgroundTint
import com.goodwy.messages.common.util.extensions.setTint
import com.goodwy.messages.common.util.extensions.setVisible
import com.goodwy.messages.common.widget.QkTextView
import com.goodwy.messages.feature.blocking.BlockingDialog
import com.goodwy.messages.feature.changelog.ChangelogDialog
import com.goodwy.messages.feature.conversations.ConversationItemTouchCallback
import com.goodwy.messages.feature.conversations.ConversationsAdapter
import com.goodwy.messages.manager.ChangelogManager
import com.goodwy.messages.repository.BackupRepository
import com.goodwy.messages.repository.SyncRepository
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.drawer_view.*
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.android.synthetic.main.main_activity.toolbar
import kotlinx.android.synthetic.main.main_activity.toolbarTitle
import kotlinx.android.synthetic.main.main_permission_hint.*
import kotlinx.android.synthetic.main.main_syncing.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MainActivity : QkThemedActivity(), MainView {

    companion object {
        private const val MENU_MOVE_CATEGORY = 9991
    }

    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var drawerBadgesExperiment: DrawerBadgesExperiment
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var itemTouchCallback: ConversationItemTouchCallback
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var backupRepo: BackupRepository

    override val onNewIntentIntent: Subject<Intent> = PublishSubject.create()
    override val activityResumedIntent: Subject<Boolean> = PublishSubject.create()
    override val queryChangedIntent by lazy { toolbarSearch.textChanges() }
    override val composeIntent by lazy { compose.clicks() }
    override val drawerOpenIntent: Observable<Boolean> by lazy {
        drawerLayout
                .drawerOpen(Gravity.START)
                .doOnNext { dismissKeyboard() }
    }
    override val homeIntent: Subject<Unit> = PublishSubject.create()
    override val navigationIntent: Observable<NavItem> by lazy {
        Observable.merge(listOf(
                backPressedSubject,
                inbox.clicks().map { NavItem.INBOX },
                archived.clicks().map { NavItem.ARCHIVED },
                backup.clicks().map {
                    val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
                    if (sp.getString("nc_pass", "").isNullOrBlank()) {
                        showNextcloudSettingsDialog()
                    }
                    NavItem.BACKUP
                },
                scheduled.clicks().map { NavItem.SCHEDULED },
                blocking.clicks().map { NavItem.BLOCKING },
                settings.clicks().map { NavItem.SETTINGS },
                plus.clicks().map { NavItem.PLUS },
                help.clicks().map { NavItem.HELP },
                invite.clicks().map { NavItem.INVITE }))
    }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val plusBannerIntent by lazy { plusBanner.clicks() }
    override val dismissRatingIntent by lazy { rateDismiss.clicks() }
    override val rateIntent by lazy { rateOkay.clicks() }
    override val conversationsSelectedIntent by lazy { conversationsAdapter.selectionChanges }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val swipeConversationIntent by lazy { itemTouchCallback.swipes }
    override val changelogMoreIntent by lazy { changelogDialog.moreClicks }
    override val undoArchiveIntent: Subject<Unit> = PublishSubject.create()
    override val snackbarButtonIntent: Subject<Unit> = PublishSubject.create()

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[MainViewModel::class.java] }
    private val toggle by lazy { ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.main_drawer_open_cd, 0) }
    private val itemTouchHelper by lazy { ItemTouchHelper(itemTouchCallback) }
    private val progressAnimator by lazy { ObjectAnimator.ofInt(syncingProgress, "progress", 0, 0) }
    private val changelogDialog by lazy { ChangelogDialog(this) }
    private val snackbar by lazy { findViewById<View>(R.id.snackbar) }
    private val syncing by lazy { findViewById<View>(R.id.syncing) }
    private val backPressedSubject: Subject<NavItem> = PublishSubject.create()
    private var currentSelectedIds: List<Long> = emptyList()
    private var draggedTabIndex: Int = -1

    private val categoryColors = mapOf(
        MessageCategory.CONTACTS to Color.parseColor("#0EA5E9"), // Sky Blue
        MessageCategory.UNKNOWN to Color.parseColor("#F59E0B"),  // Amber Orange
        MessageCategory.BANK to Color.parseColor("#22C55E"),     // Emerald Green
        MessageCategory.OTP to Color.parseColor("#A855F7"),      // Purple
        MessageCategory.OTHER to Color.parseColor("#EF4444")     // Coral Red
    )

    private val categoryLabels = mapOf(
        MessageCategory.CONTACTS to "مخاطبین",
        MessageCategory.UNKNOWN to "شخصی ناشناس",
        MessageCategory.BANK to "بانکی",
        MessageCategory.OTP to "رمز و کد",
        MessageCategory.OTHER to "متفرقه"
    )

    private val tabViews: List<QkTextView> by lazy {
        listOf(tabContacts, tabUnknown, tabBank, tabOtp, tabOther)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        forceWhiteStatusBarIcons()
        viewModel.bindView(this)
        onNewIntentIntent.onNext(intent)

        drawer?.setBackgroundColor(Color.parseColor("#111827"))

        conversationsSelectedIntent
                .autoDisposable(scope())
                .subscribe({ currentSelectedIds = it }, {})

        (snackbar as? ViewStub)?.setOnInflateListener { _, _ ->
            snackbarButton.clicks()
                    .autoDisposable(scope(Lifecycle.Event.ON_DESTROY))
                    .subscribe(snackbarButtonIntent)
        }

        (syncing as? ViewStub)?.setOnInflateListener { _, _ ->
            syncingProgress?.progressTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
            syncingProgress?.indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
        }

        toggle.syncState()
        toolbar.setNavigationOnClickListener {
            dismissKeyboard()
            homeIntent.onNext(Unit)
        }

        itemTouchCallback.adapter = conversationsAdapter
        conversationsAdapter.autoScrollToStart(recyclerView)

        setupDragAndDropForCategoryTabs()

        backup?.setOnLongClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showNextcloudSettingsDialog()
            true
        }
        settings?.setOnLongClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showNextcloudSettingsDialog()
            true
        }

        checkAndRunAutoBackup()

        drawer.clicks().autoDisposable(scope()).subscribe()

        theme
                .autoDisposable(scope())
                .subscribe({
                    forceWhiteStatusBarIcons()
                    val states = arrayOf(
                            intArrayOf(android.R.attr.state_activated),
                            intArrayOf(-android.R.attr.state_activated))

                    ColorStateList(states, intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#9CA3AF")))
                            .let { tintList ->
                                inboxIcon?.imageTintList = tintList
                                archivedIcon?.imageTintList = tintList
                            }

                    listOf(plusBadge1, plusBadge2).forEach { badge ->
                        badge?.setBackgroundTint(Color.parseColor("#0EA5E9"))
                        badge?.setTextColor(Color.WHITE)
                    }
                    syncingProgress?.progressTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
                    syncingProgress?.indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
                    plusIcon?.setTint(Color.parseColor("#0EA5E9"))
                    rateIcon?.setTint(Color.parseColor("#0EA5E9"))
                    compose?.setBackgroundTint(Color.parseColor("#0EA5E9"))
                    compose?.setTint(Color.WHITE)
                }, {})

        if (Build.VERSION.SDK_INT <= 22) {
            toolbarSearch?.setBackgroundTint(Color.parseColor("#111827"))
        }
    }

    private fun setupDragAndDropForCategoryTabs() {
        tabViews.forEachIndexed { index, tabView ->
            // با نگه داشتن دست روی هر تب، قابلیت کشیدن و رها کردن (Drag & Drop) فعال می‌شود
            tabView.setOnLongClickListener { view ->
                draggedTabIndex = index
                val clipData = ClipData.newPlainText("tab_index", index.toString())
                val shadowBuilder = View.DragShadowBuilder(view)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    view.startDragAndDrop(clipData, shadowBuilder, view, 0)
                } else {
                    @Suppress("DEPRECATION")
                    view.startDrag(clipData, shadowBuilder, view, 0)
                }
                view.alpha = 0.5f
                true
            }

            tabView.setOnDragListener { targetView, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        val fromIdx = draggedTabIndex
                        val toIdx = tabViews.indexOf(targetView)
                        if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                            val currentOrder = viewModel.getSavedCategoryOrder().toMutableList()
                            if (fromIdx in currentOrder.indices && toIdx in currentOrder.indices) {
                                val movedItem = currentOrder.removeAt(fromIdx)
                                currentOrder.add(toIdx, movedItem)
                                draggedTabIndex = toIdx
                                viewModel.saveCategoryOrder(currentOrder)
                            }
                        }
                        true
                    }
                    DragEvent.ACTION_DROP -> true
                    DragEvent.ACTION_DRAG_ENDED -> {
                        tabViews.forEach { it.alpha = 1.0f }
                        draggedTabIndex = -1
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun forceWhiteStatusBarIcons() {
        try {
            window.statusBarColor = Color.parseColor("#0B1220")
            window.navigationBarColor = Color.parseColor("#0B1220")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var flags = window.decorView.systemUiVisibility
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
                window.decorView.systemUiVisibility = flags
            }
        } catch (_: Exception) {}
    }

    private fun showMoveConversationsCategoryDialog(selectedThreadIds: List<Long>) {
        if (selectedThreadIds.isEmpty()) return
        val categories = viewModel.getSavedCategoryOrder()
        val titles = categories.map { categoryLabels[it] ?: it.name }.toTypedArray()

        AlertDialog.Builder(this)
                .setTitle("انتقال به کدام پوشه؟")
                .setItems(titles) { _, which ->
                    val targetCategory = categories[which]
                    viewModel.setManualCategoryForConversations(selectedThreadIds, targetCategory)
                    clearSelection()
                    Toast.makeText(this, "به پوشه «${titles[which]}» منتقل شد", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("انصراف", null)
                .show()
    }

    private fun checkAndRunAutoBackup() {
        try {
            val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
            val autoBackupEnabled = sp.getBoolean("nc_auto_backup", true)
            val pass = sp.getString("nc_pass", "") ?: ""
            if (!autoBackupEnabled || pass.isBlank()) return

            val lastAutoBackup = sp.getLong("nc_last_auto_backup", 0L)
            val now = System.currentTimeMillis()
            if (now - lastAutoBackup >= TimeUnit.HOURS.toMillis(24)) {
                sp.edit().putLong("nc_last_auto_backup", now).apply()
                Completable.fromAction { backupRepo.performBackup() }
                        .subscribeOn(Schedulers.io())
                        .subscribe({}, {})
            }
        } catch (_: Exception) {}
    }

    private fun showNextcloudSettingsDialog() {
        val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
        val currentUser = sp.getString("nc_user", "saeed") ?: "saeed"
        val currentPass = sp.getString("nc_pass", "") ?: ""
        val currentAuto = sp.getBoolean("nc_auto_backup", true)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 36, 56, 16)
        }

        val infoText = TextView(this).apply {
            text = "سرور: https://nc.taha.surf\nمسیر: /5Messages_Backups"
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }

        val userEdit = EditText(this).apply {
            hint = "نام کاربری Nextcloud (مثلاً saeed)"
            setText(currentUser)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val passEdit = EditText(this).apply {
            hint = "رمز عبور یا App Password نکست‌کلاد"
            setText(currentPass)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val autoBackupCheck = CheckBox(this).apply {
            text = "بکاپ خودکار روزانه (پیام‌ها و تنظیمات)"
            isChecked = currentAuto
            setPadding(0, 16, 0, 0)
        }

        container.addView(infoText)
        container.addView(userEdit)
        container.addView(passEdit)
        container.addView(autoBackupCheck)

        AlertDialog.Builder(this)
                .setTitle("تنظیمات بکاپ Nextcloud")
                .setView(container)
                .setPositiveButton("ذخیره و بکاپ فوری") { _, _ ->
                    val u = userEdit.text.toString().trim()
                    val p = passEdit.text.toString().trim()
                    val auto = autoBackupCheck.isChecked
                    sp.edit()
                            .putString("nc_user", if (u.isEmpty()) "saeed" else u)
                            .putString("nc_pass", p)
                            .putBoolean("nc_auto_backup", auto)
                            .putLong("nc_last_auto_backup", System.currentTimeMillis())
                            .apply()

                    Completable.fromAction { backupRepo.performBackup() }
                            .subscribeOn(Schedulers.io())
                            .subscribe({}, {})

                    Toast.makeText(this, "تنظیمات ذخیره شد و بکاپ به Nextcloud آغاز شد", Toast.LENGTH_LONG).show()
                }
                .setNeutralButton("فقط ذخیره") { _, _ ->
                    val u = userEdit.text.toString().trim()
                    val p = passEdit.text.toString().trim()
                    val auto = autoBackupCheck.isChecked
                    sp.edit()
                            .putString("nc_user", if (u.isEmpty()) "saeed" else u)
                            .putString("nc_pass", p)
                            .putBoolean("nc_auto_backup", auto)
                            .apply()
                    Toast.makeText(this, "تنظیمات Nextcloud ذخیره شد", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("انصراف", null)
                .show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.run(onNewIntentIntent::onNext)
    }

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        forceWhiteStatusBarIcons()

        val addContact = when (state.page) {
            is Inbox -> state.page.addContact
            is Archived -> state.page.addContact
            else -> false
        }

        val markPinned = when (state.page) {
            is Inbox -> state.page.markPinned
            is Archived -> state.page.markPinned
            else -> true
        }

        val markRead = when (state.page) {
            is Inbox -> state.page.markRead
            is Archived -> state.page.markRead
            else -> true
        }

        val selectedConversations = when (state.page) {
            is Inbox -> state.page.selected
            is Archived -> state.page.selected
            else -> 0
        }

        toolbarSearch?.setVisible(state.page is Inbox && state.page.selected == 0 || state.page is Searching)
        toolbarTitle?.setVisible(toolbarSearch?.visibility != View.VISIBLE)
        categoryScroll?.setVisible(state.page is Inbox && state.page.selected == 0)

        toolbar?.menu?.findItem(R.id.archive)?.isVisible = state.page is Inbox && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unarchive)?.isVisible = state.page is Archived && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.delete)?.isVisible = selectedConversations != 0
        toolbar?.menu?.findItem(R.id.add)?.isVisible = addContact && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.pin)?.isVisible = markPinned && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unpin)?.isVisible = !markPinned && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.read)?.isVisible = markRead && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unread)?.isVisible = !markRead && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.block)?.isVisible = selectedConversations != 0
        toolbar?.menu?.findItem(MENU_MOVE_CATEGORY)?.isVisible = state.page is Inbox && selectedConversations != 0

        listOf(plusBadge1, plusBadge2).forEach { badge ->
            badge?.isVisible = !state.upgraded
        }
        plus?.isVisible = state.upgraded
        plusBanner?.isVisible = !state.upgraded
        rateLayout?.setVisible(state.showRating)

        compose?.setVisible(state.page is Inbox || state.page is Archived)
        compose?.animate()?.rotation(if (state.drawerOpen) 90f else 0f)?.start()
        conversationsAdapter.emptyView = empty.takeIf { state.page is Inbox || state.page is Archived }
        searchAdapter.emptyView = empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                showBackButton(state.page.selected > 0)
                if (state.page.selected > 0) {
                    toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                    toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                    toolbar?.setBackgroundResource(R.drawable.rounded_rectangle_transparent_24dp)
                    compose?.animate()?.rotation(90f)?.start()
                } else {
                    toolbar?.setNavigationIcon(R.drawable.ic_menu_24dp)
                    toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                    toolbar?.setBackgroundResource(R.drawable.rounded_rectangle_24dp)
                    toolbar?.setBackgroundTint(Color.parseColor("#111827"))
                    toolbar?.elevation = prefs.searchElevation.get().toFloat()
                    compose?.animate()?.rotation(0f)?.start()
                }
                title = getString(R.string.main_title_selected, state.page.selected)
                if (recyclerView?.adapter !== conversationsAdapter) recyclerView?.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(recyclerView)
                empty?.setText(R.string.inbox_empty_text)

                val inactiveBg = Color.parseColor("#111827")
                val activeCategoryColor = categoryColors[state.page.category] ?: Color.parseColor("#0EA5E9")

                compose?.setBackgroundTint(activeCategoryColor)
                compose?.setTint(Color.WHITE)

                val orderedCategories = viewModel.getSavedCategoryOrder()
                tabViews.forEachIndexed { index, tabView ->
                    val category = orderedCategories.getOrNull(index) ?: return@forEachIndexed
                    val label = categoryLabels[category] ?: ""
                    val hasUnread = (state.page.unreadCounts[category] ?: 0) > 0
                    val isSelected = state.page.category == category
                    val catColor = categoryColors[category] ?: Color.parseColor("#0EA5E9")

                    // نمایش نام تمیز فولدر بدون اعداد گیج‌کننده (فقط یک نقطه کوچک در صورت داشتن پیام نخوانده)
                    tabView.text = if (hasUnread) "$label •" else label
                    tabView.setBackgroundTint(if (isSelected) catColor else inactiveBg)
                    tabView.setTextColor(if (isSelected) Color.WHITE else catColor)
                    tabView.setTypeface(null, Typeface.BOLD)

                    tabView.setOnClickListener {
                        clearSelection()
                        viewModel.setCategory(category)
                    }
                }
            }

            is Searching -> {
                showBackButton(true)
                toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                if (recyclerView?.adapter !== searchAdapter) recyclerView?.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                itemTouchHelper.attachToRecyclerView(null)
                empty?.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                showBackButton(state.page.selected > 0)
                if (state.page.selected > 0) {
                    toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                    toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                    compose?.animate()?.rotation(90f)?.start()
                } else {
                    toolbar?.setNavigationIcon(R.drawable.ic_menu_24dp)
                    toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                    compose?.animate()?.rotation(0f)?.start()
                }
                toolbar?.setBackgroundResource(R.drawable.rounded_rectangle_transparent_24dp)
                title = when (state.page.selected != 0) {
                    true -> getString(R.string.main_title_selected, state.page.selected)
                    false -> getString(R.string.title_archived)
                }
                if (recyclerView?.adapter !== conversationsAdapter) recyclerView?.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(null)
                empty?.setText(R.string.archived_empty_text)
            }
        }

        inbox?.isActivated = state.page is Inbox
        archived?.isActivated = state.page is Archived

        if (drawerLayout?.isDrawerOpen(GravityCompat.START) == true && !state.drawerOpen) {
            drawerLayout?.closeDrawer(GravityCompat.START)
        } else if (drawerLayout?.isDrawerVisible(GravityCompat.START) == false && state.drawerOpen) {
            drawerLayout?.openDrawer(GravityCompat.START)
        }

        when (state.syncing) {
            is SyncRepository.SyncProgress.Idle -> {
                syncing?.isVisible = false
                snackbar?.isVisible = !state.defaultSms || !state.smsPermission || !state.contactPermission
            }

            is SyncRepository.SyncProgress.Running -> {
                syncing?.isVisible = true
                syncingProgress?.max = state.syncing.max
                if (syncingProgress != null) {
                    progressAnimator.apply { setIntValues(syncingProgress.progress, state.syncing.progress) }.start()
                }
                syncingProgress?.isIndeterminate = state.syncing.indeterminate
                snackbar?.isVisible = false
            }
        }

        when {
            !state.defaultSms -> {
                snackbarTitle?.setText(R.string.main_default_sms_title)
                snackbarMessage?.setText(R.string.main_default_sms_message)
                snackbarButton?.setText(R.string.main_default_sms_change)
            }

            !state.smsPermission -> {
                snackbarTitle?.setText(R.string.main_permission_required)
                snackbarMessage?.setText(R.string.main_permission_sms)
                snackbarButton?.setText(R.string.main_permission_allow)
            }

            !state.contactPermission -> {
                snackbarTitle?.setText(R.string.main_permission_required)
                snackbarMessage?.setText(R.string.main_permission_contacts)
                snackbarButton?.setText(R.string.main_permission_allow)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        forceWhiteStatusBarIcons()
        activityResumedIntent.onNext(true)
        checkAndRunAutoBackup()
    }

    override fun onPause() {
        super.onPause()
        activityResumedIntent.onNext(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
    }

    override fun showBackButton(show: Boolean) {
        toggle.onDrawerSlide(drawer, if (show) 1f else 0f)
        toggle.drawerArrowDrawable.color = Color.parseColor("#F9FAFB")
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(this)
    }

    override fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS), 0)
    }

    override fun clearSearch() {
        dismissKeyboard()
        toolbarSearch?.text = null
    }

    override fun clearSelection() {
        conversationsAdapter.clearSelection()
    }

    override fun themeChanged() {
        recyclerView?.scrapViews()
    }

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(this, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        val count = conversations.size
        AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(resources.getQuantityString(R.plurals.dialog_delete_message, count, count))
                .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.onNext(conversations) }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }

    override fun showChangelog(changelog: ChangelogManager.CumulativeChangelog) {
        changelogDialog.show(changelog)
    }

    override fun showArchivedSnackbar() {
        Snackbar.make(drawerLayout, R.string.toast_archived, Snackbar.LENGTH_LONG).apply {
            setAction(R.string.button_undo) { undoArchiveIntent.onNext(Unit) }
            setActionTextColor(Color.parseColor("#0EA5E9"))
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        // قرار دادن دکمه مستقیم «پوشه» در نوار بالای صفحه هنگام انتخاب پیام (همراه با حضور در منوی سه‌نقطه)
        menu?.add(0, MENU_MOVE_CATEGORY, 0, "انتقال به پوشه")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_MOVE_CATEGORY) {
            showMoveConversationsCategoryDialog(currentSelectedIds)
            return true
        }
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun onBackPressed() {
        backPressedSubject.onNext(NavItem.BACK)
    }

}
