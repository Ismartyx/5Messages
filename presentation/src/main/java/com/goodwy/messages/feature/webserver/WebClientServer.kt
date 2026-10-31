package com.goodwy.messages.feature.webserver

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.goodwy.messages.interactor.SendMessage
import com.goodwy.messages.model.Conversation
import com.goodwy.messages.model.Message
import com.goodwy.messages.repository.ConversationRepository
import com.goodwy.messages.repository.MessageRepository
import io.realm.Realm
import io.realm.Sort
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class WebClientServer(
    private val context: Context,
    private val port: Int,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val sendMessage: SendMessage? = null,
    private val getPin: () -> String
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newFixedThreadPool(4)
    private val activeTokens = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress("0.0.0.0", port))
                }
                Timber.i("WebClientServer started on 0.0.0.0:$port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleConnection(socket) }
                }
            } catch (e: Exception) {
                if (isRunning) Timber.e(e, "WebClientServer error")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignored
        }
        executor.shutdownNow()
        Timber.i("WebClientServer stopped")
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 15000
            try { socket.setSoLinger(true, 3) } catch (e: Exception) {}
            val rawInput = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(rawInput) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].toUpperCase()
            val fullPath = parts[1]
            val path = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
            val queryString = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""

            val headers = mutableMapOf<String, String>()
            var line = readLine(rawInput)
            while (!line.isNullOrEmpty()) {
                val colon = line.indexOf(':')
                if (colon != -1) {
                    val k = line.substring(0, colon).trim().toLowerCase()
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                }
                line = readLine(rawInput)
            }

            // Read Body as raw BYTES according to Content-Length
            var body = ""
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val bodyBytes = ByteArray(contentLength)
                var bytesRead = 0
                while (bytesRead < contentLength) {
                    val count = rawInput.read(bodyBytes, bytesRead, contentLength - bytesRead)
                    if (count == -1) break
                    bytesRead += count
                }
                body = String(bodyBytes, 0, bytesRead, Charsets.UTF_8)
            }

            // CORS preflight
            if (method == "OPTIONS") {
                sendResponse(output, 204, "No Content", "text/plain", "")
                return
            }

            // Routes
            when {
                // Ping test
                (method == "GET" && path == "/ping") -> {
                    sendResponse(output, 200, "OK", "text/plain", "pong")
                }

                // Serve HTML SPA
                (method == "GET" && (path == "/" || path == "/index.html")) -> {
                    val html = WebClientHtml.getHtml(context)
                    sendResponse(output, 200, "OK", "text/html; charset=utf-8", html)
                }

                // Login with PIN
                (method == "POST" && path == "/api/login") -> {
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val inputPin = json.optString("pin", "")
                    val correctPin = getPin().ifBlank { "123456" }
                    if (inputPin == correctPin) {
                        val token = UUID.randomUUID().toString()
                        activeTokens.add(token)
                        val res = JSONObject().apply {
                            put("success", true)
                            put("token", token)
                        }
                        sendResponse(output, 200, "OK", "application/json; charset=utf-8", res.toString())
                    } else {
                        val res = JSONObject().apply { put("success", false) }
                        sendResponse(output, 401, "Unauthorized", "application/json; charset=utf-8", res.toString())
                    }
                }

                // Authenticated endpoints below
                else -> {
                    val authHeader = headers["authorization"] ?: ""
                    val queryToken = parseQuery(queryString)["token"] ?: ""
                    val isAuth = when {
                        authHeader.startsWith("Bearer ") && activeTokens.contains(authHeader.removePrefix("Bearer ").trim()) -> true
                        queryToken.isNotBlank() && activeTokens.contains(queryToken) -> true
                        else -> false
                    }

                    if (!isAuth) {
                        val res = JSONObject().apply {
                            put("error", "Unauthorized")
                            put("authenticated", false)
                        }
                        sendResponse(output, 401, "Unauthorized", "application/json; charset=utf-8", res.toString())
                        return
                    }

                    handleAuthenticatedRequest(method, path, queryString, body, output)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error handling client connection")
        } finally {
            try { socket.shutdownOutput() } catch (e: Exception) {}
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun handleAuthenticatedRequest(
        method: String,
        path: String,
        query: String,
        body: String,
        output: OutputStream
    ) {
        when {
            // Check auth status
            (method == "GET" && path == "/api/status") -> {
                val res = JSONObject().apply {
                    put("authenticated", true)
                    put("app", "5Messages")
                    put("version", "1.3.0")
                }
                sendResponse(output, 200, "OK", "application/json; charset=utf-8", res.toString())
            }

            // List conversations
            (method == "GET" && path == "/api/conversations") -> {
                val list = JSONArray()
                try {
                    Realm.getDefaultInstance().use { realm ->
                        val results = realm.where(Conversation::class.java)
                            .equalTo("archived", false)
                            .equalTo("blocked", false)
                            .sort("lastMessage.date", Sort.DESCENDING)
                            .findAll()

                        for (c in results.take(150)) {
                            val lastMsg = c.lastMessage
                            val item = JSONObject().apply {
                                put("id", c.id)
                                put("title", c.getTitle())
                                put("snippet", lastMsg?.getText() ?: "")
                                put("date", lastMsg?.date ?: 0L)
                                put("unread", lastMsg?.read == false)
                                put("address", c.recipients.firstOrNull()?.address ?: "")
                            }
                            list.put(item)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error fetching conversations")
                }
                sendResponse(output, 200, "OK", "application/json; charset=utf-8", list.toString())
            }

            // List messages in thread
            (method == "GET" && path == "/api/messages") -> {
                val threadId = parseQuery(query)["threadId"]?.toLongOrNull() ?: 0L
                val list = JSONArray()
                try {
                    Realm.getDefaultInstance().use { realm ->
                        val results = realm.where(Message::class.java)
                            .equalTo("threadId", threadId)
                            .sort("date", Sort.ASCENDING)
                            .findAll()

                        for (m in results.takeLast(250)) {
                            val item = JSONObject().apply {
                                put("id", m.id)
                                put("threadId", m.threadId)
                                put("body", m.getText())
                                put("date", m.date)
                                put("isMe", m.isMe())
                                put("address", m.address)
                            }
                            list.put(item)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error fetching messages")
                }
                sendResponse(output, 200, "OK", "application/json; charset=utf-8", list.toString())
            }

            // Send SMS
            (method == "POST" && path == "/api/send") -> {
                try {
                    val json = JSONObject(body)
                    val threadId = json.optLong("threadId", 0L)
                    var address = json.optString("address", "").trim()
                    val text = json.getString("body")

                    if (address.isBlank() && threadId != 0L) {
                        try {
                            Realm.getDefaultInstance().use { realm ->
                                address = realm.where(Conversation::class.java)
                                    .equalTo("id", threadId)
                                    .findFirst()?.recipients?.firstOrNull()?.address ?: ""
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error finding recipient address for thread $threadId")
                        }
                    }

                    if (address.isBlank()) {
                        val res = JSONObject().apply {
                            put("success", false)
                            put("error", "آدرس گیرنده یافت نشد")
                        }
                        sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", res.toString())
                        return
                    }

                    // Look up latest subId for this thread (or -1)
                    val subId = try {
                        Realm.getDefaultInstance().use { realm ->
                            realm.where(Message::class.java)
                                .equalTo("threadId", threadId)
                                .sort("date", Sort.DESCENDING)
                                .findFirst()?.subId ?: -1
                        }
                    } catch (e: Exception) { -1 }

                    if (sendMessage != null) {
                        sendMessage.execute(
                            SendMessage.Params(
                                subId = subId,
                                threadId = threadId,
                                addresses = listOf(address),
                                body = text
                            )
                        )
                    } else {
                        mainHandler.post {
                            messageRepo.sendMessage(
                                subId = subId,
                                threadId = threadId,
                                addresses = listOf(address),
                                body = text,
                                attachments = emptyList()
                            )
                        }
                    }

                    val res = JSONObject().apply { put("success", true) }
                    sendResponse(output, 200, "OK", "application/json; charset=utf-8", res.toString())
                } catch (e: Exception) {
                    Timber.e(e, "Error parsing send payload")
                    val res = JSONObject().apply {
                        put("success", false)
                        put("error", e.message ?: "Unknown error")
                    }
                    sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", res.toString())
                }
            }

            // Poll for new messages
            (method == "GET" && path == "/api/poll") -> {
                val since = parseQuery(query)["since"]?.toLongOrNull() ?: (System.currentTimeMillis() - 5000)
                val list = JSONArray()
                try {
                    Realm.getDefaultInstance().use { realm ->
                        val results = realm.where(Message::class.java)
                            .greaterThan("date", since)
                            .sort("date", Sort.ASCENDING)
                            .findAll()

                        for (m in results.take(50)) {
                            val item = JSONObject().apply {
                                put("id", m.id)
                                put("threadId", m.threadId)
                                put("body", m.getText())
                                put("date", m.date)
                                put("isMe", m.isMe())
                                put("address", m.address)
                            }
                            list.put(item)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error polling messages")
                }
                sendResponse(output, 200, "OK", "application/json; charset=utf-8", list.toString())
            }

            else -> {
                sendResponse(output, 404, "Not Found", "text/plain", "Not Found")
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var hasData = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (hasData) sb.toString() else null
            }
            hasData = true
            if (b == 10) { // '\n'
                val len = sb.length
                if (len > 0 && sb[len - 1] == '\r') {
                    sb.setLength(len - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun sendResponse(
        output: OutputStream,
        code: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isBlank()) return map
        query.split("&").forEach { pair ->
            val idx = pair.indexOf("=")
            if (idx != -1) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }
}
