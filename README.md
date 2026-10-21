# session-worker

A [House Elf](https://github.com/House-elves) that turns the principal's email into Claude Code sessions on the host. Email bin chicken from anywhere; the reply you get back is a live session's answer, and replying to it continues the same session - context and all.

session-worker never touches the mailbox. mail-worker (private) owns the mail credentials and the trust gate, and hands work over on the [elf-bus](https://github.com/House-elves/elf-bus-common) as `session.run` envelopes.

## How a mail trail becomes a session

```
principal emails bin.chicken@...
  |  mail-worker tick (IMAP)
  |  gate: sender in SESSION_SENDERS  AND  Authentication-Results: dkim=pass
  |  attachments spooled to disk; References root = thread_key
  v
elf-bus: session.run { thread_key, subject, body, from, attachments[] }
  |  session-worker tick
  |  ThreadStore: thread_key -> claude session id
  |  new thread:   claude -p "<framed email>"       --output-format json
  |  continuation: claude --resume <id> -p "<reply>" --output-format json
  v
elf-bus: session.run.result { status, reply_body, session_id }
  |  mail-worker tick
  v
threaded SMTP reply to the principal ("reply to this email to continue")
```

The mail thread IS the session: the thread's root `Message-Id` maps to a persistent Claude session id, so a reply arrives with the full context of everything the session has already seen and done.

## Security model

This elf is **remote code execution by design** - an email becomes a shell-capable Claude session. The gates, in order:

1. **mail-worker's sender gate.** Only `SESSION_SENDERS` (the principal's own addresses) are ever routed here, and only when Gmail's `Authentication-Results` shows `dkim=pass` - a spoofed `From:` fails DKIM and falls back to mail-worker's zero-tool triage.
2. **session-worker's own copy.** The bus is host-writable, so this elf re-checks the envelope's `from` against its own `SESSION_SENDERS` config before running anything.
3. **No mail credentials here; no shell there.** mail-worker cannot run commands; session-worker cannot read or send mail.
4. **Bounded runs.** One session at a time (single-instance lock), a hard timeout per run, reply bodies truncated at `MAX_REPLY_CHARS`.

What this does NOT protect against, by definition: the principal themselves. `PERMISSION_MODE=bypassPermissions` means an email from you can do anything your shell can. That is the feature. If that trade sits wrong, set `PERMISSION_MODE=acceptEdits` and accept that sessions needing broader permissions will stall.

Failures are returned as `session.run.result` with `status=error` (never retried - a session may have edited files before failing, so replay is wrong), and the principal hears about it as a threaded reply.

## Install

```bash
jbang app install session-worker@House-elves/session-worker

# Interactive setup - prompts for workdir, senders, permission mode, schedule
session-worker --install

systemctl --user daemon-reload
systemctl --user enable --now session-worker.timer
```

Then set the SAME `SESSION_SENDERS` list in mail-worker's config, and you're live.

## Configuration

Stored in `~/.config/session-worker/config` as `KEY=VALUE` lines:

| Key | Required | Default | Description |
|---|---|---|---|
| `ELF_NAME` | no | `session-worker` | Identity on the elf-bus. |
| `WORKDIR` | yes | - | Directory sessions run in (usually your projects root). |
| `SESSION_SENDERS` | yes | - | Comma-separated principal addresses. Must match mail-worker's list. |
| `PERMISSION_MODE` | no | `bypassPermissions` | Passed to `claude --permission-mode`. See security model. |
| `AGENT_MODEL` | no | (CLI default) | Passed to `claude --model` when set. |
| `TIMEOUT_MINUTES` | no | `15` | Hard cap per session run. |
| `MAX_REPLY_CHARS` | no | `12000` | Reply body truncation. |
| `ACTIVE_HOURS` | no | `07-22` | Only run between these hours. |
| `BUS_ROOT` | no | `$XDG_DATA_HOME/elf-bus` | Where peer inboxes live. |
| `STATE_DIR` | no | `$XDG_STATE_HOME/session-worker` | Lock, seen-ids, threads.json. |

## State

- `threads.json` - the thread-to-session map. Delete an entry to make the next reply in that thread start a fresh session.
- `elf-bus-seen` - processed envelope ids (idempotency).

## See also

- [elf-bus-common](https://github.com/House-elves/elf-bus-common) - the bus transport + the `session.run` kind spec
- mail-worker (private) - the mailbox side of this feature

## License

Apache 2.0 - see [`LICENSE`](LICENSE).
