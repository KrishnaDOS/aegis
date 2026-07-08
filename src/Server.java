import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {

    static final String SERVER_PREFIX = "SERVER:";
    static final String ROLE_PREFIX = "ROLE:";
    static final String CONTROL_PREFIX = "CTRL:";
    static final String REGISTER_CMD = "REGISTER";
    static final String LIST_CMD = "LIST";
    static final String CALL_CMD = "CALL";
    static final String CANCEL_CMD = "CANCEL";
    static final String ACCEPT_CMD = "ACCEPT";
    static final String REJECT_CMD = "REJECT";
    static final String END_SESSION_CMD = "END_SESSION";
    static final String HELP_CMD = "HELP";

    static final String REGISTERED_MESSAGE = "REGISTERED:";
    static final String CLIENT_LIST_MESSAGE = "CLIENT_LIST:";
    static final String INFO_MESSAGE = "INFO:";
    static final String ERROR_MESSAGE = "ERROR:";
    static final String CALL_INCOMING_MESSAGE = "CALL_INCOMING:";
    static final String CALL_REQUEST_SENT_MESSAGE = "CALL_REQUEST_SENT:";
    static final String CALL_REJECTED_MESSAGE = "CALL_REJECTED:";
    static final String CALL_ACCEPTED_MESSAGE = "CALL_ACCEPTED:";
    static final String SESSION_READY_MESSAGE = "SESSION_READY";
    static final String INITIATOR_ROLE = "INITIATOR";
    static final String RESPONDER_ROLE = "RESPONDER";

    private static final String DEFAULT_BIND_IP = "0.0.0.0";
    private static final int DEFAULT_PORT = 5000;
    private static final long CALL_REQUEST_TIMEOUT_MS = 30_000L;
    private static final long CALL_TIMEOUT_SWEEP_INTERVAL_MS = 1_000L;
    private static final long LOBBY_COMMAND_IDLE_TIMEOUT_MS = 180_000L;
    private static final long STALE_LOBBY_SWEEP_INTERVAL_MS = 5_000L;

    private final String bindIp;
    private final int port;
    private final Map<String, ClientHandler> clientsByName;
    private final Map<String, PendingCall> pendingCallsByTarget;
    private final AtomicInteger sessionCounter;

    public Server(String bindIp, int port) {
        this.bindIp = bindIp;
        this.port = port;
        this.clientsByName = new HashMap<>();
        this.pendingCallsByTarget = new HashMap<>();
        this.sessionCounter = new AtomicInteger(1);
    }

    public static void main(String[] args) {
        String bindIp = args.length >= 1 ? args[0] : DEFAULT_BIND_IP;
        int port = DEFAULT_PORT;

        try {
            if (args.length >= 2) {
                port = Integer.parseInt(args[1]);
            }

            Server server = new Server(bindIp, port);
            server.start();
        } catch (NumberFormatException ex) {
            System.out.println("Invalid port number. Please use a numeric port.");
            printUsage();
        } catch (IOException ex) {
            System.out.println("Server error: " + ex.getMessage());
        }
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(bindIp, port));
            System.out.println("Server listening on " + bindIp + ":" + port);
            startBackgroundMonitors();

            while (true) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);

                ClientEndpoint endpoint = new ClientEndpoint(socket);
                ClientHandler handler = new ClientHandler(endpoint);
                Thread thread = new Thread(handler, "client-handler-" + endpoint.getRemoteAddress());
                thread.start();
            }
        }
    }

    private synchronized boolean registerClient(ClientHandler handler, String requestedName) {
        String normalized = requestedName == null ? "" : requestedName.trim();
        if (normalized.isEmpty()) {
            handler.sendError("Username cannot be empty.");
            return false;
        }

        if (clientsByName.containsKey(normalized)) {
            handler.sendError("Username already in use: " + normalized);
            return false;
        }

        handler.username = normalized;
        handler.state = ClientState.AVAILABLE;
        handler.lastCommandEpochMs = System.currentTimeMillis();
        clientsByName.put(normalized, handler);
        handler.sendServerPayload(REGISTERED_MESSAGE + normalized);
        handler.sendInfo("Connected to server as " + normalized + ".");
        handler.sendInfo(
                "Use commands: /list, /call <user>, /cancel [user], /accept [user], /reject [user], /help, /quit.");

        broadcastClientListLocked();
        return true;
    }

    private synchronized void handleListRequest(ClientHandler handler) {
        if (!handler.isRegistered()) {
            handler.sendError("Please register first.");
            return;
        }
        handler.sendServerPayload(CLIENT_LIST_MESSAGE + buildClientListForViewerLocked(handler));
    }

    private synchronized void handleCallRequest(ClientHandler requester, String targetName) {
        if (!requester.isRegistered()) {
            requester.sendError("Please register first.");
            return;
        }

        String target = targetName == null ? "" : targetName.trim();
        if (target.isEmpty()) {
            requester.sendError("Usage: /call <username>");
            return;
        }

        if (requester.username.equals(target)) {
            requester.sendError("You cannot call yourself.");
            return;
        }

        ClientHandler recipient = clientsByName.get(target);
        if (recipient == null) {
            requester.sendError("User not found: " + target);
            return;
        }

        if (requester.state != ClientState.AVAILABLE) {
            requester.sendError("You are not available to place a call.");
            return;
        }

        if (recipient.state != ClientState.AVAILABLE) {
            requester.sendError("User is not available: " + target);
            return;
        }

        pendingCallsByTarget.put(recipient.username,
                new PendingCall(requester.username, recipient.username, System.currentTimeMillis()));
        requester.state = ClientState.CALLING;
        requester.pendingTarget = recipient.username;
        recipient.state = ClientState.RINGING;
        recipient.pendingRequester = requester.username;

        requester.sendServerPayload(CALL_REQUEST_SENT_MESSAGE + recipient.username);
        recipient.sendServerPayload(CALL_INCOMING_MESSAGE + requester.username);

        broadcastClientListLocked();
    }

    private synchronized void handleCallCancel(ClientHandler requester, String targetArg) {
        if (!requester.isRegistered()) {
            requester.sendError("Please register first.");
            return;
        }

        if (requester.state != ClientState.CALLING || requester.pendingTarget == null) {
            requester.sendError("No active outgoing call to cancel.");
            return;
        }

        String expectedTarget = requester.pendingTarget;
        String providedTarget = targetArg == null ? "" : targetArg.trim();
        if (!providedTarget.isEmpty() && !expectedTarget.equals(providedTarget)) {
            requester
                    .sendError("You are currently calling " + expectedTarget + ". Use /cancel " + expectedTarget + ".");
            return;
        }

        PendingCall pendingCall = pendingCallsByTarget.get(expectedTarget);
        if (pendingCall == null || !requester.username.equals(pendingCall.requesterUsername)) {
            requester.state = ClientState.AVAILABLE;
            requester.pendingTarget = null;
            requester.sendInfo("Outgoing call is no longer active.");
            broadcastClientListLocked();
            return;
        }

        pendingCallsByTarget.remove(expectedTarget);
        requester.state = ClientState.AVAILABLE;
        requester.pendingTarget = null;
        requester.sendInfo("Cancelled call to " + expectedTarget + ".");

        ClientHandler recipient = clientsByName.get(expectedTarget);
        if (recipient != null
                && recipient.state == ClientState.RINGING
                && requester.username.equals(recipient.pendingRequester)) {

            recipient.state = ClientState.AVAILABLE;
            recipient.pendingRequester = null;
            recipient.sendInfo("Call from " + requester.username + " was cancelled.");
        }

        broadcastClientListLocked();
    }

    private synchronized void handleCallAccept(ClientHandler responder, String requesterArg) {
        if (!responder.isRegistered()) {
            responder.sendError("Please register first.");
            return;
        }

        String requesterName = resolvePeerName(requesterArg, responder.pendingRequester);
        if (requesterName == null || requesterName.isBlank()) {
            responder.sendError("No pending caller. Usage: /accept <username>");
            return;
        }

        PendingCall pendingCall = pendingCallsByTarget.get(responder.username);
        if (pendingCall == null || !pendingCall.requesterUsername.equals(requesterName)) {
            responder.sendError("No matching pending call from " + requesterName);
            return;
        }

        ClientHandler requester = clientsByName.get(requesterName);
        if (requester == null) {
            clearPendingState(responder, null);
            responder.sendError("Requester disconnected.");
            broadcastClientListLocked();
            return;
        }

        if (requester.state != ClientState.CALLING || !responder.username.equals(requester.pendingTarget)) {
            clearPendingState(responder, requester);
            responder.sendError("Call is no longer active.");
            requester.sendError("Call to " + responder.username + " was no longer active.");
            broadcastClientListLocked();
            return;
        }

        pendingCallsByTarget.remove(responder.username);
        requester.pendingTarget = null;
        responder.pendingRequester = null;

        requester.state = ClientState.IN_SESSION;
        responder.state = ClientState.IN_SESSION;

        requester.activePeer = responder;
        responder.activePeer = requester;

        int sessionId = sessionCounter.getAndIncrement();

        requester.sendServerPayload(CALL_ACCEPTED_MESSAGE + responder.username);
        responder.sendServerPayload(CALL_ACCEPTED_MESSAGE + requester.username);

        requester.sendServerPayload(SESSION_READY_MESSAGE + ":" + sessionId + ":" + responder.username);
        responder.sendServerPayload(SESSION_READY_MESSAGE + ":" + sessionId + ":" + requester.username);

        requester.sendRole(INITIATOR_ROLE, sessionId, responder.username);
        responder.sendRole(RESPONDER_ROLE, sessionId, requester.username);

        broadcastClientListLocked();
    }

    private synchronized void handleCallReject(ClientHandler responder, String requesterArg) {
        if (!responder.isRegistered()) {
            responder.sendError("Please register first.");
            return;
        }

        String requesterName = resolvePeerName(requesterArg, responder.pendingRequester);
        if (requesterName == null || requesterName.isBlank()) {
            responder.sendError("No pending caller. Usage: /reject <username>");
            return;
        }

        PendingCall pendingCall = pendingCallsByTarget.get(responder.username);
        if (pendingCall == null || !pendingCall.requesterUsername.equals(requesterName)) {
            responder.sendError("No matching pending call from " + requesterName);
            return;
        }

        pendingCallsByTarget.remove(responder.username);

        ClientHandler requester = clientsByName.get(requesterName);
        if (requester != null) {
            requester.state = ClientState.AVAILABLE;
            requester.pendingTarget = null;
            requester.sendServerPayload(CALL_REJECTED_MESSAGE + responder.username);
        }

        responder.state = ClientState.AVAILABLE;
        responder.pendingRequester = null;
        responder.sendInfo("Rejected call from " + requesterName);

        broadcastClientListLocked();
    }

    private synchronized void handleEndSession(ClientHandler handler) {
        if (handler.state != ClientState.IN_SESSION) {
            return;
        }

        ClientHandler peer = handler.activePeer;
        handler.activePeer = null;
        handler.state = ClientState.AVAILABLE;
        handler.pendingTarget = null;
        handler.pendingRequester = null;

        if (peer != null && !peer.cleanedUp) {
            if (peer.activePeer == handler) {
                peer.activePeer = null;
            }

            if (peer.state == ClientState.IN_SESSION) {
                peer.state = ClientState.AVAILABLE;
                peer.pendingTarget = null;
                peer.pendingRequester = null;
            }
        }

        // Mimics automatic /list refresh for users returning to lobby after /quit.
        broadcastClientListLocked();
    }

    private synchronized void expireTimedOutCallsLocked() {
        long now = System.currentTimeMillis();
        boolean changed = false;

        for (PendingCall pendingCall : new ArrayList<>(pendingCallsByTarget.values())) {
            if (now - pendingCall.createdAtEpochMs < CALL_REQUEST_TIMEOUT_MS) {
                continue;
            }

            PendingCall current = pendingCallsByTarget.get(pendingCall.targetUsername);
            if (current == null || !pendingCall.requesterUsername.equals(current.requesterUsername)) {
                continue;
            }

            pendingCallsByTarget.remove(pendingCall.targetUsername);

            ClientHandler requester = clientsByName.get(pendingCall.requesterUsername);
            if (requester != null
                    && requester.state == ClientState.CALLING
                    && pendingCall.targetUsername.equals(requester.pendingTarget)) {

                requester.state = ClientState.AVAILABLE;
                requester.pendingTarget = null;
                requester.sendInfo("Call to " + pendingCall.targetUsername + " timed out.");
            }

            ClientHandler recipient = clientsByName.get(pendingCall.targetUsername);
            if (recipient != null
                    && recipient.state == ClientState.RINGING
                    && pendingCall.requesterUsername.equals(recipient.pendingRequester)) {

                recipient.state = ClientState.AVAILABLE;
                recipient.pendingRequester = null;
                recipient.sendInfo("Missed call from " + pendingCall.requesterUsername + " (timed out).");
            }

            changed = true;
        }

        if (changed) {
            broadcastClientListLocked();
        }
    }

    private synchronized List<ClientHandler> collectStaleLobbyClientsLocked() {
        long now = System.currentTimeMillis();
        List<ClientHandler> staleClients = new ArrayList<>();

        for (ClientHandler handler : clientsByName.values()) {
            if (handler.cleanedUp || handler.state == ClientState.IN_SESSION) {
                continue;
            }

            if (handler.state == ClientState.CONNECTING) {
                continue;
            }

            if (now - handler.lastCommandEpochMs >= LOBBY_COMMAND_IDLE_TIMEOUT_MS) {
                staleClients.add(handler);
            }
        }

        return staleClients;
    }

    private void startBackgroundMonitors() {
        Thread timeoutMonitor = new Thread(this::monitorCallTimeoutsLoop, "call-timeout-monitor");
        timeoutMonitor.setDaemon(true);
        timeoutMonitor.start();

        Thread staleMonitor = new Thread(this::monitorStaleLobbyClientsLoop, "stale-lobby-monitor");
        staleMonitor.setDaemon(true);
        staleMonitor.start();
    }

    private void monitorCallTimeoutsLoop() {
        while (true) {
            try {
                Thread.sleep(CALL_TIMEOUT_SWEEP_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            synchronized (this) {
                expireTimedOutCallsLocked();
            }
        }
    }

    private void monitorStaleLobbyClientsLoop() {
        while (true) {
            try {
                Thread.sleep(STALE_LOBBY_SWEEP_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            List<ClientHandler> staleClients;
            synchronized (this) {
                staleClients = collectStaleLobbyClientsLocked();
            }

            for (ClientHandler handler : staleClients) {
                handler.forceClose();
            }
        }
    }

    private synchronized void handleClientDisconnect(ClientHandler handler) {
        if (handler.cleanedUp) {
            return;
        }
        handler.cleanedUp = true;

        if (handler.username != null) {
            clientsByName.remove(handler.username);
            pendingCallsByTarget.remove(handler.username);
        }

        if (handler.state == ClientState.CALLING && handler.pendingTarget != null) {
            ClientHandler target = clientsByName.get(handler.pendingTarget);
            if (target != null && handler.username != null) {
                PendingCall pendingCall = pendingCallsByTarget.get(target.username);
                if (pendingCall != null && handler.username.equals(pendingCall.requesterUsername)) {
                    pendingCallsByTarget.remove(target.username);
                    target.pendingRequester = null;
                    target.state = ClientState.AVAILABLE;
                    target.sendInfo("Call request from " + handler.username + " was cancelled.");
                }
            }
        }

        if (handler.state == ClientState.RINGING && handler.pendingRequester != null) {
            ClientHandler requester = clientsByName.get(handler.pendingRequester);
            if (requester != null) {
                requester.pendingTarget = null;
                requester.state = ClientState.AVAILABLE;
                requester.sendInfo("Call target " + handler.username + " disconnected.");
            }
            pendingCallsByTarget.remove(handler.username);
        }

        if (handler.state == ClientState.IN_SESSION && handler.activePeer != null) {
            ClientHandler peer = handler.activePeer;
            handler.activePeer = null;
            if (!peer.cleanedUp) {
                peer.forceClose();
            }
        }

        broadcastClientListLocked();
    }

    private synchronized void broadcastClientListLocked() {
        for (ClientHandler handler : new ArrayList<>(clientsByName.values())) {
            if (handler.cleanedUp || handler.state == ClientState.IN_SESSION) {
                continue;
            }
            handler.sendServerPayload(CLIENT_LIST_MESSAGE + buildClientListForViewerLocked(handler));
        }
    }

    private String buildClientListForViewerLocked(ClientHandler viewer) {
        List<String> entries = new ArrayList<>();
        for (ClientHandler handler : clientsByName.values()) {
            if (viewer != null && handler == viewer) {
                continue;
            }
            entries.add(handler.username + " [" + handler.describeState() + "]");
        }
        if (entries.isEmpty()) {
            return "No other clients online";
        }
        return String.join(", ", entries);
    }

    private static String resolvePeerName(String providedName, String fallbackName) {
        if (providedName != null && !providedName.isBlank()) {
            return providedName.trim();
        }
        return fallbackName;
    }

    private void clearPendingState(ClientHandler responder, ClientHandler requester) {
        responder.state = ClientState.AVAILABLE;
        responder.pendingRequester = null;
        if (requester != null) {
            requester.state = ClientState.AVAILABLE;
            requester.pendingTarget = null;
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java Server [bindIp] [port]");
        System.out.println("Example:");
        System.out.println("  java Server 0.0.0.0 5000");
    }

    private enum ClientState {
        CONNECTING,
        AVAILABLE,
        CALLING,
        RINGING,
        IN_SESSION
    }

    private final class ClientHandler implements Runnable {
        private final ClientEndpoint endpoint;
        private volatile String username;
        private volatile ClientState state;
        private volatile String pendingTarget;
        private volatile String pendingRequester;
        private volatile ClientHandler activePeer;
        private volatile boolean cleanedUp;
        private volatile long lastCommandEpochMs;

        ClientHandler(ClientEndpoint endpoint) {
            this.endpoint = endpoint;
            this.state = ClientState.CONNECTING;
            this.pendingTarget = null;
            this.pendingRequester = null;
            this.activePeer = null;
            this.cleanedUp = false;
            this.lastCommandEpochMs = System.currentTimeMillis();
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = endpoint.readLine()) != null) {
                    if (line.startsWith(CONTROL_PREFIX)) {
                        handleControl(line.substring(CONTROL_PREFIX.length()));
                        continue;
                    }

                    if (state == ClientState.IN_SESSION) {
                        forwardSessionFrame(line);
                        continue;
                    }

                    sendError("Unexpected input. Use lobby commands.");
                }
            } catch (IOException ex) {
                // Socket close and disconnect cleanup are handled in finally.
            } finally {
                endpoint.closeQuietly();
                handleClientDisconnect(this);
            }
        }

        private void handleControl(String payload) {
            String[] parts = payload.split(":", 2);
            String command = parts[0].trim().toUpperCase();
            String argument = parts.length > 1 ? parts[1].trim() : "";

            switch (command) {
                case REGISTER_CMD:
                    markCommandActivity();
                    registerClient(this, argument);
                    break;
                case LIST_CMD:
                    markCommandActivity();
                    handleListRequest(this);
                    break;
                case CALL_CMD:
                    markCommandActivity();
                    handleCallRequest(this, argument);
                    break;
                case CANCEL_CMD:
                    markCommandActivity();
                    handleCallCancel(this, argument);
                    break;
                case ACCEPT_CMD:
                    markCommandActivity();
                    handleCallAccept(this, argument);
                    break;
                case REJECT_CMD:
                    markCommandActivity();
                    handleCallReject(this, argument);
                    break;
                case END_SESSION_CMD:
                    markCommandActivity();
                    handleEndSession(this);
                    break;
                case HELP_CMD:
                    markCommandActivity();
                    sendInfo(
                            "Commands: /list, /call <user>, /cancel [user], /accept [user], /reject [user], /help, /quit.");
                    break;
                default:
                    sendError("Unknown command: " + command);
                    break;
            }
        }

        private void forwardSessionFrame(String line) {
            ClientHandler peer = activePeer;
            if (peer == null) {
                return;
            }
            peer.endpoint.writeLine(line);
        }

        private void forceClose() {
            endpoint.closeQuietly();
        }

        private void markCommandActivity() {
            this.lastCommandEpochMs = System.currentTimeMillis();
        }

        private boolean isRegistered() {
            return username != null && !username.isBlank();
        }

        private String describeState() {
            switch (state) {
                case AVAILABLE:
                    return "AVAILABLE";
                case CALLING:
                    return "CALLING " + pendingTarget;
                case RINGING:
                    return "RINGING from " + pendingRequester;
                case IN_SESSION:
                    if (activePeer == null) {
                        return "IN_SESSION";
                    }
                    return "IN_SESSION with " + activePeer.username;
                default:
                    return "CONNECTING";
            }
        }

        private void sendInfo(String message) {
            sendServerPayload(INFO_MESSAGE + message);
        }

        private void sendError(String message) {
            sendServerPayload(ERROR_MESSAGE + message);
        }

        private void sendRole(String role, int sessionId, String peerUsername) {
            endpoint.sendControl(ROLE_PREFIX, role + ":" + sessionId + ":" + peerUsername);
        }

        private void sendServerPayload(String payload) {
            endpoint.sendControl(SERVER_PREFIX, payload);
        }
    }

    private static final class PendingCall {
        private final String requesterUsername;
        private final String targetUsername;
        private final long createdAtEpochMs;

        private PendingCall(String requesterUsername, String targetUsername, long createdAtEpochMs) {
            this.requesterUsername = requesterUsername;
            this.targetUsername = targetUsername;
            this.createdAtEpochMs = createdAtEpochMs;
        }
    }

    static final class ClientEndpoint {
        private final Socket socket;
        private final BufferedReader input;
        private final PrintWriter output;

        ClientEndpoint(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        }

        String readLine() throws IOException {
            return input.readLine();
        }

        synchronized void writeLine(String line) {
            output.println(line);
        }

        synchronized void sendControl(String prefix, String value) {
            output.println(prefix + value);
        }

        boolean isClosed() {
            return socket.isClosed();
        }

        String getRemoteAddress() {
            return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        }

        void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ex) {
                // Ignore close exception during cleanup.
            }
        }
    }
}
