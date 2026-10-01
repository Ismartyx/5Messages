package com.goodwy.messages.repository

import android.content.Context
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

    private val BACKUP_DIRECTORY: String
        get() = (context.getExternalFilesDir("Backups") ?: File(context.filesDir, "Backups")).apply { mkdirs() }.absolutePath

    data class Backup(
        val messageCount: Int = 0,
        val messages: List<BackupMessage> = listOf(),
        val preferences: Map<String, String> = mapOf(),
        val manualCategories: Map<String, String> = mapOf()
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

    private data class NextcloudConfig(
        val serverUrl: String,
        val user: String,
        val pass: String,
        val remoteFilePath: String,
        val authHeader: String
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

        val exportedPrefs = exportSharedPrefs("${context.packageName}_preferences", excludePrefix = "nc_")
        val exportedCategories = exportSharedPrefs("manual_message_categories")

        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(Backup(messageCount, backupMessages, exportedPrefs, exportedCategories)).toByteArray()

        try {
            val dir = File(BACKUP_DIRECTORY).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(System.currentTimeMillis())
            val fileName = "backup-$timestamp.json"
            val file = File(dir, fileName)

            FileOutputStream(file, false).use { it.write(json) }

            uploadToNextcloud(json)
        } catch (e: Exception) {
            Timber.w(e)
            backupProgress.onNext(BackupRepository.Progress.Idle())
            // هدایت خطا به سمت رابط کاربری برای نمایش پیغام واقعی
            throw RuntimeException(e.message ?: "خطای ناشناخته در ارتباط با سرور")
        }

        backupProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    private fun exportSharedPrefs(prefsName: String, excludePrefix: String? = null): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            for ((key, value) in sp.all) {
                if (excludePrefix != null && key.startsWith(excludePrefix)) continue
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

    private fun importSharedPrefs(prefsName: String, prefsMap: Map<String, String>?) {
        if (prefsMap.isNullOrEmpty()) return
        try {
            val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
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

    private fun getNextcloudConfig(): NextcloudConfig? {
        val sp = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        val server = (sp.getString("nc_server", "https://nc.paranas.ir") ?: "https://nc.paranas.ir").trim().trimEnd('/')
        val user = (sp.getString("nc_user", "saeed") ?: "saeed").trim()
        val pass = (sp.getString("nc_pass", "") ?: "").trim()
        val path = (sp.getString("nc_path", "Backups/MessagesBackup/Messages_Backup.json")
                ?: "Backups/MessagesBackup/Messages_Backup.json").trim().trimStart('/')

        if (server.isBlank() || user.isBlank() || pass.isBlank() || path.isBlank()) return null
        val auth = "Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return NextcloudConfig(server, user, pass, path, auth)
    }

    private fun uploadToNextcloud(data: ByteArray) {
        val cfg = getNextcloudConfig() ?: throw RuntimeException("لطفاً تنظیمات نکست‌کلاد را کامل کنید")
        
        val baseDav = "${cfg.serverUrl}/remote.php/dav/files/${cfg.user}"
        val segments = cfg.remoteFilePath.split("/").filter { it.isNotBlank() }

        var currentFolderUrl = baseDav
        for (i in 0 until segments.size - 1) {
            currentFolderUrl += "/${segments[i]}"
            val mkConn = (URL(currentFolderUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "MKCOL"
                setRequestProperty("Authorization", cfg.authHeader)
                // اضافه کردن User-Agent برای دور زدن فایروال و Cloudflare
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                connectTimeout = 10000
                readTimeout = 10000
            }
            tryOrNull { mkConn.responseCode }
            mkConn.disconnect()
        }

        val targetFileUrl = "$baseDav/${cfg.remoteFilePath}"
        val conn = (URL(targetFileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Authorization", cfg.authHeader)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            connectTimeout = 15000
            readTimeout = 30000
        }
        conn.outputStream.use { it.write(data) }
        val code = conn.responseCode
        conn.disconnect()
        
        if (code !in 200..299) {
            throw RuntimeException("کد خطای سرور: $code")
        }
    }

    private fun downloadFromNextcloudToFile(throwOnError: Boolean = false): File? {
        val cfg = getNextcloudConfig()
        if (cfg == null) {
            if (throwOnError) throw RuntimeException("لطفاً تنظیمات نکست‌کلاد را کامل کنید")
            return null
        }
        try {
            val targetFileUrl = "${cfg.serverUrl}/remote.php/dav/files/${cfg.user}/${cfg.remoteFilePath}"
            val conn = (URL(targetFileUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", cfg.authHeader)
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                connectTimeout = 15000
                readTimeout = 30000
            }
            
            val code = conn.responseCode
            if (code in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.isNotEmpty()) {
                    val dir = File(BACKUP_DIRECTORY).apply { mkdirs() }
                    val cloudFile = File(dir, "backup-nextcloud-latest.json")
                    FileOutputStream(cloudFile, false).use { it.write(bytes) }
                    return cloudFile
                }
            } else {
                conn.disconnect()
                if (throwOnError) throw RuntimeException("کد خطای سرور: $code")
            }
        } catch (e: Exception) {
            Timber.w(e)
            if (throwOnError) throw RuntimeException(e.message ?: "خطا در دریافت از سرور")
        }
        return null
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
            .doOnNext { downloadFromNextcloudToFile() }
            .map { File(BACKUP_DIRECTORY).listFiles() ?: arrayOf() }
            .observeOn(Schedulers.computation())
            .map { files ->
                files.mapNotNull { file ->
                    val adapter = moshi.adapter(BackupMetadata::class.java)
                    val backup = tryOrNull(false) {
                        file.source().buffer().use(adapter::fromJson)
                    } ?: return@mapNotNull null

                    BackupFile(file.path, file.lastModified(), backup.messageCount, file.length())
                }
            }
            .map { files -> files.sortedByDescending { file -> file.date } }

    override fun performRestore(filePath: String) {
        if (isBackupOrRestoreRunning()) return

        val timer = Timer()
        timer.schedule(10000) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }

        restoreProgress.onNext(BackupRepository.Progress.Parsing())

        try {
            val resolvedFile = if (filePath == "__NEXTCLOUD_DIRECT__") {
                downloadFromNextcloudToFile(throwOnError = true) ?: run {
                    restoreProgress.onNext(BackupRepository.Progress.Idle())
                    throw RuntimeException("فایل در سرور یافت نشد")
                }
            } else {
                File(filePath)
            }

            val backup = resolvedFile.source().buffer().use { source ->
                moshi.adapter(Backup::class.java).fromJson(source)
            }

            importSharedPrefs("${context.packageName}_preferences", backup?.preferences)
            importSharedPrefs("manual_message_categories", backup?.manualCategories)

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
                Timber.w(Exception("Failed to restore $errorCount/$messageCount messages"))
            }

            restoreProgress.onNext(BackupRepository.Progress.Syncing())
            syncRepo.syncMessages()

        } catch (e: Exception) {
            Timber.w(e)
            restoreProgress.onNext(BackupRepository.Progress.Idle())
            throw RuntimeException(e.message ?: "خطا در بازیابی")
        }

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
