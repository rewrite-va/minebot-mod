package minebot.mod;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The local control channel: a WebSocket *client* connecting out to the
 * Python backend, which now owns the server side (see
 * bridge/client.py's ModBridge). This mod is still the only thing that
 * talks to the actual Minecraft server -- it just no longer listens for
 * the Python side's connection, it initiates one.
 *
 * Why the mod is the client and not the server: the Python backend
 * commonly runs inside WSL2, and WSL2's default (NAT) networking mode
 * only forwards localhost connections *from Windows into WSL2*, not the
 * reverse -- confirmed live (a mod-side server bound to 0.0.0.0 was still
 * unreachable from WSL2 due to Windows Firewall silently dropping the
 * inbound connection; a plain HTTP server run inside WSL2 was reachable
 * from Windows via plain localhost with zero configuration). Making the
 * mod the client means it makes an *outbound* connection to
 * localhost:PORT, which Windows forwards into WSL2 transparently and
 * isn't blocked by the inbound-connection-focused default firewall rules
 * either way.
 *
 * Reconnects automatically (unlike the old server side, which just waited
 * for an incoming connection) since the mod's lifetime -- a long-running
 * game client -- now depends on the Python backend already being up, or
 * coming up after the game does; a dropped Python process shouldn't
 * require restarting the whole game.
 */
public final class ControlClient {
    public static final int DEFAULT_PORT = 47893;
    private static final long RECONNECT_DELAY_MS = 2000;

    private final URI uri;
    private final Consumer<String> onMessage;
    private final Runnable onConnect;
    private volatile Client client;
    private volatile boolean shuttingDown;
    // Java-WebSocket's WebSocketClient instances are single-use (calling
    // connect() twice on the same object throws IllegalStateException),
    // and a single failed connection attempt can fire *both* onError and
    // onClose on the same instance -- found live: without this guard, two
    // reconnect attempts got scheduled for one failure, and the second one
    // to run tried to reconnect an already-used Client. This flag makes
    // "a reconnect is already scheduled or in flight" idempotent regardless
    // of how many callback paths ask for one.
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);

    public ControlClient(final String host, final int port, final Consumer<String> onMessage, final Runnable onConnect) {
        this.uri = URI.create("ws://" + host + ":" + port);
        this.onMessage = onMessage;
        this.onConnect = onConnect;
    }

    public void start() {
        connect();
    }

    public void stop() {
        shuttingDown = true;
        if (client != null) {
            client.close();
        }
    }

    public boolean isOpen() {
        return client != null && client.isOpen();
    }

    /** Sends one JSON event line to the backend, if currently connected (silently dropped otherwise). */
    public void sendEvent(final String json) {
        Client current = client;
        if (current != null && current.isOpen()) {
            // Wire-level logging, mod -> Python direction. At `info` (not
            // `debug`) since this client's default log4j config filters
            // debug output entirely -- no LOGGER.debug line from this mod
            // has ever actually appeared in a real log (confirmed
            // elsewhere in this codebase, e.g. BlockBreaker's tool-switch
            // logging). Added to debug a live report of stone not
            // dropping cobblestone when mined via !collect -- having the
            // exact commands/events on both sides of the socket, in order,
            // is the fastest way to tell "the mod never asked for/reported
            // X" apart from "X happened but something downstream ignored
            // it" without guessing from decompiled source alone.
            MinebotMod.LOGGER.info("wire >> {}", json);
            current.send(json);
        }
    }

    private void connect() {
        if (shuttingDown) {
            return;
        }
        client = new Client(uri);
        client.connect();
    }

    private void scheduleReconnect() {
        if (shuttingDown) {
            return;
        }
        if (!reconnectPending.compareAndSet(false, true)) {
            return; // a reconnect is already scheduled/in flight for the current failure
        }
        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                reconnectPending.set(false);
            }
            connect();
        }, "minebot-control-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private final class Client extends WebSocketClient {
        Client(final URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(final ServerHandshake handshake) {
            MinebotMod.LOGGER.info("control channel: connected to {}", uri);
            onConnect.run();
        }

        @Override
        public void onMessage(final String message) {
            // Wire-level logging, Python -> mod direction -- see sendEvent's
            // own comment for why this is at `info`. Logged before dispatch
            // (not inside MinebotMod.handleMessage) so a message that fails
            // to even parse as JSON still gets recorded here first.
            MinebotMod.LOGGER.info("wire << {}", message);
            onMessage.accept(message);
        }

        @Override
        public void onClose(final int code, final String reason, final boolean remote) {
            MinebotMod.LOGGER.info("control channel: disconnected ({}), retrying in {}ms", reason, RECONNECT_DELAY_MS);
            scheduleReconnect();
        }

        @Override
        public void onError(final Exception ex) {
            MinebotMod.LOGGER.warn("control channel: connection error: {}", ex.toString());
        }
    }
}
