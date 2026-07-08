import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

public class Client {
    private static final String QUIT_COMMAND = "/quit";
    private static final String DEFAULT_SERVER_IP = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;
    private static final String DEFAULT_USERNAME = "User";

    private static final String KEM_PUBLIC_KEY_PREFIX = "KEM_PUB:";
    private static final String SIGNING_PUBLIC_KEY_PREFIX = "SIG_PUB:";
    private static final String KEM_CIPHERTEXT_PREFIX = "KEM_CT:";
    private static final String HANDSHAKE_SIGNATURE_PREFIX = "HS_SIG:";
    private static final String ACK_PREFIX = "ACK:";
    private static final String MESSAGE_PREFIX = "MSG:";
    private static final String MESSAGE_SIGNATURE_DELIMITER = "|SIG|";
    private static final String NAME_PREFIX = "NAME:";
    private static final long CONSOLE_POLL_MS = 100L;

    private final String serverIp;
    private final int port;
    private String username;

    public Client(String serverIp, int port, String username) {
        this.serverIp = serverIp;
        this.port = port;
        this.username = username;
    }

    public static void main(String[] args) {
        String serverIp = args.length >= 1 ? args[0] : DEFAULT_SERVER_IP;
        int port = DEFAULT_PORT;
        String username = args.length >= 3 ? args[2] : DEFAULT_USERNAME;

        try {
            if (args.length >= 2) {
                port = Integer.parseInt(args[1]);
            }

            Client client = new Client(serverIp, port, username);
            client.start();
        } catch (NumberFormatException ex) {
            System.out.println("Invalid port number. Please use a numeric port.");
            printUsage();
        } catch (GeneralSecurityException ex) {
            System.out.println("Cryptography error: " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("Client error: " + ex.getMessage());
        }
    }

    public void start() throws IOException, GeneralSecurityException {
        Encryption encryption = new Encryption();

        try (Socket socket = new Socket(serverIp, port)) {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            BufferedReader lobbyConsole = new BufferedReader(new InputStreamReader(System.in));

            registerWithServer(input, output, lobbyConsole);

            AtomicBoolean consoleRunning = new AtomicBoolean(true);
            BlockingQueue<String> consoleQueue = new LinkedBlockingQueue<>();
            Thread consoleReader = new Thread(
                    () -> runConsoleReader(lobbyConsole, consoleQueue, consoleRunning),
                    "console-reader-" + username);
            consoleReader.setDaemon(true);
            consoleReader.start();

            boolean clientRunning = true;
            boolean autoListOnLobbyEntry = false;
            while (clientRunning && !socket.isClosed()) {
                AtomicBoolean lobbyRunning = new AtomicBoolean(true);
                AtomicReference<String> pendingCaller = new AtomicReference<>(null);
                AtomicReference<SessionAssignment> sessionAssignmentRef = new AtomicReference<>(null);

                Thread lobbyReceiver = new Thread(
                        () -> runLobbyReceiver(input, lobbyRunning, pendingCaller, sessionAssignmentRef),
                        "lobby-receiver-" + username);
                lobbyReceiver.setDaemon(true);
                lobbyReceiver.start();

                consoleQueue.clear();
                if (autoListOnLobbyEntry) {
                    sendControlCommand(output, Server.LIST_CMD, null);
                    autoListOnLobbyEntry = false;
                }

                while (lobbyRunning.get() && sessionAssignmentRef.get() == null) {
                    String command = pollConsoleLine(consoleQueue, CONSOLE_POLL_MS);
                    if (command == null) {
                        if (!consoleRunning.get()) {
                            lobbyRunning.set(false);
                            clientRunning = false;
                            break;
                        }
                        continue;
                    }

                    boolean shouldQuit = handleLobbyCommand(command, output, pendingCaller);
                    if (shouldQuit) {
                        lobbyRunning.set(false);
                        clientRunning = false;
                        break;
                    }
                }

                lobbyRunning.set(false);
                try {
                    lobbyReceiver.join(1000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    clientRunning = false;
                }

                if (!clientRunning || socket.isClosed()) {
                    break;
                }

                SessionAssignment assignment = sessionAssignmentRef.get();
                if (assignment == null) {
                    continue;
                }

                HandshakeContext handshakeContext = performPeerHandshake(input, output, encryption, assignment);
                String remoteName = exchangeNames(input, output, encryption, handshakeContext, assignment);

                consoleQueue.clear();
                clientRunning = startEncryptedChatLoop(
                        socket,
                        input,
                        output,
                        consoleQueue,
                        consoleRunning,
                        encryption,
                        handshakeContext,
                        remoteName);
                if (clientRunning && !socket.isClosed()) {
                    autoListOnLobbyEntry = true;
                }
            }

            consoleRunning.set(false);
        }
    }

    private void registerWithServer(BufferedReader input, PrintWriter output, BufferedReader lobbyConsole)
            throws IOException {
        sendControlCommand(output, Server.REGISTER_CMD, username);

        while (true) {
            String line = input.readLine();
            if (line == null) {
                throw new IOException("Server closed connection during registration.");
            }

            if (line.startsWith(Server.SERVER_PREFIX)) {
                String payload = line.substring(Server.SERVER_PREFIX.length());
                if (payload.startsWith(Server.REGISTERED_MESSAGE)) {
                    String registeredName = payload.substring(Server.REGISTERED_MESSAGE.length());
                    this.username = registeredName;
                    return;
                }

                if (payload.startsWith(Server.ERROR_MESSAGE)) {
                    String error = payload.substring(Server.ERROR_MESSAGE.length());
                    System.out.println("[Server] Error: " + error);
                    if (error.startsWith("Username already in use:")) {
                        String nextUsername = promptForNewUsername(lobbyConsole);
                        this.username = nextUsername;
                        sendControlCommand(output, Server.REGISTER_CMD, nextUsername);
                    }
                    continue;
                }

                processLobbyServerPayload(payload, new AtomicReference<>(null));
                continue;
            }

            if (line.startsWith(Server.ROLE_PREFIX)) {
                throw new IOException("Unexpected role assignment before registration completion.");
            }
        }
    }

    private String promptForNewUsername(BufferedReader console) throws IOException {
        while (true) {
            System.out.print("Enter a different username: ");
            String next = console.readLine();
            if (next == null) {
                throw new IOException("Console input closed while selecting a username.");
            }

            String trimmed = next.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }

            System.out.println("Username cannot be empty.");
        }
    }

    private void runConsoleReader(
            BufferedReader console,
            BlockingQueue<String> queue,
            AtomicBoolean consoleRunning) {

        try {
            while (consoleRunning.get()) {
                String line = console.readLine();
                if (line == null) {
                    consoleRunning.set(false);
                    return;
                }
                queue.offer(line);
            }
        } catch (IOException ex) {
        } finally {
            consoleRunning.set(false);
        }
    }

    private String pollConsoleLine(BlockingQueue<String> queue, long timeoutMs) {
        try {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void runLobbyReceiver(
            BufferedReader input,
            AtomicBoolean lobbyRunning,
            AtomicReference<String> pendingCaller,
            AtomicReference<SessionAssignment> assignmentRef) {

        try {
            while (lobbyRunning.get()) {
                String line = input.readLine();
                if (line == null) {
                    lobbyRunning.set(false);
                    return;
                }

                if (line.startsWith(Server.SERVER_PREFIX)) {
                    String payload = line.substring(Server.SERVER_PREFIX.length());
                    processLobbyServerPayload(payload, pendingCaller);
                    continue;
                }

                if (!line.startsWith(Server.ROLE_PREFIX)) {
                    continue;
                }

                SessionAssignment assignment = parseRoleLine(line);
                if (assignment == null) {
                    continue;
                }

                assignmentRef.set(assignment);
                System.out.println("Session ready with " + assignment.peerUsername + ". Starting handshake...");
                lobbyRunning.set(false);
                return;
            }
        } catch (IOException ex) {
            if (lobbyRunning.get()) {
                System.out.println("Connection closed: " + ex.getMessage());
            }
        } finally {
            lobbyRunning.set(false);
        }
    }

    private boolean handleLobbyCommand(
            String command,
            PrintWriter output,
            AtomicReference<String> pendingCaller) {

        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        if (!trimmed.startsWith("/")) {
            System.out.println("Commands must start with '/'. Use /help for command list.");
            return false;
        }

        String[] parts = trimmed.substring(1).split("\\s+", 2);
        String action = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (action) {
            case "list":
                sendControlCommand(output, Server.LIST_CMD, null);
                return false;
            case "call":
                if (arg.isBlank()) {
                    System.out.println("Usage: /call <username>");
                    return false;
                }
                sendControlCommand(output, Server.CALL_CMD, arg);
                return false;
            case "cancel":
                if (arg.isBlank()) {
                    sendControlCommand(output, Server.CANCEL_CMD, null);
                    return false;
                }
                sendControlCommand(output, Server.CANCEL_CMD, arg);
                return false;
            case "accept":
                if (arg.isBlank()) {
                    arg = pendingCaller.get();
                }
                if (arg == null || arg.isBlank()) {
                    System.out.println("No pending caller. Usage: /accept <username>");
                    return false;
                }
                pendingCaller.set(null);
                sendControlCommand(output, Server.ACCEPT_CMD, arg);
                return false;
            case "reject":
                if (arg.isBlank()) {
                    arg = pendingCaller.get();
                }
                if (arg == null || arg.isBlank()) {
                    System.out.println("No pending caller. Usage: /reject <username>");
                    return false;
                }
                pendingCaller.set(null);
                sendControlCommand(output, Server.REJECT_CMD, arg);
                return false;
            case "help":
                sendControlCommand(output, Server.HELP_CMD, null);
                return false;
            case "quit":
                System.out.println("Closing client.");
                return true;
            default:
                System.out.println(
                        "Unknown command. Use: /list, /call <user>, /cancel [user], /accept [user], /reject [user], /help, /quit");
                return false;
        }
    }

    private void processLobbyServerPayload(String payload, AtomicReference<String> pendingCaller) {
        if (payload.startsWith(Server.CLIENT_LIST_MESSAGE)) {
            String list = payload.substring(Server.CLIENT_LIST_MESSAGE.length());
            if ("No other clients online".equals(list)) {
                System.out.println("[Server] No other clients online");
            } else {
                System.out.println("[Server] Online clients: " + list);
            }
            return;
        }

        if (payload.startsWith(Server.CALL_INCOMING_MESSAGE)) {
            String caller = payload.substring(Server.CALL_INCOMING_MESSAGE.length());
            pendingCaller.set(caller);
            System.out
                    .println("Incoming request from " + caller + ". Type: /accept " + caller + " or /reject " + caller);
            return;
        }

        if (payload.startsWith(Server.CALL_REQUEST_SENT_MESSAGE)) {
            String target = payload.substring(Server.CALL_REQUEST_SENT_MESSAGE.length());
            System.out.println("Call request sent to " + target + ". Waiting for response... (use /cancel to abort)");
            return;
        }

        if (payload.startsWith(Server.CALL_REJECTED_MESSAGE)) {
            String target = payload.substring(Server.CALL_REJECTED_MESSAGE.length());
            System.out.println("Call rejected by " + target);
            return;
        }

        if (payload.startsWith(Server.CALL_ACCEPTED_MESSAGE)) {
            String peer = payload.substring(Server.CALL_ACCEPTED_MESSAGE.length());
            System.out.println("Call accepted by " + peer);
            return;
        }

        if (payload.startsWith(Server.SESSION_READY_MESSAGE + ":")) {
            String rest = payload.substring((Server.SESSION_READY_MESSAGE + ":").length());
            System.out.println("Session ready: " + rest);
            return;
        }

        if (payload.startsWith(Server.INFO_MESSAGE)) {
            String info = payload.substring(Server.INFO_MESSAGE.length());
            System.out.println("[Server] " + info);
            return;
        }

        if (payload.startsWith(Server.ERROR_MESSAGE)) {
            String error = payload.substring(Server.ERROR_MESSAGE.length());
            System.out.println("[Server] Error: " + error);
            return;
        }

        if (payload.startsWith(Server.REGISTERED_MESSAGE)) {
            return;
        }

        System.out.println("[Server] " + payload);
    }

    private SessionAssignment parseRoleLine(String line) {
        String payload = line.substring(Server.ROLE_PREFIX.length());
        String[] parts = payload.split(":", 3);
        if (parts.length == 0) {
            return null;
        }

        HandshakeRole role;
        if (Server.INITIATOR_ROLE.equals(parts[0])) {
            role = HandshakeRole.INITIATOR;
        } else if (Server.RESPONDER_ROLE.equals(parts[0])) {
            role = HandshakeRole.RESPONDER;
        } else {
            return null;
        }

        int sessionId = -1;
        if (parts.length >= 2 && !parts[1].isBlank()) {
            try {
                sessionId = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ex) {
                sessionId = -1;
            }
        }

        String peerUsername = parts.length >= 3 && !parts[2].isBlank() ? parts[2] : "Peer";
        return new SessionAssignment(role, sessionId, peerUsername);
    }

    private void sendControlCommand(PrintWriter output, String command, String argument) {
        if (argument == null || argument.isBlank()) {
            output.println(Server.CONTROL_PREFIX + command);
            return;
        }
        output.println(Server.CONTROL_PREFIX + command + ":" + argument);
    }

    private HandshakeContext performPeerHandshake(
            BufferedReader input,
            PrintWriter output,
            Encryption encryption,
            SessionAssignment assignment) throws IOException, GeneralSecurityException {

        if (assignment.role == HandshakeRole.INITIATOR) {
            return performInitiatorHandshake(input, output, encryption, assignment);
        }
        return performResponderHandshake(input, output, encryption, assignment);
    }

    private HandshakeContext performInitiatorHandshake(
            BufferedReader input,
            PrintWriter output,
            Encryption encryption,
            SessionAssignment assignment) throws IOException, GeneralSecurityException {

        String initiatorKemPublicKey = encryption.getEncodedKemPublicKey();
        String initiatorSigningPublicKey = encryption.getEncodedSigningPublicKey();
        output.println(KEM_PUBLIC_KEY_PREFIX + initiatorKemPublicKey);
        output.println(SIGNING_PUBLIC_KEY_PREFIX + initiatorSigningPublicKey);

        String responderKemPublicKey = readPayload(input, KEM_PUBLIC_KEY_PREFIX);
        String responderSigningPublicKey = readPayload(input, SIGNING_PUBLIC_KEY_PREFIX);

        Encryption.EncapsulationResult encapsulation = encryption.encapsulateSessionKey(responderKemPublicKey);
        SecretKey sessionKey = encapsulation.getSessionKey();
        String kemCiphertext = encapsulation.getEncapsulation();
        output.println(KEM_CIPHERTEXT_PREFIX + kemCiphertext);

        String transcript = buildHandshakeTranscript(
                assignment.sessionId,
                username,
                assignment.peerUsername,
                initiatorKemPublicKey,
                initiatorSigningPublicKey,
                responderKemPublicKey,
                responderSigningPublicKey,
                kemCiphertext);

        String initiatorSignature = encryption.sign(transcript);
        output.println(HANDSHAKE_SIGNATURE_PREFIX + initiatorSignature);

        String responderSignature = readPayload(input, HANDSHAKE_SIGNATURE_PREFIX);
        boolean responderSignatureValid = encryption.verify(transcript, responderSignature, responderSigningPublicKey);
        if (!responderSignatureValid) {
            throw new IOException("Responder handshake signature verification failed.");
        }

        output.println(ACK_PREFIX + "KEY_OK");
        System.out.println("Secure channel established with peer.");
        return new HandshakeContext(sessionKey, responderSigningPublicKey);
    }

    private HandshakeContext performResponderHandshake(
            BufferedReader input,
            PrintWriter output,
            Encryption encryption,
            SessionAssignment assignment) throws IOException, GeneralSecurityException {

        String initiatorKemPublicKey = readPayload(input, KEM_PUBLIC_KEY_PREFIX);
        String initiatorSigningPublicKey = readPayload(input, SIGNING_PUBLIC_KEY_PREFIX);

        String responderKemPublicKey = encryption.getEncodedKemPublicKey();
        String responderSigningPublicKey = encryption.getEncodedSigningPublicKey();
        output.println(KEM_PUBLIC_KEY_PREFIX + responderKemPublicKey);
        output.println(SIGNING_PUBLIC_KEY_PREFIX + responderSigningPublicKey);

        String kemCiphertext = readPayload(input, KEM_CIPHERTEXT_PREFIX);
        SecretKey sessionKey = encryption.decapsulateSessionKey(kemCiphertext);

        String transcript = buildHandshakeTranscript(
                assignment.sessionId,
                assignment.peerUsername,
                username,
                initiatorKemPublicKey,
                initiatorSigningPublicKey,
                responderKemPublicKey,
                responderSigningPublicKey,
                kemCiphertext);

        String initiatorSignature = readPayload(input, HANDSHAKE_SIGNATURE_PREFIX);
        boolean initiatorSignatureValid = encryption.verify(transcript, initiatorSignature, initiatorSigningPublicKey);
        if (!initiatorSignatureValid) {
            throw new IOException("Initiator handshake signature verification failed.");
        }

        String responderSignature = encryption.sign(transcript);
        output.println(HANDSHAKE_SIGNATURE_PREFIX + responderSignature);

        String ack = readPayload(input, ACK_PREFIX);
        if (!"KEY_OK".equals(ack)) {
            throw new IOException("Unexpected handshake acknowledgement: " + ack);
        }

        System.out.println("Secure channel established with peer.");
        return new HandshakeContext(sessionKey, initiatorSigningPublicKey);
    }

    private String exchangeNames(
            BufferedReader input,
            PrintWriter output,
            Encryption encryption,
            HandshakeContext handshakeContext,
            SessionAssignment assignment) throws IOException, GeneralSecurityException {

        String remoteName = assignment.peerUsername;
        if (assignment.role == HandshakeRole.INITIATOR) {
            sendEncryptedMessage(output, encryption, handshakeContext.sessionKey, NAME_PREFIX + username);
            String incoming = readEncryptedMessage(input, encryption, handshakeContext);
            if (incoming.startsWith(NAME_PREFIX) && incoming.length() > NAME_PREFIX.length()) {
                remoteName = incoming.substring(NAME_PREFIX.length());
            }
            if (!assignment.peerUsername.equals(remoteName)) {
            }
            return remoteName;
        }

        String incoming = readEncryptedMessage(input, encryption, handshakeContext);
        if (incoming.startsWith(NAME_PREFIX) && incoming.length() > NAME_PREFIX.length()) {
            remoteName = incoming.substring(NAME_PREFIX.length());
        }
        if (!assignment.peerUsername.equals(remoteName)) {
        }
        sendEncryptedMessage(output, encryption, handshakeContext.sessionKey, NAME_PREFIX + username);
        return remoteName;
    }

    private boolean startEncryptedChatLoop(
            Socket socket,
            BufferedReader input,
            PrintWriter output,
            BlockingQueue<String> consoleQueue,
            AtomicBoolean consoleRunning,
            Encryption encryption,
            HandshakeContext handshakeContext,
            String remoteName) {

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean connectionHealthy = new AtomicBoolean(true);

        Thread receiver = new Thread(() -> {
            try {
                while (running.get()) {
                    String decrypted = readEncryptedMessage(input, encryption, handshakeContext);
                    if (QUIT_COMMAND.equalsIgnoreCase(decrypted.trim())) {
                        System.out.println(remoteName + " has left the chat.");
                        running.set(false);
                        break;
                    }
                    System.out.println(remoteName + ": " + decrypted);
                }
            } catch (IOException | GeneralSecurityException ex) {
                if (running.get()) {
                    System.out.println("Connection closed: " + ex.getMessage());
                    connectionHealthy.set(false);
                }
            } finally {
                running.set(false);
            }
        }, "receiver-" + username);

        receiver.setDaemon(true);
        receiver.start();

        System.out.println("Secure chat started as " + username + ". Type /quit to end the chat.");

        while (running.get()) {
            String message = pollConsoleLine(consoleQueue, CONSOLE_POLL_MS);
            if (message == null) {
                if (!consoleRunning.get()) {
                    running.set(false);
                    connectionHealthy.set(false);
                    break;
                }
                continue;
            }

            try {
                sendEncryptedMessage(output, encryption, handshakeContext.sessionKey, message);
            } catch (GeneralSecurityException ex) {
                System.out.println("Failed to encrypt message: " + ex.getMessage());
                running.set(false);
                connectionHealthy.set(false);
                break;
            }

            if (QUIT_COMMAND.equalsIgnoreCase(message.trim())) {
                running.set(false);
                break;
            }
        }

        try {
            receiver.join(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            connectionHealthy.set(false);
        }

        if (!socket.isClosed()) {
            sendControlCommand(output, Server.END_SESSION_CMD, null);
        }

        if (!connectionHealthy.get() || socket.isClosed()) {
            return false;
        }

        System.out.println("Conversation ended. Back in lobby.");
        return true;
    }

    private String readPayload(BufferedReader input, String prefix) throws IOException {
        String line = input.readLine();
        if (line == null) {
            throw new IOException("Connection closed while waiting for protocol payload.");
        }
        if (!line.startsWith(prefix)) {
            throw new IOException("Unexpected protocol message: " + line);
        }
        return line.substring(prefix.length());
    }

    private void sendEncryptedMessage(
            PrintWriter output,
            Encryption encryption,
            SecretKey sessionKey,
            String plaintext) throws GeneralSecurityException {

        String encryptedPayload = encryption.encryptMessage(sessionKey, plaintext);
        String signature = encryption.sign(encryptedPayload);
        output.println(MESSAGE_PREFIX + encryptedPayload + MESSAGE_SIGNATURE_DELIMITER + signature);
    }

    private String readEncryptedMessage(
            BufferedReader input,
            Encryption encryption,
            HandshakeContext context) throws IOException, GeneralSecurityException {

        String line = input.readLine();
        if (line == null) {
            throw new IOException("Connection closed while reading encrypted message.");
        }
        if (!line.startsWith(MESSAGE_PREFIX)) {
            throw new IOException("Unexpected encrypted message format.");
        }

        String payload = line.substring(MESSAGE_PREFIX.length());
        int signatureIndex = payload.indexOf(MESSAGE_SIGNATURE_DELIMITER);
        if (signatureIndex <= 0) {
            throw new IOException("Missing ML-DSA signature in encrypted message.");
        }

        String encryptedPayload = payload.substring(0, signatureIndex);
        String signature = payload.substring(signatureIndex + MESSAGE_SIGNATURE_DELIMITER.length());
        boolean signatureValid = encryption.verify(encryptedPayload, signature, context.remoteSigningPublicKey);
        if (!signatureValid) {
            throw new GeneralSecurityException("Message signature verification failed.");
        }

        return encryption.decryptMessage(context.sessionKey, encryptedPayload);
    }

    private String buildHandshakeTranscript(
            int sessionId,
            String initiatorUsername,
            String responderUsername,
            String initiatorKemPublicKey,
            String initiatorSigningPublicKey,
            String responderKemPublicKey,
            String responderSigningPublicKey,
            String kemCiphertext) {

        return String.join(
                "|",
                "sessionId=" + sessionId,
                "initiator=" + initiatorUsername,
                "responder=" + responderUsername,
                initiatorKemPublicKey,
                initiatorSigningPublicKey,
                responderKemPublicKey,
                responderSigningPublicKey,
                kemCiphertext);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java Client [serverIp] [port] [username]");
        System.out.println("Examples:");
        System.out.println("  java Client 127.0.0.1 5000 Alice");
        System.out.println("  java Client 127.0.0.1 5000 Bob");
    }

    private enum HandshakeRole {
        INITIATOR,
        RESPONDER
    }

    private static final class HandshakeContext {
        private final SecretKey sessionKey;
        private final String remoteSigningPublicKey;

        private HandshakeContext(SecretKey sessionKey, String remoteSigningPublicKey) {
            this.sessionKey = sessionKey;
            this.remoteSigningPublicKey = remoteSigningPublicKey;
        }
    }

    private static final class SessionAssignment {
        private final HandshakeRole role;
        private final int sessionId;
        private final String peerUsername;

        private SessionAssignment(HandshakeRole role, int sessionId, String peerUsername) {
            this.role = role;
            this.sessionId = sessionId;
            this.peerUsername = peerUsername;
        }
    }
}
