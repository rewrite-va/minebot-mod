package minebot.mod;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The local control channel: a WebSocket server embedded in the client,
 * bound to localhost only, that the Python backend connects to. This mod
 * is the only thing that talks to the actual Minecraft server -- Python
 * sends commands here (see MessageHandler) and receives game events
 * (chat, position, entities, health) broadcast back over the same
 * connection, rather than Python speaking the Minecraft protocol itself.
 *
 * Bound to 127.0.0.1 only: this channel has no auth of its own (nothing
 * validates who's on the other end), which is fine only as long as it's
 * unreachable from outside this machine.
 */
public final class ControlServer extends WebSocketServer {
    public static final int DEFAULT_PORT = 47893;

    private final Set<WebSocket> clients = ConcurrentHashMap.newKeySet();
    private final Consumer<String> onMessage;

    public ControlServer(final int port, final Consumer<String> onMessage) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.onMessage = onMessage;
    }

    @Override
    public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
        clients.add(conn);
        MinebotMod.LOGGER.info("control channel: client connected ({})", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(final WebSocket conn, final int code, final String reason, final boolean remote) {
        clients.remove(conn);
        MinebotMod.LOGGER.info("control channel: client disconnected ({})", reason);
    }

    @Override
    public void onMessage(final WebSocket conn, final String message) {
        onMessage.accept(message);
    }

    @Override
    public void onError(final WebSocket conn, final Exception ex) {
        MinebotMod.LOGGER.error("control channel error", ex);
    }

    @Override
    public void onStart() {
        MinebotMod.LOGGER.info("control channel: listening on 127.0.0.1:{}", getPort());
    }

    /** Sends one JSON event line to every currently-connected backend. */
    public void broadcastEvent(final String json) {
        for (WebSocket client : clients) {
            client.send(json);
        }
    }
}
