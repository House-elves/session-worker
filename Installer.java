import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Interactive setup, same shape as mail-worker's: prompts, writes
 * ~/.config/session-worker/config (0600), bootstraps state + bus dirs,
 * writes systemd user units. No secrets are prompted - this elf holds none.
 */
public final class Installer {

    private Installer() {}

    public static void run() {
        Console console = System.console();
        BufferedReader fallback = (console == null)
                ? new BufferedReader(new InputStreamReader(System.in))
                : null;

        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("ELF_NAME",        prompt(console, fallback, "Elf name", "session-worker"));
        cfg.put("WORKDIR",         require(prompt(console, fallback,
                "Work directory for sessions (repo root)",
                System.getProperty("user.home") + "/Projects")));
        cfg.put("SESSION_SENDERS", require(prompt(console, fallback,
                "Session senders (comma-sep; MUST match mail-worker's SESSION_SENDERS)", "")));
        cfg.put("PERMISSION_MODE", prompt(console, fallback,
                "Claude permission mode (bypassPermissions = unattended; the sessions run "
                        + "with your shell)", "bypassPermissions"));
        cfg.put("AGENT_MODEL",     prompt(console, fallback, "Claude model (blank = CLI default)", ""));
        cfg.put("TIMEOUT_MINUTES", prompt(console, fallback, "Session timeout (minutes)", "15"));
        cfg.put("ACTIVE_HOURS",    prompt(console, fallback, "Active hours (e.g. 07-22)", "07-22"));
        String cadenceMin = prompt(console, fallback, "Run every N minutes", "2");

        try {
            writeConfig(cfg);
            bootstrapDirs(cfg.get("ELF_NAME"));
            writeSystemd(cadenceMin);
            printNextSteps();
        } catch (IOException e) {
            System.err.println("install failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void writeConfig(Map<String, String> cfg) throws IOException {
        Files.createDirectories(Config.CONFIG_DIR);
        StringBuilder sb = new StringBuilder("# session-worker config - written by --install\n");
        cfg.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Files.writeString(Config.CONFIG_PATH, sb.toString());
        try {
            Files.setPosixFilePermissions(Config.CONFIG_PATH,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem; nothing secret in here anyway
        }
        System.out.println("wrote " + Config.CONFIG_PATH);
    }

    private static void bootstrapDirs(String elfName) throws IOException {
        Files.createDirectories(Config.STATE_DIR_DEFAULT);
        Path inbox = Config.BUS_ROOT_DEFAULT.resolve(elfName).resolve("Maildir");
        for (String sub : new String[]{"tmp", "new", "cur", ".dead/tmp", ".dead/new", ".dead/cur"}) {
            Files.createDirectories(inbox.resolve(sub));
        }
        System.out.println("bootstrapped " + inbox);
    }

    private static void writeSystemd(String cadenceMin) throws IOException {
        Path unitDir = Path.of(System.getProperty("user.home"), ".config", "systemd", "user");
        Files.createDirectories(unitDir);

        String service = """
                [Unit]
                Description=session-worker (run Claude sessions from principal email)
                After=network-online.target

                [Service]
                Type=oneshot
                Environment=PATH=%h/.local/bin:%h/.jbang/bin:%h/.sdkman/candidates/jbang/current/bin:%h/.sdkman/candidates/java/current/bin:/usr/local/bin:/usr/bin
                ExecStart=%h/.jbang/bin/session-worker --once
                """;

        String timer = String.format("""
                [Unit]
                Description=session-worker every %s minutes

                [Timer]
                OnCalendar=*:0/%s
                Persistent=true

                [Install]
                WantedBy=timers.target
                """, cadenceMin, cadenceMin);

        Files.writeString(unitDir.resolve("session-worker.service"), service);
        Files.writeString(unitDir.resolve("session-worker.timer"), timer);
        System.out.println("wrote systemd user units (service + timer)");
    }

    private static void printNextSteps() {
        System.out.println("""

                Next steps:
                  systemctl --user daemon-reload
                  systemctl --user enable --now session-worker.timer

                And in mail-worker's config, set SESSION_SENDERS to the same list.
                """);
    }

    // ---- prompts ----

    private static String prompt(Console console, BufferedReader fallback, String label, String dflt) {
        String suffix = dflt.isEmpty() ? "" : " [" + dflt + "]";
        try {
            String s;
            if (console != null) {
                s = console.readLine("%s%s: ", label, suffix);
            } else {
                System.out.print(label + suffix + ": ");
                s = fallback.readLine();
            }
            if (s == null || s.isBlank()) return dflt;
            return s.strip();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            System.err.println("required value missing; aborting");
            System.exit(1);
        }
        return value;
    }
}
