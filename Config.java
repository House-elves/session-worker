import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * KEY=VALUE config at ~/.config/session-worker/config, same shape as the
 * other elves. No mail credentials live here - mail-worker owns the mailbox.
 */
public class Config {

    static final Path CONFIG_DIR  = Path.of(System.getProperty("user.home"), ".config", "session-worker");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");
    static final Path STATE_DIR_DEFAULT = Path.of(
            System.getenv().getOrDefault("XDG_STATE_HOME",
                    System.getProperty("user.home") + "/.local/state")).resolve("session-worker");
    static final Path BUS_ROOT_DEFAULT = Path.of(
            System.getenv().getOrDefault("XDG_DATA_HOME",
                    System.getProperty("user.home") + "/.local/share")).resolve("elf-bus");

    String elfName;
    Path   workDir;          // where claude sessions run (repo root, usually ~/Projects)
    String agentModel;       // empty = claude CLI default
    String permissionMode;   // claude --permission-mode value
    int    timeoutMinutes;   // hard cap per session run
    int    maxReplyChars;    // reply body truncation
    Set<String> sessionSenders; // defence-in-depth copy of mail-worker's list
    String activeHours;

    Path stateDir;
    Path busRoot;

    static Config load() {
        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Config file not found: " + CONFIG_PATH);
            System.err.println("Run: session-worker --install");
            System.exit(1);
        }
        Map<String, String> raw = new HashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) raw.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            System.exit(1);
        }

        Config c = new Config();
        c.elfName        = raw.getOrDefault("ELF_NAME", "session-worker");
        c.workDir        = Path.of(raw.getOrDefault("WORKDIR", ""));
        c.agentModel     = raw.getOrDefault("AGENT_MODEL", "");
        c.permissionMode = raw.getOrDefault("PERMISSION_MODE", "bypassPermissions");
        c.timeoutMinutes = Integer.parseInt(raw.getOrDefault("TIMEOUT_MINUTES", "15"));
        c.maxReplyChars  = Integer.parseInt(raw.getOrDefault("MAX_REPLY_CHARS", "12000"));
        c.sessionSenders = parseSet(raw.getOrDefault("SESSION_SENDERS", ""))
                                .stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
        c.activeHours    = raw.getOrDefault("ACTIVE_HOURS", "07-22");
        c.stateDir       = Path.of(raw.getOrDefault("STATE_DIR", STATE_DIR_DEFAULT.toString()));
        c.busRoot        = Path.of(raw.getOrDefault("BUS_ROOT", BUS_ROOT_DEFAULT.toString()));
        return c;
    }

    void validate() {
        if (workDir.toString().isEmpty() || !Files.isDirectory(workDir)) {
            System.err.println("Error: WORKDIR must be an existing directory (got '" + workDir + "')");
            System.exit(1);
        }
        if (sessionSenders.isEmpty()) {
            System.err.println("Error: SESSION_SENDERS must list at least one address");
            System.exit(1);
        }
    }

    boolean isWithinActiveHours() {
        try {
            String[] parts = activeHours.split("-");
            int start = Integer.parseInt(parts[0]);
            int end   = Integer.parseInt(parts[1]);
            int hour  = LocalTime.now().getHour();
            return start <= hour && hour < end;
        } catch (Exception e) {
            return true;
        }
    }

    String elfName()        { return elfName; }
    Path   busRoot()        { return busRoot; }
    Path   lockPath()       { return stateDir.resolve("lock"); }
    Path   busSeenIdsPath() { return stateDir.resolve("elf-bus-seen"); }
    Path   threadsPath()    { return stateDir.resolve("threads.json"); }

    private static Set<String> parseSet(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
