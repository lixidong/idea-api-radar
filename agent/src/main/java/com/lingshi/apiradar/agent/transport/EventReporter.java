package com.lingshi.apiradar.agent.transport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 将拦截到的 HttpEvent 通过 TCP socket 异步上报给 IDEA plugin。
 *
 * <p>同一条 socket 双向使用：
 * <ul>
 *     <li>写端（agent → plugin）：发送 HttpEvent JSON</li>
 *     <li>读端（plugin → agent）：接收 control 消息（暂停、阈值、URL 过滤等）</li>
 * </ul>
 *
 * <p>单线程后台发送，避免阻塞业务线程；队列满时直接丢弃，保护内存。
 */
public final class EventReporter {

    private static final int QUEUE_CAPACITY = 1024;
    private static final BlockingQueue<HttpEvent> QUEUE = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private static volatile boolean running = false;
    private static int port;

    private EventReporter() {}

    public static void init(int port) {
        if (running) return;
        EventReporter.port = port;
        running = true;
        Thread t = new Thread(EventReporter::loop, "api-radar-reporter");
        t.setDaemon(true);
        t.start();
    }

    public static void report(HttpEvent event) {
        if (!running || event == null) return;
        // 队列满直接丢弃，不阻塞调用方
        QUEUE.offer(event);
    }

    private static void loop() {
        while (running) {
            Socket socket = null;
            try {
                socket = new Socket("127.0.0.1", port);
                System.out.println("[API Radar] connected to plugin at port " + port);

                final Socket sock = socket;
                Thread reader = new Thread(() -> readControl(sock), "api-radar-control-reader");
                reader.setDaemon(true);
                reader.start();

                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    while (running && !socket.isClosed()) {
                        HttpEvent event = QUEUE.poll(1, TimeUnit.SECONDS);
                        if (event == null) continue;
                        writer.write(JsonWriter.toJson(event));
                        writer.write('\n');
                        writer.flush();
                    }
                }
            } catch (IOException e) {
                // 连不上 plugin，5 秒后重试
                sleepQuietly(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                closeQuietly(socket);
            }
        }
    }

    private static void readControl(Socket socket) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                ControlMessage.apply(line);
            }
        } catch (IOException ignored) {
            // 连接断开，主循环负责重连
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
