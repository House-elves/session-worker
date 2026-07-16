import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * The heart of "reply to continue": a JSON file mapping a mail thread's root
 * Message-Id to the Claude session id it runs in. Loaded per tick, written
 * atomically on every change.
 */
public class ThreadStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;
    private final Map<String, String> map;

    private ThreadStore(Path path, Map<String, String> map) {
        this.path = path;
        this.map = map;
    }

    @SuppressWarnings("unchecked")
    static ThreadStore open(Path path) throws IOException {
        Map<String, String> map = new HashMap<>();
        if (Files.exists(path)) {
            map.putAll(MAPPER.readValue(Files.readString(path), Map.class));
        }
        return new ThreadStore(path, map);
    }

    String get(String threadKey) {
        return map.get(threadKey);
    }

    void put(String threadKey, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        map.put(threadKey, sessionId);
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(map));
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Non-fatal: the session ran; worst case the NEXT reply starts a
            // fresh session instead of resuming. Log and continue.
            System.err.println("thread store write failed: " + e.getMessage());
        }
    }

    int size() {
        return map.size();
    }
}
