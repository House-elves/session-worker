import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shells out to the claude CLI. A new thread gets `claude -p`; a continuing
 * thread gets `claude --resume <sessionId> -p` - which is the whole trick:
 * the mail thread IS the session, context and all.
 *
 * Output format is `--output-format json`, whose single result object
 * carries both the final text ("result") and the session id we persist.
 */
public class SessionRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Outcome(String sessionId, String replyBody) {}

    private final Config config;

    public SessionRunner(Config config) { this.config = config; }

    public Outcome run(String resumeSessionId, String subject, String body,
            String from, List<String> attachments) throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>();
        cmd.add("claude");
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            cmd.add("--resume");
            cmd.add(resumeSessionId);
        }
        cmd.add("-p");
        cmd.add(buildPrompt(resumeSessionId != null, subject, body, from, attachments));
        cmd.add("--output-format");
        cmd.add("json");
        cmd.add("--permission-mode");
        cmd.add(config.permissionMode);
        if (!config.agentModel.isEmpty()) {
            cmd.add("--model");
            cmd.add(config.agentModel);
        }

        Path out = Files.createTempFile("session-worker-", ".json");
        try {
            Process p = new ProcessBuilder(cmd)
                    .directory(config.workDir.toFile())
                    .redirectOutput(out.toFile())
                    .redirectErrorStream(false)
                    .start();
            if (!p.waitFor(config.timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("session timed out after " + config.timeoutMinutes
                        + " minutes (work so far, if any, is on disk in " + config.workDir + ")");
            }
            String json = Files.readString(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0 && json.isBlank()) {
                throw new IOException("claude exited " + p.exitValue() + " with no output");
            }
            JsonNode node = MAPPER.readTree(json);
            String sessionId = node.path("session_id").asText("");
            String result = node.path("result").asText("");
            if (node.path("is_error").asBoolean(false)) {
                throw new IOException("claude reported an error: "
                        + (result.isEmpty() ? node.path("subtype").asText("unknown") : result));
            }
            return new Outcome(sessionId, result);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /**
     * Frames the email for the session. The body is TRUSTED here by
     * definition - only the DKIM-verified principal reaches this elf; the
     * framing is about output shape, not injection defence.
     */
    private String buildPrompt(boolean continuing, String subject, String body,
            String from, List<String> attachments) {
        StringBuilder sb = new StringBuilder();
        if (continuing) {
            sb.append("The principal replied to your last email in this session's mail thread.\n\n");
        } else {
            sb.append("You are running as a headless session started by an email from your principal (")
              .append(from).append("). Do what the email asks.\n\n");
        }
        sb.append("Subject: ").append(subject).append('\n');
        if (!attachments.isEmpty()) {
            sb.append("Attached files (already on local disk):\n");
            for (String a : attachments) sb.append("  ").append(a).append('\n');
        }
        sb.append("\n").append(body).append("\n\n");
        sb.append("Your final message will be emailed back as a plain-text reply, and the ")
          .append("principal's reply to it continues this session. Lead with the outcome, ")
          .append("keep it readable in a mail client (no markdown tables or headers), and ")
          .append("say clearly if you need a decision from the principal to proceed.");
        return sb.toString();
    }
}
