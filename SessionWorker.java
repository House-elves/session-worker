///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS jakarta.mail:jakarta.mail-api:2.1.3
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java SessionRunner.java ThreadStore.java Installer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/EnvelopeOptions.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusEnvelope.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusHandler.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusProducer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusInboxes.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusSeenIds.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/FileSystemBus.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusConsumer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/InMemoryBus.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A House Elf that turns the principal's email into Claude Code sessions on
 * this host. mail-worker owns the mailbox and the trust gate (SESSION_SENDERS
 * + DKIM); this elf owns the sessions: it consumes session.run envelopes,
 * maps each mail thread to a persistent Claude session id, runs the claude
 * CLI (resuming the session when the thread continues), and returns the
 * final answer as a session.run.result for mail-worker to send back as a
 * threaded reply.
 *
 * This elf never holds mail credentials; mail-worker never runs a shell.
 */
@Command(name = "session-worker", mixinStandardHelpOptions = true,
        description = "Runs Claude Code sessions from principal email, via the elf-bus.")
public class SessionWorker implements Runnable {

    @Option(names = "--install", description = "Interactive setup: config, dirs, systemd units.")
    boolean install;

    @Option(names = "--once", description = "Single tick (what the systemd timer runs).")
    boolean once;

    @Option(names = "--status", description = "Show config summary and known threads.")
    boolean status;

    public static void main(String[] args) {
        System.exit(new CommandLine(new SessionWorker()).execute(args));
    }

    @Override
    public void run() {
        if (install) {
            Installer.run();
            return;
        }

        Config config = Config.load();
        config.validate();

        if (status) {
            printStatus(config);
            return;
        }

        if (!config.isWithinActiveHours()) {
            System.out.println("outside active hours; exiting");
            return;
        }

        try (var lock = SingleInstanceLock.tryAcquire(config.lockPath())) {
            if (lock == null) {
                // A long session may legitimately still be running from the
                // previous tick; skipping is the correct behaviour.
                System.err.println("another session-worker is running; exiting");
                return;
            }

            var bus     = new FileSystemBus(config.busRoot(), config.elfName());
            var threads = ThreadStore.open(config.threadsPath());
            var runner  = new SessionRunner(config);

            var consumer = new ElfBusConsumer(
                    config.busRoot(),
                    config.elfName(),
                    config.busSeenIdsPath(),
                    List.of(new SessionRunHandler(config, threads, runner)),
                    /*replyProducer*/ bus
            );
            consumer.poll();
        } catch (IOException e) {
            System.err.println("session-worker: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printStatus(Config config) {
        System.out.println("workdir:         " + config.workDir);
        System.out.println("permission mode: " + config.permissionMode);
        System.out.println("model:           " + (config.agentModel.isEmpty() ? "(cli default)" : config.agentModel));
        System.out.println("timeout:         " + config.timeoutMinutes + " min");
        System.out.println("session senders: " + config.sessionSenders);
        try {
            var threads = ThreadStore.open(config.threadsPath());
            System.out.println("known threads:   " + threads.size());
        } catch (IOException e) {
            System.out.println("known threads:   (unreadable: " + e.getMessage() + ")");
        }
    }

    /**
     * The session.run handler. Errors are RETURNED as {status:error} rather
     * than thrown: a session is not idempotent (it may have edited files
     * before failing), so retries are wrong, and the principal should hear
     * about the failure as a threaded reply rather than finding a
     * dead-letter by hand.
     */
    static final class SessionRunHandler implements ElfBusHandler {
        private final Config config;
        private final ThreadStore threads;
        private final SessionRunner runner;

        SessionRunHandler(Config config, ThreadStore threads, SessionRunner runner) {
            this.config = config;
            this.threads = threads;
            this.runner = runner;
        }

        @Override public String kind() { return "session.run"; }

        @Override
        public Map<String, Object> execute(ElfBusEnvelope env) {
            var b = env.body();
            String from      = b.path("from").asText("");
            String threadKey = b.path("thread_key").asText("");
            String subject   = b.path("subject").asText("");
            String body      = b.path("body").asText("");

            // Defence in depth: mail-worker already gated on SESSION_SENDERS
            // + DKIM, but the bus is writable by anything on this host, so
            // re-check the principal here with our own config copy.
            if (from.isEmpty() || !config.sessionSenders.contains(from.toLowerCase())) {
                System.err.println("session.run from non-session sender '" + from + "'; refusing");
                return Map.of("status", "error",
                        "error", "sender is not on session-worker's SESSION_SENDERS list");
            }
            if (threadKey.isEmpty() || body.isEmpty()) {
                return Map.of("status", "error", "error", "thread_key and body are required");
            }

            List<String> attachments = new java.util.ArrayList<>();
            b.path("attachments").forEach(n -> attachments.add(n.asText()));

            String existingSession = threads.get(threadKey);
            System.out.printf("session.run thread=%s session=%s subj=%s%n",
                    threadKey, existingSession == null ? "(new)" : existingSession, subject);

            try {
                SessionRunner.Outcome out = runner.run(existingSession, subject, body, from, attachments);
                threads.put(threadKey, out.sessionId());
                String reply = out.replyBody();
                if (reply.length() > config.maxReplyChars) {
                    reply = reply.substring(0, config.maxReplyChars)
                            + "\n\n[reply truncated at " + config.maxReplyChars + " characters]";
                }
                return Map.of("status", "ok", "reply_body", reply, "session_id", out.sessionId());
            } catch (Exception e) {
                System.err.println("session failed: " + e.getMessage());
                return Map.of("status", "error",
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    /** Same single-instance lock idiom as mail-worker. */
    static final class SingleInstanceLock implements AutoCloseable {
        private final RandomAccessFile file;
        private final FileLock lock;

        private SingleInstanceLock(RandomAccessFile file, FileLock lock) {
            this.file = file;
            this.lock = lock;
        }

        static SingleInstanceLock tryAcquire(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
            FileLock lock = raf.getChannel().tryLock();
            if (lock == null) {
                raf.close();
                return null;
            }
            return new SingleInstanceLock(raf, lock);
        }

        @Override public void close() throws IOException {
            lock.release();
            file.close();
        }
    }
}
