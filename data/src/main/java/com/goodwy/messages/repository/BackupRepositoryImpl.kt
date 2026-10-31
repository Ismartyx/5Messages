package com.goodwy.messages.repository

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import android.util.Base64
import androidx.core.content.contentValuesOf
import com.goodwy.messages.extensions.anyOf
import com.goodwy.messages.model.BackupFile
import com.goodwy.messages.model.BlockedNumber
import com.goodwy.messages.model.BlockedRegex
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
        val manualCategories: Map<String, String> = mapOf(),
        val blockedNumbers: List<String> = listOf(),
        val blockedRegexps: List<String> = listOf()
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

        val blockedNumbers = Realm.getDefaultInstance().use { realm ->
            realm.where(BlockedNumber::class.java).findAll().map { it.address }
        }
        val blockedRegexps = Realm.getDefaultInstance().use { realm ->
            realm.where(BlockedRegex::class.java).findAll().map { it.regex }
        }

        val exportedPrefs = exportSharedPrefs("${context.packageName}_preferences", excludeKeys = setOf("nc_pass"))
        val exportedCategories = exportSharedPrefs("manual_message_categories")

        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val backupData = Backup(
            messageCount = messageCount,
            messages = backupMessages,
            preferences = exportedPrefs,
            manualCategories = exportedCategories,
            blockedNumbers = blockedNumbers,
            blockedRegexps = blockedRegexps
        )
        val json = adapter.toJson(backupData).toByteArray()

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
            throw RuntimeException(e.message ?: "خطای ناشناخته")
        }

        backupProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    private fun exportSharedPrefs(prefsName: String, excludeKeys: Set<String> = emptySet()): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            for ((key, value) in sp.all) {
                if (excludeKeys.contains(key)) continue
                val serialized = when (value) {
                    is Boolean -> "B:$value"
                    is Int -> "I:$value"
                    is Long -> "L:$value"
                    is Float -> "F:$value"
                    is String -> "S:$value"
                    is Set<*> -> "SET:" + (value as Set<*>).joinToString(";;;")
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
                    raw.startsWith("SET:") -> {
                        val set = raw.removePrefix("SET:").split(";;;").filter { it.isNotEmpty() }.toSet()
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.commit()
        } catch (e: Exception) {
            Timber.w(e)
        }
    }

    private fun getNextcloudConfig(): NextcloudConfig? {
        val sp = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        val server = (sp.getString("nc_server", "") ?: "").trim().trimEnd('/')
        val user = (sp.getString("nc_user", "") ?: "").trim()
        val pass = (sp.getString("nc_pass", "") ?: "").trim()
        val path = (sp.getString("nc_path", "Backups/MessagesBackup/Messages_Backup.json")
                ?: "Backups/MessagesBackup/Messages_Backup.json").trim().trimStart('/')

        if (server.isBlank() || user.isBlank() || pass.isBlank() || path.isBlank()) return null
        val auth = "Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return NextcloudConfig(server, user, pass, path, auth)
    }

    private fun uploadToNextcloud(data: ByteArray) {
        val cfg = getNextcloudConfig() ?: throw RuntimeException("لطفاً تنظیمات نکست‌کلاد را کامل کنید")
        
        val safePath = cfg.remoteFilePath.trimStart('/')
        val targetFileUrl = "${cfg.serverUrl}/remote.php/dav/files/${cfg.user}/$safePath"
        
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
            val safePath = cfg.remoteFilePath.trimStart('/')
            val targetFileUrl = "${cfg.serverUrl}/remote.php/dav/files/${cfg.user}/$safePath"
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

            // Clean up existing duplicates before restoring
            deduplicateMessages()

            // Fetch existing messages from system ContentProvider to never insert duplicates
            val existingMessages = HashSet<String>()
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE
            )
            tryOrNull {
                context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                    val addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                    val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                    val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                    val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)

                    while (cursor.moveToNext()) {
                        val addr = if (addressIdx != -1) cursor.getString(addressIdx).orEmpty() else ""
                        val date = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                        val body = if (bodyIdx != -1) cursor.getString(bodyIdx).orEmpty() else ""
                        val type = if (typeIdx != -1) cursor.getInt(typeIdx) else -1
                        existingMessages.add("$addr|$date|$type|$body")
                    }
                }
            }

            // Restore preferences and manual categories
            importSharedPrefs("${context.packageName}_preferences", backup?.preferences)
            importSharedPrefs("manual_message_categories", backup?.manualCategories)

            // Restore blocked numbers
            if (!backup?.blockedNumbers.isNullOrEmpty()) {
                tryOrNull {
                    Realm.getDefaultInstance().use { realm ->
                        val currentBlocked = realm.where(BlockedNumber::class.java).findAll()
                        val toAdd = backup!!.blockedNumbers.filter { addr ->
                            currentBlocked.none { it.address == addr }
                        }
                        if (toAdd.isNotEmpty()) {
                            val maxId = realm.where(BlockedNumber::class.java).max("id")?.toLong() ?: -1L
                            realm.executeTransaction {
                                realm.insert(toAdd.mapIndexed { idx, addr ->
                                    BlockedNumber(maxId + 1 + idx, addr)
                                })
                            }
                        }
                    }
                }
            }

            // Restore blocked regexps
            if (!backup?.blockedRegexps.isNullOrEmpty()) {
                tryOrNull {
                    Realm.getDefaultInstance().use { realm ->
                        val currentRegexps = realm.where(BlockedRegex::class.java).findAll()
                        val toAdd = backup!!.blockedRegexps.filter { reg ->
                            currentRegexps.none { it.regex == reg }
                        }
                        if (toAdd.isNotEmpty()) {
                            val maxId = realm.where(BlockedRegex::class.java).max("id")?.toLong() ?: -1L
                            realm.executeTransaction {
                                realm.insert(toAdd.mapIndexed { idx, reg ->
                                    BlockedRegex(maxId + 1 + idx, reg)
                                })
                            }
                        }
                    }
                }
            }

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

                val msgKey = "${message.address}|${message.date}|${message.type}|${message.body}"
                if (existingMessages.contains(msgKey)) {
                    // Message already exists in system SMS, do not duplicate!
                    return@forEachIndexed
                }
                existingMessages.add(msgKey)

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

            // Deduplicate again after insertion
            deduplicateMessages()

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

    private data class SmsDupEntry(
        val id: Long,
        val normAddr: String,
        val date: Long,
        val type: Int,
        val body: String
    )

    override fun deduplicateMessages(): Int {
        var deletedCount = 0
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE
        )
        val seenEntries = ArrayList<SmsDupEntry>()
        val idsToDelete = ArrayList<Long>()

        tryOrNull {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                val addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)

                while (cursor.moveToNext()) {
                    val id = if (idIdx != -1) cursor.getLong(idIdx) else continue
                    val addr = if (addressIdx != -1) cursor.getString(addressIdx).orEmpty() else ""
                    val date = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                    val body = if (bodyIdx != -1) cursor.getString(bodyIdx).orEmpty().trim() else ""
                    val type = if (typeIdx != -1) cursor.getInt(typeIdx) else -1
                    val normAddr = addr.replace(Regex("[^0-9+]"), "").takeLast(10)

                    val isDup = seenEntries.any { seen ->
                        seen.type == type &&
                        seen.body == body &&
                        (seen.normAddr == normAddr || (normAddr.isNotEmpty() && seen.normAddr.endsWith(normAddr))) &&
                        Math.abs(seen.date - date) < 60000L
                    }

                    if (isDup) {
                        idsToDelete.add(id)
                    } else {
                        seenEntries.add(SmsDupEntry(id, normAddr, date, type, body))
                    }
                }
            }
        }

        if (idsToDelete.isNotEmpty()) {
            idsToDelete.forEach { id ->
                tryOrNull {
                    val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
                    val count = context.contentResolver.delete(uri, null, null)
                    deletedCount += count
                }
            }

            tryOrNull {
                Realm.getDefaultInstance().use { realm ->
                    realm.executeTransaction {
                        val toDeleteInRealm = realm.where(Message::class.java)
                            .equalTo("type", "sms")
                            .anyOf("contentId", idsToDelete.toLongArray())
                            .findAll()
                        toDeleteInRealm.deleteAllFromRealm()
                    }
                }
            }
        }

        tryOrNull {
            Realm.getDefaultInstance().use { realm ->
                val allMessages = realm.where(Message::class.java).sort("date").findAll()
                val realmSeen = ArrayList<SmsDupEntry>()
                val realmIdsToDelete = ArrayList<Long>()
                for (msg in allMessages) {
                    val body = msg.body.trim()
                    val normAddr = msg.address.replace(Regex("[^0-9+]"), "").takeLast(10)
                    val isDup = realmSeen.any { seen ->
                        seen.type == msg.boxId &&
                        seen.body == body &&
                        (seen.normAddr == normAddr || (normAddr.isNotEmpty() && seen.normAddr.endsWith(normAddr))) &&
                        Math.abs(seen.date - msg.date) < 60000L
                    }
                    if (isDup) {
                        realmIdsToDelete.add(msg.id)
                    } else {
                        realmSeen.add(SmsDupEntry(msg.id, normAddr, msg.date, msg.boxId, body))
                    }
                }
                if (realmIdsToDelete.isNotEmpty()) {
                    realm.executeTransaction {
                        realm.where(Message::class.java)
                            .anyOf("id", realmIdsToDelete.toLongArray())
                            .findAll()
                            .deleteAllFromRealm()
                    }
                    if (deletedCount == 0) {
                        deletedCount = realmIdsToDelete.size
                    }
                }
            }
        }

        // Immediately trigger sync so Realm conversations, snippets, and UI listeners update
        tryOrNull {
            syncRepo.syncMessages()
        }

        return deletedCount
    }

    override fun stopRestore() {
        stopFlag = true
    }

    override fun getRestoreProgress(): Observable<BackupRepository.Progress> = restoreProgress

    private fun isBackupOrRestoreRunning(): Boolean {
        return backupProgress.blockingFirst().running || restoreProgress.blockingFirst().running
    }

}
