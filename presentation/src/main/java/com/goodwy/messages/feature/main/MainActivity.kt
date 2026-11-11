package com.goodwy.messages.feature.main

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.goodwy.messages.common.util.extensions.resolveThemeColor
import com.goodwy.messages.common.util.extensions.setBackgroundTint
import com.goodwy.messages.common.util.extensions.setTint
import com.goodwy.messages.common.util.extensions.setVisible
import com.goodwy.messages.common.util.TrashManager
import com.goodwy.messages.common.widget.QkTextView
import com.goodwy.messages.feature.blocking.BlockingDialog
import com.goodwy.messages.feature.changelog.ChangelogDialog
import com.goodwy.messages.feature.conversations.ConversationItemTouchCallback
import com.goodwy.messages.feature.conversations.ConversationsAdapter
import com.goodwy.messages.manager.ChangelogManager
import com.goodwy.messages.repository.BackupRepository
import com.goodwy.messages.repository.SyncRepository
import com.goodwy.messages.util.NightModeManager
import com.goodwy.messages.util.Preferences
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MainActivity : QkThemedActivity(), MainView {

    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var drawerBadgesExperiment: DrawerBadgesExperiment
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var itemTouchCallback: ConversationItemTouchCallback
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var backupRepo: BackupRepository
    @Inject lateinit var syncRepo: SyncRepository
    @Inject lateinit var nightModeManager: NightModeManager
    @Inject lateinit var dateFormatter: com.goodwy.messages.common.util.DateFormatter

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
                trash.clicks().map { NavItem.TRASH },
                backup.clicks().doOnNext {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    showNextcloudSettingsDialog()
                }.filter { false }.map { NavItem.BACKUP },
                scheduled.clicks().map { NavItem.SCHEDULED },
                blocking.clicks().map { NavItem.BLOCKING },
                settings.clicks().map { NavItem.SETTINGS },
                webClient.clicks().doOnNext {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    showWebClientDialog()
                }.filter { false }.map { NavItem.SETTINGS },
                appUpdate.clicks().doOnNext {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    checkAppUpdate()
                }.filter { false }.map { NavItem.SETTINGS },
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
    override val undoTrashIntent: Subject<Unit> = PublishSubject.create()
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
    private var currentState: MainState? = null

    companion object {
        private const val MENU_MOVE_CATEGORY = 991
        private const val MENU_NEXTCLOUD_CONFIG = 992
        private const val MENU_MANAGE_CATEGORIES = 993
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        updateStatusBarColor()
        viewModel.bindView(this)
        onNewIntentIntent.onNext(intent)


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

        backup?.setOnLongClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showNextcloudSettingsDialog()
            true
        }

        checkAndRunAutoBackup()

        drawer.clicks().autoDisposable(scope()).subscribe()

        theme
                .autoDisposable(scope())
                .subscribe({
                    updateStatusBarColor()
                    val states = arrayOf(
                            intArrayOf(android.R.attr.state_activated),
                            intArrayOf(-android.R.attr.state_activated))

                    ColorStateList(states, intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#9CA3AF")))
                            .let { tintList ->
                                inboxIcon?.imageTintList = tintList
                                archivedIcon?.imageTintList = tintList
                                trashIcon?.imageTintList = tintList
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
            toolbarSearch?.setBackgroundTint(resolveThemeColor(R.attr.bubbleColor))
        }

        setupSavedNotesView()

        savedNotes?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            viewModel.setCategory("SAVED_NOTES")
        }

        prefs.showAvatars.asObservable()
                .skip(1)
                .observeOn(AndroidSchedulers.mainThread())
                .autoDisposable(scope())
                .subscribe { conversationsAdapter.notifyDataSetChanged() }

        prefs.boldNames.asObservable()
                .skip(1)
                .observeOn(AndroidSchedulers.mainThread())
                .autoDisposable(scope())
                .subscribe { conversationsAdapter.notifyDataSetChanged() }
    }

    private var savedMessagesAdapter: SavedMessagesAdapter? = null
    private var clipboardItemsAdapter: ClipboardItemsAdapter? = null
    private var isSavedSubTabSelected = true

    private fun setupSavedNotesView() {
        val container = savedNotesContainer ?: return

        savedMessagesAdapter = SavedMessagesAdapter(this, dateFormatter) { msg ->
            com.goodwy.messages.common.util.SavedMessagesManager.deleteSavedMessage(this, msg.id)
            refreshSavedMessages()
        }
        val recyclerSaved = container.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerSavedMessages)
        recyclerSaved?.adapter = savedMessagesAdapter

        clipboardItemsAdapter = ClipboardItemsAdapter(this) { item ->
            // Re-copied
        }
        val recyclerClip = container.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerClipboard)
        recyclerClip?.adapter = clipboardItemsAdapter

        val btnTabSaved = container.findViewById<TextView>(R.id.btnTabSavedMessages)
        val btnTabClip = container.findViewById<TextView>(R.id.btnTabClipboard)
        val layoutSaved = container.findViewById<View>(R.id.layoutSavedMessages)
        val layoutClip = container.findViewById<View>(R.id.layoutClipboard)

        fun updateSubTabs(saved: Boolean) {
            isSavedSubTabSelected = saved
            val activeColor = Color.parseColor("#10B981")
            val inactiveBg = resolveThemeColor(R.attr.bubbleColor)
            val textColorSec = resolveThemeColor(android.R.attr.textColorSecondary)

            btnTabSaved?.setBackgroundTint(if (saved) activeColor else inactiveBg)
            btnTabSaved?.setTextColor(if (saved) Color.WHITE else textColorSec)

            btnTabClip?.setBackgroundTint(if (!saved) activeColor else inactiveBg)
            btnTabClip?.setTextColor(if (!saved) Color.WHITE else textColorSec)

            layoutSaved?.visibility = if (saved) View.VISIBLE else View.GONE
            layoutClip?.visibility = if (!saved) View.VISIBLE else View.GONE

            if (saved) refreshSavedMessages() else refreshClipboard()
        }

        btnTabSaved?.setOnClickListener { updateSubTabs(true) }
        btnTabClip?.setOnClickListener { updateSubTabs(false) }

        // Send / Save Note
        val inputMsg = container.findViewById<EditText>(R.id.inputSavedMessage)
        val btnSave = container.findViewById<ImageView>(R.id.btnSaveMessage)
        btnSave?.setOnClickListener {
            val text = inputMsg?.text?.toString().orEmpty().trim()
            if (text.isNotEmpty()) {
                com.goodwy.messages.common.util.SavedMessagesManager.addSavedMessage(this, text)
                inputMsg?.setText("")
                refreshSavedMessages()
                recyclerSaved?.smoothScrollToPosition(0)
            }
        }

        // Add to clipboard manually
        val inputClip = container.findViewById<EditText>(R.id.inputClipboard)
        val btnAddClip = container.findViewById<TextView>(R.id.btnAddClipboard)
        btnAddClip?.setOnClickListener {
            val text = inputClip?.text?.toString().orEmpty().trim()
            if (text.isNotEmpty()) {
                com.goodwy.messages.common.util.SavedMessagesManager.addClipboardItem(this, text, copyToSystem = true)
                inputClip?.setText("")
                Toast.makeText(this, "به کلیپ‌بورد اضافه شد (پاکسازی خودکار تا ۵ دقیقه دیگر)", Toast.LENGTH_SHORT).show()
                refreshClipboard()
            }
        }

        // Clear clipboard now
        val btnClearNow = container.findViewById<TextView>(R.id.btnClearClipboardNow)
        btnClearNow?.setOnClickListener {
            com.goodwy.messages.common.util.SavedMessagesManager.clearClipboard(this)
            Toast.makeText(this, "کلیپ‌بورد به طور کامل پاک شد", Toast.LENGTH_SHORT).show()
            refreshClipboard()
        }

        updateSubTabs(true)
    }

    private fun refreshSavedMessages() {
        val items = com.goodwy.messages.common.util.SavedMessagesManager.getSavedMessages(this)
        savedMessagesAdapter?.items = items
        val empty = savedNotesContainer?.findViewById<View>(R.id.emptySavedMessages)
        empty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshClipboard() {
        val items = com.goodwy.messages.common.util.SavedMessagesManager.getClipboardItems(this)
        clipboardItemsAdapter?.items = items
        val empty = savedNotesContainer?.findViewById<View>(R.id.emptyClipboard)
        empty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderCategoryTabs(selectedCategoryId: String, unreadCounts: Map<String, Int>) {
        val container = categoryContainer ?: return
        container.removeAllViews()

        val categories = viewModel.getSavedCategoryOrder()
        val inactiveBg = resolveThemeColor(R.attr.bubbleColor)

        categories.forEach { cat ->
            val isSelected = cat.id == selectedCategoryId
            val count = unreadCounts[cat.id] ?: 0
            val tabView = QkTextView(this).apply {
                text = if (count > 0) "${cat.name} ($count)" else cat.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                layoutParams = p
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setBackgroundResource(R.drawable.rounded_rectangle_24dp)
                setBackgroundTint(if (isSelected) cat.color else inactiveBg)
                setTextColor(if (isSelected) Color.WHITE else cat.color)
                setOnClickListener {
                    clearSelection()
                    viewModel.setCategory(cat.id)
                }
                setOnLongClickListener {
                    showCategoryOptionsDialog(cat)
                    true
                }
            }
            container.addView(tabView)
        }

        // Plus button: "+ دسته جدید"
        val addBtn = QkTextView(this).apply {
            text = "+ دسته جدید"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            layoutParams = p
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundResource(R.drawable.rounded_rectangle_24dp)
            setBackgroundTint(inactiveBg)
            setTextColor(resolveThemeColor(android.R.attr.textColorSecondary))
            setOnClickListener {
                showAddCategoryDialog()
            }
        }
        container.addView(addBtn)
    }

    private fun showCategoryOptionsDialog(cat: CategoryItem) {
        val options = mutableListOf<String>()
        options.add("✏️ تغییر نام «${cat.name}»")
        options.add("➕ افزودن دسته‌بندی جدید")
        if (cat.isCustom) {
            options.add("🗑️ حذف دسته‌بندی «${cat.name}»")
        }
        options.add("⬅️ جابجایی به چپ")
        options.add("➡️ جابجایی به راست")

        AlertDialog.Builder(this)
                .setTitle("مدیریت پوشه «${cat.name}»")
                .setItems(options.toTypedArray()) { _, which ->
                    val chosen = options[which]
                    when {
                        chosen.startsWith("✏️") -> showRenameCategoryDialog(cat)
                        chosen.startsWith("➕") -> showAddCategoryDialog()
                        chosen.startsWith("🗑️") -> showDeleteCategoryDialog(cat)
                        chosen.startsWith("⬅️") -> moveCategoryRelative(cat, -1)
                        chosen.startsWith("➡️") -> moveCategoryRelative(cat, +1)
                    }
                }
                .setNegativeButton("بستن", null)
                .show()
    }

    private fun showRenameCategoryDialog(cat: CategoryItem) {
        val input = EditText(this).apply {
            setText(cat.name)
            setSelection(cat.name.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }
        AlertDialog.Builder(this)
                .setTitle("تغییر نام دسته‌بندی")
                .setMessage("نام جدید را برای «${cat.name}» وارد کنید:")
                .setView(input)
                .setPositiveButton("ذخیره") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotBlank()) {
                        viewModel.renameCategory(cat.id, newName)
                        triggerAutoBackupIfEnabled()
                        Toast.makeText(this, "نام دسته‌بندی به «$newName» تغییر یافت", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("انصراف", null)
                .show()
    }

    private fun showAddCategoryDialog(onAdded: ((CategoryItem) -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "مثلاً: کاری، خانواده، تبلیغات..."
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 15f
        }
        AlertDialog.Builder(this)
                .setTitle("➕ افزودن دسته‌بندی جدید")
                .setMessage("نام دسته‌بندی را وارد کنید:")
                .setView(input)
                .setPositiveButton("ایجاد") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotBlank()) {
                        val newItem = viewModel.addCategory(name)
                        if (newItem != null) {
                            triggerAutoBackupIfEnabled()
                            Toast.makeText(this, "دسته‌بندی «$name» ایجاد شد", Toast.LENGTH_SHORT).show()
                            onAdded?.invoke(newItem)
                        }
                    }
                }
                .setNegativeButton("انصراف", null)
                .show()
    }

    private fun showDeleteCategoryDialog(cat: CategoryItem) {
        AlertDialog.Builder(this)
                .setTitle("حذف دسته‌بندی")
                .setMessage("آیا از حذف دسته‌بندی «${cat.name}» اطمینان دارید؟ پیامک‌های این پوشه حذف نخواهند شد و به دسته‌بندی پیش‌فرض بازمی‌گردند.")
                .setPositiveButton("حذف") { _, _ ->
                    viewModel.deleteCategory(cat.id)
                    triggerAutoBackupIfEnabled()
                    Toast.makeText(this, "دسته‌بندی «${cat.name}» حذف شد", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("انصراف", null)
                .show()
    }

    private fun moveCategoryRelative(cat: CategoryItem, offset: Int) {
        val current = viewModel.getSavedCategoryOrder()
        val index = current.indexOfFirst { it.id == cat.id }
        if (index != -1) {
            val target = index + offset
            if (target in current.indices) {
                viewModel.moveCategoryPosition(index, target)
                triggerAutoBackupIfEnabled()
            }
        }
    }

    private fun showManageCategoriesDialog() {
        val categories = viewModel.getSavedCategoryOrder()
        val names = categories.map { "${it.name}${if (it.isCustom) " (سفارشی)" else ""}" }.toTypedArray()

        AlertDialog.Builder(this)
                .setTitle("📁 مدیریت دسته‌بندی‌ها")
                .setItems(names) { _, which ->
                    showCategoryOptionsDialog(categories[which])
                }
                .setPositiveButton("➕ دسته‌بندی جدید") { _, _ ->
                    showAddCategoryDialog()
                }
                .setNegativeButton("بستن", null)
                .show()
    }

    private fun updateStatusBarColor() {
        try {
            val isNight = isNightModeActive()
            val bgColor = resolveThemeColor(android.R.attr.windowBackground)
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var flags = window.decorView.systemUiVisibility
                flags = if (isNight) {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = if (isNight) {
                        flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    } else {
                        flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    }
                }
                window.decorView.systemUiVisibility = flags
            }
        } catch (_: Exception) {}
    }

    private fun showMoveConversationsCategoryDialog(selectedThreadIds: List<Long>) {
        if (selectedThreadIds.isEmpty()) return
        val categories = viewModel.getSavedCategoryOrder().filter { it.id != "SAVED_NOTES" }
        val titles = categories.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
                .setTitle("انتقال به کدام پوشه؟")
                .setItems(titles) { _, which ->
                    val targetCategory = categories[which]
                    viewModel.setManualCategoryForConversations(selectedThreadIds, targetCategory.id)
                    clearSelection()
                    triggerAutoBackupIfEnabled()
                    Toast.makeText(this, "به پوشه «${targetCategory.name}» منتقل شد", Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton("➕ دسته‌بندی جدید...") { _, _ ->
                    showAddCategoryDialog { newCat ->
                        viewModel.setManualCategoryForConversations(selectedThreadIds, newCat.id)
                        clearSelection()
                        triggerAutoBackupIfEnabled()
                        Toast.makeText(this, "به پوشه «${newCat.name}» منتقل شد", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton("📌 کپی در پیام‌های ذخیره‌شده") { _, _ ->
                    try {
                        val realm = io.realm.Realm.getDefaultInstance()
                        for (threadId in selectedThreadIds) {
                            val conv = realm.where(com.goodwy.messages.model.Conversation::class.java).equalTo("id", threadId).findFirst()
                            val body = conv?.lastMessage?.body ?: ""
                            if (body.isNotBlank()) {
                                com.goodwy.messages.common.util.SavedMessagesManager.addSavedMessage(this, body)
                            }
                        }
                        realm.close()
                        clearSelection()
                        Toast.makeText(this, "متن در پیام‌های ذخیره‌شده کپی شد", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                .setNegativeButton("انصراف", null)
                .show()
    }

    private fun triggerAutoBackupIfEnabled() {
        try {
            val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
            val autoBackupEnabled = sp.getBoolean("nc_auto_backup", true)
            val pass = sp.getString("nc_pass", "") ?: ""
            if (!autoBackupEnabled || pass.isBlank()) return

            sp.edit().putLong("nc_last_auto_backup", System.currentTimeMillis()).apply()
            Completable.fromAction { backupRepo.performBackup() }
                    .subscribeOn(Schedulers.io())
                    .subscribe({}, {})
        } catch (_: Exception) {}
    }

    private fun checkAndRunAutoBackup() {
        try {
            val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
            val autoBackupEnabled = sp.getBoolean("nc_auto_backup", true)
            val pass = sp.getString("nc_pass", "") ?: ""
            if (!autoBackupEnabled || pass.isBlank()) return

            val lastAutoBackup = sp.getLong("nc_last_auto_backup", 0L)
            val now = System.currentTimeMillis()
            if (now - lastAutoBackup >= TimeUnit.HOURS.toMillis(6)) {
                sp.edit().putLong("nc_last_auto_backup", now).apply()
                Completable.fromAction { backupRepo.performBackup() }
                        .subscribeOn(Schedulers.io())
                        .subscribe({}, {})
            }
        } catch (_: Exception) {}
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeRoundedBg(fillColor: String, strokeColor: String? = null, radiusDp: Int = 12): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillColor))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(1), Color.parseColor(strokeColor))
            }
        }
    }

    private fun showNextcloudSettingsDialog() {
        val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
        val currentServer = sp.getString("nc_server", "") ?: ""
        val currentUser = sp.getString("nc_user", "") ?: ""
        val currentPass = sp.getString("nc_pass", "") ?: ""
        val currentPath = sp.getString("nc_path", "Backups/MessagesBackup/Messages_Backup.json")
                ?: "Backups/MessagesBackup/Messages_Backup.json"
        val currentAuto = sp.getBoolean("nc_auto_backup", true)

        val isNight = isNightModeActive()
        val cardBg = if (isNight) "#0B1220" else "#FFFFFF"
        val cardBorder = if (isNight) "#1E293B" else "#E2E8F0"
        val inputBg = if (isNight) "#090E1A" else "#F8FAFC"
        val inputBorder = if (isNight) "#1E293B" else "#CBD5E1"
        val inputTextColor = if (isNight) Color.WHITE else Color.parseColor("#0F172A")
        val labelTextColor = if (isNight) Color.parseColor("#9CA3AF") else Color.parseColor("#64748B")
        val checkTextColor = if (isNight) Color.WHITE else Color.parseColor("#1E293B")
        val localBtnBg = if (isNight) "#111827" else "#F1F5F9"
        val localBtnText = if (isNight) Color.parseColor("#94A3B8") else Color.parseColor("#475569")

        val scrollView = ScrollView(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = makeRoundedBg(cardBg, cardBorder, 18)
        }
        scrollView.addView(card)

        val header = TextView(this).apply {
            text = "☁️ اتصال خودکار به سرور نکست‌کلاد"
            setTextColor(Color.parseColor("#0EA5E9"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(16))
        }
        card.addView(header)

        fun makeLabel(textStr: String): TextView {
            return TextView(this@MainActivity).apply {
                text = textStr
                setTextColor(labelTextColor)
                textSize = 12f
                gravity = Gravity.RIGHT
                setPadding(dp(4), dp(10), dp(4), dp(6))
            }
        }

        val serverInput = EditText(this).apply {
            setText(currentServer)
            hint = "https://your-nextcloud.com"
            setTextColor(inputTextColor)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedBg(inputBg, inputBorder, 12)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        card.addView(makeLabel("آدرس سرور نکست‌کلاد"))
        card.addView(serverInput)

        val credentialsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, dp(14), 0, 0)
        }

        val passCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        }
        val passInput = EditText(this).apply {
            setText(currentPass)
            hint = "••••••••••••"
            setTextColor(inputTextColor)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedBg(inputBg, inputBorder, 12)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passCol.addView(makeLabel("رمز / App Password"))
        passCol.addView(passInput)

        val userCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
        }
        val userInput = EditText(this).apply {
            setText(currentUser)
            hint = "username"
            setTextColor(inputTextColor)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedBg(inputBg, inputBorder, 12)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        userCol.addView(makeLabel("نام کاربری"))
        userCol.addView(userInput)

        credentialsRow.addView(passCol)
        credentialsRow.addView(userCol)
        card.addView(credentialsRow)

        val pathInput = EditText(this).apply {
            setText(currentPath)
            hint = "Backups/MessagesBackup/Messages_Backup.json"
            setTextColor(inputTextColor)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 13f
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedBg(inputBg, inputBorder, 12)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        card.addView(makeLabel("نام فایل در نکست‌کلاد"))
        card.addView(pathInput)

        val autoCheck = CheckBox(this).apply {
            text = "بکاپ اتوماتیک پس از هر تغییر"
            setTextColor(checkTextColor)
            textSize = 13f
            isChecked = currentAuto
            buttonTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.RIGHT
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
            layoutParams = params
        }
        card.addView(autoCheck)

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val restoreBtn = Button(this).apply {
            text = "☁️ بازیابی از سرور"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = makeRoundedBg("#F59E0B", null, 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
        }

        val saveBackupBtn = Button(this).apply {
            text = "💾 ذخیره و ابری"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = makeRoundedBg("#10B981", null, 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) }
        }

        buttonsRow.addView(restoreBtn)
        buttonsRow.addView(saveBackupBtn)
        card.addView(buttonsRow)

        val dedupBtn = Button(this).apply {
            text = "🧹 حذف پیام‌های تکراری"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = makeRoundedBg("#DC2626", null, 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(12)
            }
        }
        card.addView(dedupBtn)

        val localFilesBtn = Button(this).apply {
            text = "📂 فایل‌های بکاپ محلی"
            setTextColor(localBtnText)
            textSize = 12f
            isAllCaps = false
            background = makeRoundedBg(localBtnBg, cardBorder, 10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(12)
            }
        }
        card.addView(localFilesBtn)

        val dialog = AlertDialog.Builder(this)
                .setView(scrollView)
                .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun saveInputsToPrefs() {
            val srv = serverInput.text.toString().trim()
            val usr = userInput.text.toString().trim()
            val pwd = passInput.text.toString().trim()
            val pth = pathInput.text.toString().trim().ifEmpty { "Backups/MessagesBackup/Messages_Backup.json" }
            val aut = autoCheck.isChecked

            sp.edit()
                    .putString("nc_server", srv)
                    .putString("nc_user", usr)
                    .putString("nc_pass", pwd)
                    .putString("nc_path", pth)
                    .putBoolean("nc_auto_backup", aut)
                    .putLong("nc_last_auto_backup", System.currentTimeMillis())
                    .apply()
        }

saveBackupBtn.setOnClickListener {
            saveInputsToPrefs()
            dialog.dismiss()
            Toast.makeText(this, "ارسال به سرور...", Toast.LENGTH_SHORT).show()
            Completable.fromAction { backupRepo.performBackup() }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        Toast.makeText(this, "✅ در سرور ذخیره شد", Toast.LENGTH_SHORT).show()
                    }, { error ->
                        Toast.makeText(this, "❌ خطا: ${error.message}", Toast.LENGTH_LONG).show()
                    })
        }

        restoreBtn.setOnClickListener {
            saveInputsToPrefs()
            dialog.dismiss()
            Toast.makeText(this, "دریافت از سرور...", Toast.LENGTH_SHORT).show()
            Completable.fromAction {
                backupRepo.performRestore("__NEXTCLOUD_DIRECT__")
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Toast.makeText(this, "✅ اطلاعات بازیابی شد!", Toast.LENGTH_SHORT).show()
            }, { error ->
                Toast.makeText(this, "❌ خطا: ${error.message}", Toast.LENGTH_LONG).show()
            })
        }

        dedupBtn.setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "در حال بررسی و حذف پیام‌های تکراری و ادغام مخاطبین...", Toast.LENGTH_SHORT).show()
            Single.fromCallable {
                val count = backupRepo.deduplicateMessages()
                syncRepo.mergeContactConversations()
                count
            }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ count ->
                        if (count > 0) {
                            Toast.makeText(this, "✅ $count پیام تکراری حذف و مخاطبین ادغام شدند", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "✅ ادغام گفتگوها انجام شد (پیام تکراری یافت نشد)", Toast.LENGTH_SHORT).show()
                        }
                    }, { error ->
                        Toast.makeText(this, "❌ خطا: ${error.message}", Toast.LENGTH_LONG).show()
                    })
        }

        localFilesBtn.setOnClickListener {
            saveInputsToPrefs()
            dialog.dismiss()
            navigator.showBackup()
        }

        dialog.show()
    }

    private fun showWebClientDialog() {
        val isNight = isNightModeActive()
        val cardBg = if (isNight) "#0B1220" else "#FFFFFF"
        val cardBorder = if (isNight) "#1E293B" else "#E2E8F0"
        val inputBg = if (isNight) "#090E1A" else "#F1F5F9"
        val inputBorder = if (isNight) "#1E293B" else "#CBD5E1"
        val textPrimary = if (isNight) Color.WHITE else Color.parseColor("#0F172A")
        val textSecondary = if (isNight) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
        val highlightColor = if (isNight) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")

        val isRunning = com.goodwy.messages.feature.webserver.WebClientHelper.isServiceRunning(this)
        val port = com.goodwy.messages.feature.webserver.WebClientHelper.getPort(this)
        val currentPin = com.goodwy.messages.feature.webserver.WebClientHelper.getPin(this)
        val wifiIp = com.goodwy.messages.feature.webserver.WebClientHelper.getWifiIp()
        var savedTsIp = com.goodwy.messages.feature.webserver.WebClientHelper.getEffectiveTailscaleIp(this)

        val scrollView = ScrollView(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = makeRoundedBg(cardBg, cardBorder, 18)
        }
        scrollView.addView(card)

        val header = TextView(this).apply {
            text = "💻 Web Client (Tailscale / PC)"
            setTextColor(highlightColor)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(header)

        // Status Banner
        val statusText = TextView(this).apply {
            text = if (isRunning) "🟢 وضعیت: در حال اجرا" else "⚪ وضعیت: خاموش"
            setTextColor(if (isRunning) Color.parseColor("#10B981") else textSecondary)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = makeRoundedBg(if (isRunning) (if (isNight) "#064E3B" else "#D1FAE5") else (if (isNight) "#1E293B" else "#F1F5F9"), null, 10)
        }
        card.addView(statusText)

        // Live Health Check
        if (isRunning) {
            val pingStatus = TextView(this).apply {
                text = "⏳ در حال بررسی سلامت سرور داخلی..."
                setTextColor(textSecondary)
                textSize = 11f
                gravity = Gravity.RIGHT
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            card.addView(pingStatus)
            Thread {
                try {
                    val conn = (URL("http://127.0.0.1:$port/ping").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1500
                        readTimeout = 1500
                    }
                    val code = conn.responseCode
                    val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    runOnUiThread {
                        if (code == 200 && text == "pong") {
                            pingStatus.text = "✅ سرور داخلی فعال و آماده اتصال است"
                            pingStatus.setTextColor(Color.parseColor("#10B981"))
                        } else {
                            pingStatus.text = "⚠️ پورت پاسخ نداد (کد $code)"
                            pingStatus.setTextColor(Color.parseColor("#F59E0B"))
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        pingStatus.text = "⚠️ سرور در انتظار درخواست است"
                    }
                }
            }.start()
        }

        // Section 1: Tailscale (Primary & Recommended)
        val tsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedBg(inputBg, inputBorder, 14)
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            }
            layoutParams = p
        }
        val tsLabel = TextView(this).apply {
            text = "⚡ روش اول: اتصال با Tailscale (پیشنهادی)"
            setTextColor(highlightColor)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
        }
        val tsDesc = TextView(this).apply {
            text = "آی‌پی 100 گوشی را از برنامه Tailscale بردارید و وارد کنید:"
            setTextColor(textSecondary)
            textSize = 11f
            gravity = Gravity.RIGHT
            setPadding(0, dp(4), 0, dp(6))
        }
        val tsInput = EditText(this).apply {
            setText(savedTsIp)
            hint = "مثلاً 100.85.20.15"
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            textSize = 14f
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = makeRoundedBg(if (isNight) "#111827" else "#FFFFFF", inputBorder, 8)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val tsUrlPreview = TextView(this).apply {
            val ipToUse = savedTsIp.ifBlank { "100.x.y.z" }
            text = "http://$ipToUse:$port"
            setTextColor(highlightColor)
            textSize = 14f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
            gravity = Gravity.LEFT
        }
        tsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val ip = s?.toString()?.trim() ?: ""
                tsUrlPreview.text = "http://${ip.ifBlank { "100.x.y.z" }}:$port"
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        val copyTsBtn = Button(this).apply {
            text = "📋 کپی آدرس Tailscale برای کامپیوتر"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = makeRoundedBg("#0284C7", null, 8)
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                topMargin = dp(4)
            }
            layoutParams = p
            setOnClickListener {
                val ip = tsInput.text.toString().trim()
                if (ip.isNotBlank()) {
                    com.goodwy.messages.feature.webserver.WebClientHelper.setSavedTailscaleIp(this@MainActivity, ip)
                }
                val urlToCopy = "http://${ip.ifBlank { "100.x.y.z" }}:$port"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("url", urlToCopy))
                Toast.makeText(this@MainActivity, "آدرس کپی شد: $urlToCopy", Toast.LENGTH_SHORT).show()
            }
        }
        tsCard.addView(tsLabel)
        tsCard.addView(tsDesc)
        tsCard.addView(tsInput)
        tsCard.addView(tsUrlPreview)
        tsCard.addView(copyTsBtn)
        card.addView(tsCard)

        // Section 2: Wi-Fi (LAN)
        if (!wifiIp.isNullOrBlank()) {
            val wifiCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = makeRoundedBg(inputBg, inputBorder, 14)
                val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                }
                layoutParams = p
            }
            val wifiLabel = TextView(this).apply {
                text = "📶 روش دوم: اتصال مستقیم از مودم Wi-Fi"
                setTextColor(textPrimary)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.RIGHT
            }
            val wifiUrlText = "http://$wifiIp:$port"
            val wifiUrlView = TextView(this).apply {
                text = wifiUrlText
                setTextColor(highlightColor)
                textSize = 14f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(6))
                gravity = Gravity.LEFT
            }
            val copyWifiBtn = Button(this).apply {
                text = "📋 کپی آدرس Wi-Fi"
                setTextColor(Color.WHITE)
                textSize = 12f
                background = makeRoundedBg("#475569", null, 8)
                val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
                layoutParams = p
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("url", wifiUrlText))
                    Toast.makeText(this@MainActivity, "آدرس کپی شد: $wifiUrlText", Toast.LENGTH_SHORT).show()
                }
            }
            val wifiNote = TextView(this).apply {
                text = "نکته: برای اتصال با وای‌فای، فیلترشکن گوشی را موقتاً قطع کنید."
                setTextColor(textSecondary)
                textSize = 10f
                gravity = Gravity.RIGHT
                setPadding(0, dp(4), 0, 0)
            }
            wifiCard.addView(wifiLabel)
            wifiCard.addView(wifiUrlView)
            wifiCard.addView(copyWifiBtn)
            wifiCard.addView(wifiNote)
            card.addView(wifiCard)
        }

        // Section 3: PIN Setting
        val pinLabel = TextView(this).apply {
            text = "پین‌کد امنیتی ورود (محرمانه):"
            setTextColor(textSecondary)
            textSize = 12f
            gravity = Gravity.RIGHT
            setPadding(dp(4), dp(14), dp(4), dp(6))
        }
        card.addView(pinLabel)

        val pinInput = EditText(this).apply {
            setText(currentPin)
            hint = "••••"
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = makeRoundedBg(inputBg, inputBorder, 12)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        card.addView(pinInput)

        var dialog: AlertDialog? = null

        // Toggle / Action Button
        val toggleBtn = Button(this).apply {
            val runningNow = com.goodwy.messages.feature.webserver.WebClientHelper.isServiceRunning(this@MainActivity)
            text = if (runningNow) "توقف وب‌کلاینت (خاموش کردن)" else "راه‌اندازی وب‌کلاینت (روشن کردن)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = makeRoundedBg(if (runningNow) "#DC2626" else "#059669", null, 12)
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(18)
            }
            layoutParams = p
            setOnClickListener {
                val newPin = pinInput.text.toString().trim().ifBlank { "1234" }
                com.goodwy.messages.feature.webserver.WebClientHelper.setPin(this@MainActivity, newPin)
                val tsVal = tsInput.text.toString().trim()
                if (tsVal.isNotBlank()) {
                    com.goodwy.messages.feature.webserver.WebClientHelper.setSavedTailscaleIp(this@MainActivity, tsVal)
                }
                val isCurrentlyRunning = com.goodwy.messages.feature.webserver.WebClientHelper.isServiceRunning(this@MainActivity)
                if (isCurrentlyRunning) {
                    com.goodwy.messages.feature.webserver.WebClientHelper.stopService(this@MainActivity)
                    Toast.makeText(this@MainActivity, "وب‌کلاینت خاموش شد", Toast.LENGTH_SHORT).show()
                    text = "راه‌اندازی وب‌کلاینت (روشن کردن)"
                    background = makeRoundedBg("#059669", null, 12)
                    statusText.text = "⚪ وضعیت: خاموش"
                    statusText.setTextColor(textSecondary)
                    statusText.background = makeRoundedBg(if (isNight) "#1E293B" else "#F1F5F9", null, 10)
                } else {
                    com.goodwy.messages.feature.webserver.WebClientHelper.startService(this@MainActivity)
                    Toast.makeText(this@MainActivity, "وب‌کلاینت فعال شد", Toast.LENGTH_SHORT).show()
                    text = "توقف وب‌کلاینت (خاموش کردن)"
                    background = makeRoundedBg("#DC2626", null, 12)
                    statusText.text = "🟢 وضعیت: در حال اجرا"
                    statusText.setTextColor(Color.parseColor("#10B981"))
                    statusText.background = makeRoundedBg(if (isNight) "#064E3B" else "#D1FAE5", null, 10)
                }
            }
        }
        card.addView(toggleBtn)

        val closeBtn = Button(this).apply {
            text = "بستن"
            setTextColor(textSecondary)
            textSize = 13f
            background = makeRoundedBg(if (isNight) "#111827" else "#F1F5F9", null, 12)
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                topMargin = dp(8)
            }
            layoutParams = p
            setOnClickListener { dialog?.dismiss() }
        }
        card.addView(closeBtn)

        dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }


    private fun checkAppUpdate() {
        Toast.makeText(this, "در حال بررسی نسخه جدید...", Toast.LENGTH_SHORT).show()
        Single.fromCallable {
            val url = URL("https://api.github.com/repos/Ismartyx/5Messages/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "5Messages-Android")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
            } else {
                throw Exception("HTTP ${conn.responseCode}")
            }
        }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .autoDisposable(scope())
        .subscribe({ json ->
            val latestTag = json.optString("tag_name", "").replace("v", "").trim()
            val currentVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName.replace("v", "").trim()
            } catch (e: Exception) { "1.1.7" }

            if (isNewerVersion(latestTag, currentVersion)) {
                val assetUrl = json.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .find { it.optString("name", "").endsWith(".apk") }
                        ?.optString("browser_download_url")
                } ?: "https://github.com/Ismartyx/5Messages/releases/latest/download/5Messages.apk"

                AlertDialog.Builder(this)
                    .setTitle("🔄 نسخه جدید موجود است")
                    .setMessage("نسخه فعلی شما: $currentVersion\nنسخه جدید: $latestTag\n\nآیا مایل به دانلود و نصب هستید؟")
                    .setPositiveButton("دانلود و به‌روزرسانی") { _, _ ->
                        startAppUpdateDownload(assetUrl, latestTag)
                    }
                    .setNegativeButton("انصراف", null)
                    .show()
            } else {
                Toast.makeText(this, "✅ برنامه شما به‌روز است (نسخه $currentVersion)", Toast.LENGTH_LONG).show()
            }
        }, { error ->
            Toast.makeText(this, "خطا در بررسی نسخه: ${error.message}", Toast.LENGTH_LONG).show()
        })
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        try {
            val lParts = latest.split(".").mapNotNull { it.toIntOrNull() }
            val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(lParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val l = lParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {}
        return false
    }

    private fun startAppUpdateDownload(downloadUrl: String, newVersion: String) {
        Toast.makeText(this, "در حال دریافت نسخه $newVersion در اعلان‌ها...", Toast.LENGTH_LONG).show()

        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("5Messages v$newVersion")
                setDescription("در حال دانلود به‌روزرسانی...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "5Messages-v$newVersion.apk")
                setMimeType("application/vnd.android.package-archive")
            }
            dm.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در دانلود خودکار: ${e.message} - باز کردن لینک در مرورگر...", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            } catch (ex: Exception) {
                // ignore
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.run(onNewIntentIntent::onNext)
    }

    override fun render(state: MainState) {
        currentState = state
        if (state.hasError) {
            finish()
            return
        }

        updateStatusBarColor()

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
            is Trash -> state.page.selected
            else -> 0
        }

        toolbarSearch?.setVisible(state.page is Inbox && state.page.selected == 0 || state.page is Searching)
        toolbarTitle?.setVisible(toolbarSearch?.visibility != View.VISIBLE)
        categoryScroll?.setVisible(state.page is Inbox && state.page.selected == 0)

        toolbar?.menu?.findItem(R.id.archive)?.isVisible = state.page is Inbox && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unarchive)?.isVisible = state.page is Archived && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.restore)?.isVisible = state.page is Trash && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.emptyTrash)?.isVisible = state.page is Trash && selectedConversations == 0
        toolbar?.menu?.findItem(R.id.delete)?.isVisible = selectedConversations != 0
        toolbar?.menu?.findItem(R.id.add)?.isVisible = addContact && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.pin)?.isVisible = markPinned && selectedConversations != 0 && state.page !is Trash
        toolbar?.menu?.findItem(R.id.unpin)?.isVisible = !markPinned && selectedConversations != 0 && state.page !is Trash
        toolbar?.menu?.findItem(R.id.read)?.isVisible = markRead && selectedConversations != 0 && state.page !is Trash
        toolbar?.menu?.findItem(R.id.unread)?.isVisible = !markRead && selectedConversations != 0 && state.page !is Trash
        toolbar?.menu?.findItem(R.id.block)?.isVisible = selectedConversations != 0 && state.page !is Trash
        toolbar?.menu?.findItem(MENU_MOVE_CATEGORY)?.isVisible = state.page is Inbox && selectedConversations != 0
        toolbar?.menu?.findItem(MENU_NEXTCLOUD_CONFIG)?.isVisible = selectedConversations == 0

        listOf(plusBadge1, plusBadge2).forEach { badge ->
            badge?.isVisible = false
        }
        plus?.isVisible = false
        plusBanner?.isVisible = false
        rateLayout?.setVisible(state.showRating)

        compose?.setVisible(state.page is Inbox || state.page is Archived)
        compose?.animate()?.rotation(if (state.drawerOpen) 90f else 0f)?.start()
        conversationsAdapter.emptyView = empty.takeIf { state.page is Inbox || state.page is Archived || state.page is Trash }
        searchAdapter.emptyView = empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                showBackButton(state.page.selected > 0)
                if (state.page.selected > 0) {
                    toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                    toolbar?.navigationIcon?.setTint(resolveThemeColor(android.R.attr.textColorSecondary))
                    toolbar?.setBackgroundResource(R.drawable.rounded_rectangle_transparent_24dp)
                    compose?.animate()?.rotation(90f)?.start()
                } else {
                    toolbar?.setNavigationIcon(R.drawable.ic_menu_24dp)
                    toolbar?.navigationIcon?.setTint(resolveThemeColor(android.R.attr.textColorSecondary))
                    toolbar?.setBackgroundResource(R.drawable.rounded_rectangle_24dp)
                    toolbar?.setBackgroundTint(resolveThemeColor(R.attr.bubbleColor))
                    toolbar?.elevation = prefs.searchElevation.get().toFloat()
                    compose?.animate()?.rotation(0f)?.start()
                }
                title = getString(R.string.main_title_selected, state.page.selected)
                if (state.page.categoryId == "SAVED_NOTES") {
                    recyclerView?.visibility = View.GONE
                    empty?.visibility = View.GONE
                    compose?.visibility = View.GONE
                    savedNotesContainer?.visibility = View.VISIBLE
                    if (isSavedSubTabSelected) refreshSavedMessages() else refreshClipboard()
                } else {
                    savedNotesContainer?.visibility = View.GONE
                    recyclerView?.visibility = View.VISIBLE
                    compose?.visibility = View.VISIBLE
                    if (recyclerView?.adapter !== conversationsAdapter) recyclerView?.adapter = conversationsAdapter
                    conversationsAdapter.updateData(state.page.data)
                    itemTouchHelper.attachToRecyclerView(recyclerView)
                    empty?.setText(R.string.inbox_empty_text)
                }

                val activeCategory = CategoryManager.getCategories(this).firstOrNull { it.id == state.page.categoryId }
                val activeCategoryColor = activeCategory?.color ?: Color.parseColor("#0EA5E9")

                compose?.setBackgroundTint(activeCategoryColor)
                compose?.setTint(Color.WHITE)

                renderCategoryTabs(state.page.categoryId, state.page.unreadCounts)
            }

            is Searching -> {
                savedNotesContainer?.visibility = View.GONE
                showBackButton(true)
                toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                toolbar?.navigationIcon?.setTint(resolveThemeColor(android.R.attr.textColorSecondary))
                if (recyclerView?.adapter !== searchAdapter) recyclerView?.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                itemTouchHelper.attachToRecyclerView(null)
                empty?.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                savedNotesContainer?.visibility = View.GONE
                showBackButton(state.page.selected > 0)
                if (state.page.selected > 0) {
                    toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                    toolbar?.navigationIcon?.setTint(resolveThemeColor(android.R.attr.textColorSecondary))
                    compose?.animate()?.rotation(90f)?.start()
                } else {
                    toolbar?.setNavigationIcon(R.drawable.ic_menu_24dp)
                    toolbar?.navigationIcon?.setTint(resolveThemeColor(android.R.attr.textColorSecondary))
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

            is Trash -> {
                savedNotesContainer?.visibility = View.GONE
                showBackButton(state.page.selected > 0)
                if (state.page.selected > 0) {
                    toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                    toolbar?.navigationIcon?.setTint(resolveThemeColor(android.R.attr.textColorSecondary))
                    compose?.animate()?.rotation(90f)?.start()
                } else {
                    toolbar?.setNavigationIcon(R.drawable.ic_menu_24dp)
                    toolbar?.navigationIcon?.setTint(resolveThemeColor(android.R.attr.textColorSecondary))
                    compose?.animate()?.rotation(0f)?.start()
                }
                toolbar?.setBackgroundResource(R.drawable.rounded_rectangle_transparent_24dp)
                title = when (state.page.selected != 0) {
                    true -> getString(R.string.main_title_selected, state.page.selected)
                    false -> getString(R.string.drawer_trash)
                }
                if (recyclerView?.adapter !== conversationsAdapter) recyclerView?.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(null)
                empty?.setText(R.string.trash_empty_text)
            }
        }

        inbox?.isActivated = state.page is Inbox
        archived?.isActivated = state.page is Archived
        trash?.isActivated = state.page is Trash

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
        updateStatusBarColor()
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
        toggle.drawerArrowDrawable.color = resolveThemeColor(android.R.attr.textColorSecondary)
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
        val isTrash = currentState?.page is Trash
        val titleRes = if (isTrash) R.string.dialog_delete_permanent_title else R.string.dialog_delete_title
        val message = if (isTrash) {
            getString(R.string.dialog_delete_permanent_message)
        } else {
            resources.getQuantityString(R.plurals.dialog_delete_message, count, count)
        }
        AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(message)
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

    override fun showTrashSnackbar(conversations: List<Long>) {
        val count = conversations.size
        val message = resources.getQuantityString(R.plurals.conversations_moved_to_trash, count, count)
        Snackbar.make(drawerLayout, message, Snackbar.LENGTH_LONG).apply {
            setAction(R.string.button_undo) {
                TrashManager.restoreConversations(this@MainActivity, conversations)
                viewModel.refreshInboxCategory()
                undoTrashIntent.onNext(Unit)
            }
            setActionTextColor(Color.parseColor("#0EA5E9"))
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu?.add(0, MENU_MOVE_CATEGORY, 0, "انتقال به پوشه")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu?.add(0, MENU_NEXTCLOUD_CONFIG, 0, "تنظیمات بکاپ Nextcloud")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu?.add(0, MENU_MANAGE_CATEGORIES, 0, "مدیریت دسته‌بندی‌ها")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.emptyTrash) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_empty_trash_title)
                .setMessage(R.string.dialog_empty_trash_message)
                .setPositiveButton(R.string.button_delete) { _, _ ->
                    viewModel.emptyTrash()
                }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
            return true
        }
        if (item.itemId == MENU_MOVE_CATEGORY) {
            showMoveConversationsCategoryDialog(currentSelectedIds)
            return true
        }
        if (item.itemId == MENU_NEXTCLOUD_CONFIG) {
            showNextcloudSettingsDialog()
            return true
        }
        if (item.itemId == MENU_MANAGE_CATEGORIES) {
            showManageCategoriesDialog()
            return true
        }
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun onBackPressed() {
        backPressedSubject.onNext(NavItem.BACK)
    }

}
