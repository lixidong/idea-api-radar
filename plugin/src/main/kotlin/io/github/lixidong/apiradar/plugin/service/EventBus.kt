package io.github.lixidong.apiradar.plugin.service

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import io.github.lixidong.apiradar.plugin.model.HttpEvent
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 本地 TCP server，接收 agent 上报的 HttpEvent JSON，同时向 agent 下发 control 指令。
 *
 * <p>采用应用级 service（单例），整个 IDE 会话共享一个端口与事件流，
 * 多个项目窗口都能订阅同一份数据。
 */
@Service(Service.Level.APP)
class EventBus : Disposable {

    private val log = thisLogger()
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<(HttpEvent) -> Unit>()
    private val clients = CopyOnWriteArrayList<ClientChannel>()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    fun start(preferredPort: Int = 7777): Int {
        if (running.get()) return port
        val socket = ServerSocket(preferredPort)
        serverSocket = socket
        port = socket.localPort
        running.set(true)

        thread(name = "api-radar-server", isDaemon = true) {
            log.info("EventBus listening on $port")
            while (running.get()) {
                try {
                    val client = socket.accept()
                    handleClient(client)
                } catch (e: Exception) {
                    if (running.get()) {
                        log.warn("EventBus accept failed", e)
                    }
                }
            }
        }
        return port
    }

    private fun handleClient(client: Socket) {
        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
        val channel = ClientChannel(client, writer)
        clients.add(channel)

        // 连接建立时立刻把当前配置推过去，保证 agent/plugin 状态一致
        ApiRadarSettings.getInstance().toControlJson()?.let { channel.send(it) }

        thread(name = "api-radar-client-${client.port}", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)).use { reader ->
                    log.info("agent connected from ${client.remoteSocketAddress}")
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        try {
                            val event = gson.fromJson(line, HttpEvent::class.java)
                            fireEvent(event)
                        } catch (e: Exception) {
                            log.warn("parse event failed: $line", e)
                        }
                    }
                }
            } finally {
                clients.remove(channel)
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    /** 向所有已连接的 agent 广播一条 control JSON。 */
    fun broadcastControl(json: String) {
        clients.forEach { it.send(json) }
    }

    fun addListener(listener: (HttpEvent) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (HttpEvent) -> Unit) {
        listeners.remove(listener)
    }

    private fun fireEvent(event: HttpEvent) {
        listeners.forEach { l ->
            try {
                l(event)
            } catch (e: Exception) {
                log.warn("listener failed", e)
            }
        }
    }

    override fun dispose() {
        running.set(false)
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    private class ClientChannel(val socket: Socket, private val writer: BufferedWriter) {
        @Synchronized
        fun send(json: String) {
            try {
                writer.write(json)
                writer.write("\n")
                writer.flush()
            } catch (_: Exception) {
                // 写失败说明连接已断，主线程会清理
            }
        }
    }
}
