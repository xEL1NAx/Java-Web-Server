package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class EventLoop implements Runnable {
    private final HttpServer server;
    private final Config config;
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true;
    private final Thread thread;

    public EventLoop(HttpServer server, Config config) throws IOException {
        this.server = server;
        this.config = config;
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        this.thread = new Thread(this, "event-loop");
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(config.bindAddress, config.port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    public void start() {
        thread.start();
    }

    public void stop() {
        running = false;
        selector.wakeup();
    }

    public void enableWriteInterest(Connection connection) {
        tasks.add(() -> {
            SelectionKey key = connection.key();
            if (key.isValid()) key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        });
        selector.wakeup();
    }

    public void disableWriteInterest(Connection connection) {
        tasks.add(() -> {
            SelectionKey key = connection.key();
            if (key.isValid()) key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        });
        selector.wakeup();
    }

    public void executeOnLoop(Runnable task) {
        tasks.add(task);
        selector.wakeup();
    }

    @Override
    public void run() {
        Logger.info("HTTP listener started on " + config.bindAddress + ":" + config.port);
        while (running) {
            try {
                drainTasks();
                selector.select(config.selectorTimeoutMillis);
                drainTasks();
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;
                    try {
                        if (key.isAcceptable()) onAccept();
                        if (!key.isValid()) continue;
                        if (key.isReadable()) ((Connection) key.attachment()).onReadable();
                        if (!key.isValid()) continue;
                        if (key.isWritable()) ((Connection) key.attachment()).onWritable();
                    } catch (Exception e) {
                        Logger.error("Connection failure", e);
                        try { key.channel().close(); } catch (IOException ignored) {}
                        key.cancel();
                    }
                }
                sweepIdleConnections();
            } catch (Exception e) {
                Logger.error("Event loop error", e);
            }
        }
        try {
            selector.close();
            serverChannel.close();
        } catch (IOException ignored) {
        }
    }

    private void onAccept() throws IOException {
        SocketChannel client;
        while ((client = serverChannel.accept()) != null) {
            client.configureBlocking(false);
            SelectionKey key = client.register(selector, SelectionKey.OP_READ);
            String remoteIp = ((InetSocketAddress) client.getRemoteAddress()).getAddress().getHostAddress();
            Connection connection = new Connection(client, this, server, key, config.readBufferSize, remoteIp);
            key.attach(connection);
        }
    }

    private void sweepIdleConnections() {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            if (!(key.attachment() instanceof Connection c)) continue;
            if (now - c.lastActivityMillis() > config.connectionIdleTimeoutMillis) {
                c.close();
            }
        }
    }

    private void drainTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                Logger.error("Event loop task failed", e);
            }
        }
    }
}
