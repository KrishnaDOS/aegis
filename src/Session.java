import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class Session implements Runnable {

    private final int sessionId;
    private final Server.ClientEndpoint initiator;
    private final Server.ClientEndpoint responder;
    private final AtomicBoolean closed;

    public Session(
            int sessionId,
            Server.ClientEndpoint initiator,
            Server.ClientEndpoint responder) {

        this.sessionId = sessionId;
        this.initiator = initiator;
        this.responder = responder;
        this.closed = new AtomicBoolean(false);
    }

    @Override
    public void run() {
        try {
            initiator.sendControl(Server.SERVER_PREFIX, Server.SESSION_READY_MESSAGE);
            responder.sendControl(Server.SERVER_PREFIX, Server.SESSION_READY_MESSAGE);

            initiator.sendControl(Server.ROLE_PREFIX, Server.INITIATOR_ROLE);
            responder.sendControl(Server.ROLE_PREFIX, Server.RESPONDER_ROLE);

            Thread forwardAtoB = new Thread(() -> relay(initiator, responder, "A->B"),
                    "session-" + sessionId + "-relay-a2b");
            Thread forwardBtoA = new Thread(() -> relay(responder, initiator, "B->A"),
                    "session-" + sessionId + "-relay-b2a");

            forwardAtoB.start();
            forwardBtoA.start();

            forwardAtoB.join();
            forwardBtoA.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            shutdown();
        }
    }

    private void relay(Server.ClientEndpoint from, Server.ClientEndpoint to, String direction) {
        try {
            String line;
            while (!closed.get() && (line = from.readLine()) != null) {
                to.writeLine(line);
            }
        } catch (IOException ex) {
        } finally {
            shutdown();
        }
    }

    private void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        initiator.closeQuietly();
        responder.closeQuietly();
    }
}
