package com.goodwy.messages.repository

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Telephony
import android.util.Base64
import androidx.core.content.contentValuesOf
import com.goodwy.messages.model.BackupFile
import com.goodwy.messages.model.Message
import com.goodwy.messages.util.Preferences
import com.goodwy.messages.util.QkFileObserver
import com.goodwy.messages.util.tryOrNull
import com.squareup.moshi.Moshi
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.Realm
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.schedule

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val context: Context,
    private val moshi: Moshi,
    private val prefs: Preferences,
    private val syncRepo: SyncRepository
) : BackupRepository {

    // ذخیره در پوشه اختصاصی برنامه تا در اندروید ۱۱ به بالا هم بدون خطای دسترسی کار کند
    private val BACKUP_DIRECTORY: String
        get() = (context.getExternalFilesDir("Backups") ?: File(context.filesDir, "Backups")).apply { mkdirs() }.absolutePath

    // تنظیمات اتصال مستقیم و انحصاری به سرور Nextcloud شخصی
    companion object {
        private const val NEXTCLOUD_WEBDAV_FOLDER = "https://nc.taha.surf/remote.php/dav/files/saeed/5Messages_Backups"
        private const val DEFAULT_NC_USER = "saeed"
        private const val DEFAULT_NC_PASS = "" // در صورت تمایل App Password نکست‌کلاد را اینجا قرار بده
    }

    data class Backup(
        val messageCount: Int = 0,
        val messages: List<BackupMessage> = listOf(),
        val preferences: Map<String, String> = mapOf()
    )

    data class BackupMetadata(
        val messageCount: Int = 0
    )

    data class BackupMessage(
        val type: Int,
        val address: String,
        val date: Long,
        val dateSent: Long,
        val read: Boolean,
        val status: Int,
        val body: String,
        val protocol: Int,
        val serviceCenter: String?,
        val locked: Boolean,
        val subId: Int
    )

    private val backupProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())
    private val restoreProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())

    @Volatile private var stopFlag: Boolean = false

    override fun performBackup() {
        if (isBackupOrRestoreRunning()) return

        var messageCount = 0

        val backupMessages = Realm.getDefaultInstance().use { realm ->
            val messages = realm.where(Message::class.java).sort("date").findAll().createSnapshot()
            messageCount = messages.size

            messages.mapIndexed { index, message ->
                backupProgress.onNext(BackupRepository.Progress.Running(messageCount, index))
                messageToBackupMessage(message)
            }
        }

        backupProgress.onNext(BackupRepository.Progress.Saving())

        // استخراج تمام تنظیمات برنامه (Preferences) برای ذخیره در کنار پیام‌ها
        val exportedPrefs = exportPreferences()

        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(Backup(messageCount, backupMessages, exportedPrefs)).toByteArray()

        try {
            val dir = File(BACKUP_DIRECTORY).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(System.currentTimeMillis())
            val fileName = "backup-$timestamp.json"
            val file = File(dir, fileName)

            FileOutputStream(file, false).use { fileOutputStream -> fileOutputStream.write(json) }

            // ارسال مستقیم فایل بکاپ به سرور Nextcloud شخصی
            uploadToNextcloud(fileName, json)
        } catch (e: Exception) {
            Timber.w(e)
        }

        backupProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    private fun exportPreferences(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val sp = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            for ((key, value) in sp.all) {
                if (key.startsWith("nc_")) continue // عدم ذخیره پسورد در فایل
                val serialized = when (value) {
                    is Boolean -> "B:$value"
                    is Int -> "I:$value"
                    is Long -> "L:$value"
                    is Float -> "F:$value"
                    is String -> "S:$value"
                    else -> null
                }
                if (serialized != null) result[key] = serialized
            }
        } catch (e: Exception) {
            Timber.w(e)
        }
        return result
    }

    private fun importPreferences(prefsMap: Map<String, String>?) {
        if (prefsMap.isNullOrEmpty()) return
        try {
            val sp = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            val editor = sp.edit()
            for ((key, raw) in prefsMap) {
                when {
                    raw.startsWith("B:") -> editor.putBoolean(key, raw.removePrefix("B:").toBoolean())
                    raw.startsWith("I:") -> raw.removePrefix("I:").toIntOrNull()?.let { editor.putInt(key, it) }
                    raw.startsWith("L:") -> raw.removePrefix("L:").toLongOrNull()?.let { editor.putLong(key, it) }
                    raw.startsWith("F:") -> raw.removePrefix("F:").toFloatOrNull()?.let { editor.putFloat(key, it) }
                    raw.startsWith("S:") -> editor.putString(key, raw.removePrefix("S:"))
                }
            }
            editor.apply()
        } catch (e: Exception) {
            Timber.w(e)
        }
    }

    private fun getNextcloudAuthHeader(): String? {
        val sp = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        val user = sp.getString("nc_user", DEFAULT_NC_USER) ?: DEFAULT_NC_USER
        val pass = sp.getString("nc_pass", DEFAULT_NC_PASS) ?: DEFAULT_NC_PASS
        if (user.isBlank() || pass.isBlank()) return null
        val credentials = "$user:$pass"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun uploadToNextcloud(fileName: String, data: ByteArray) {
        val authHeader = getNextcloudAuthHeader() ?: return
        try {
            // ۱. ساخت پوشه 5Messages_Backups در نکست‌کلاد (اگر وجود نداشته باشد)
            val mkcolConn = (URL(NEXTCLOUD_WEBDAV_FOLDER).openConnection() as HttpURLConnection).apply {
                requestMethod = "MKCOL"
                setRequestProperty("Authorization", authHeader)
                connectTimeout = 10000
                readTimeout = 10000
            }
            tryOrNull { mkcolConn.responseCode }
            mkcolConn.disconnect()

            // ۲. آپلود فایل بکاپ با تاریخ و ساعت
            putBytesToWebDav("$NEXTCLOUD_WEBDAV_FOLDER/$fileName", authHeader, data)

            // ۳. آپلود یک نسخه به نام latest.json برای بازیابی سریع از سرور
            putBytesToWebDav("$NEXTCLOUD_WEBDAV_FOLDER/latest.json", authHeader, data)
        } catch (e: Exception) {
            Timber.w(e, "Nextcloud upload failed")
        }
    }

    private fun putBytesToWebDav(targetUrl: String, authHeader: String, data: ByteArray) {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Authorization", authHeader)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000
            readTimeout = 30000
        }
        conn.outputStream.use { it.write(data) }
        val code = conn.responseCode
        conn.disconnect()
        Timber.i("Nextcloud WebDAV PUT $targetUrl -> HTTP $code")
    }

    private fun syncLatestFromNextcloud() {
        val authHeader = getNextcloudAuthHeader() ?: return
        try {
            val conn = (URL("$NEXTCLOUD_WEBDAV_FOLDER/latest.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", authHeader)
                connectTimeout = 10000
                readTimeout = 20000
            }
            if (conn.responseCode in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    val dir = File(BACKUP_DIRECTORY).apply { mkdirs() }
                    val cloudFile = File(dir, "backup-nextcloud-latest.json")
                    FileOutputStream(cloudFile, false).use { it.write(bytes) }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Timber.w(e, "Nextcloud download failed")
        }
    }

    private fun messageToBackupMessage(message: Message): BackupMessage = BackupMessage(
            type = message.boxId,
            address = message.address,
            date = message.date,
            dateSent = message.dateSent,
            read = message.read,
            status = message.deliveryStatus,
            body = message.body,
            protocol = 0,
            serviceCenter = null,
            locked = message.locked,
            subId = message.subId
    )

    override fun getBackupProgress(): Observable<BackupRepository.Progress> = backupProgress

    override fun getBackups(): Observable<List<BackupFile>> = QkFileObserver(BACKUP_DIRECTORY).observable
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .doOnNext { syncLatestFromNextcloud() }
            .map { File(BACKUP_DIRECTORY).listFiles() ?: arrayOf() }
            .observeOn(Schedulers.computation())
            .map { files ->
                files.mapNotNull { file ->
                    val adapter = moshi.adapter(BackupMetadata::class.java)
                    val backup = tryOrNull(false) {
                        file.source().buffer().use(adapter::fromJson)
                    } ?: return@mapNotNull null

                    val path = file.path
                    val date = file.lastModified()
                    val messages = backup.messageCount
                    val size = file.length()
                    BackupFile(path, date, messages, size)
                }
            }
            .map { files -> files.sortedByDescending { file -> file.date } }

    override fun performRestore(filePath: String) {
        if (isBackupOrRestoreRunning()) return

        val timer = Timer()
        timer.schedule(10000) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }

        restoreProgress.onNext(BackupRepository.Progress.Parsing())

        val file = File(filePath)
        val backup = file.source().buffer().use { source ->
            moshi.adapter(Backup::class.java).fromJson(source)
        }

        // بازیابی تنظیمات برنامه (Preferences)
        importPreferences(backup?.preferences)

        val messageCount = backup?.messages?.size ?: 0
        var errorCount = 0

        backup?.messages?.forEachIndexed { index, message ->
            if (stopFlag) {
                stopFlag = false
                restoreProgress.onNext(BackupRepository.Progress.Idle())
                return
            }

            restoreProgress.onNext(BackupRepository.Progress.Running(messageCount, index))
            timer.cancel()

            try {
                val values = contentValuesOf(
                        Telephony.Sms.TYPE to message.type,
                        Telephony.Sms.ADDRESS to message.address,
                        Telephony.Sms.DATE to message.date,
                        Telephony.Sms.DATE_SENT to message.dateSent,
                        Telephony.Sms.READ to message.read,
                        Telephony.Sms.SEEN to 1,
                        Telephony.Sms.STATUS to message.status,
                        Telephony.Sms.BODY to message.body,
                        Telephony.Sms.PROTOCOL to message.protocol,
                        Telephony.Sms.SERVICE_CENTER to message.serviceCenter,
                        Telephony.Sms.LOCKED to message.locked
                )

                if (prefs.canUseSubId.get()) {
                    values.put(Telephony.Sms.SUBSCRIPTION_ID, message.subId)
                }

                context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            } catch (e: Exception) {
                Timber.w(e)
                errorCount++
            }
        }

        if (errorCount > 0) {
            Timber.w(Exception("Failed to backup $errorCount/$messageCount messages"))
        }

        restoreProgress.onNext(BackupRepository.Progress.Syncing())
        syncRepo.syncMessages()

        restoreProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    override fun stopRestore() {
        stopFlag = true
    }

    override fun getRestoreProgress(): Observable<BackupRepository.Progress> = restoreProgress

    private fun isBackupOrRestoreRunning(): Boolean {
        return backupProgress.blockingFirst().running || restoreProgress.blockingFirst().running
    }

}
