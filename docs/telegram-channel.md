# SCAMERA builds bot (Telegram)

Every successful CI build of a push to `main` posts its APK (`SCAMERA-0.98-<code>-SCAM.apk`) to a Telegram group
topic or channel. Only the APK is posted: `SHA256SUMS.txt`, the neural-assets bundle and the test reports stay in
Actions. The owner configures the bot in a private chat with it.

| Piece | What it does |
|---|---|
| `tools/telegram_publish.py` | posts one APK; step "Post the APK to Telegram" of `build-test-apk.yml` |
| `tools/telegram_bot.py` | answers the owner's commands; `.github/workflows/telegram-bot.yml`, every 5 minutes |
| bot's pinned message in the owner's chat | the settings (summary + JSON line); both scripts read it there |

The APK (~54 MB) is above the Bot API upload limit (50 MB), so posting logs the bot in over MTProto (Telethon),
where bots upload up to 2 GB; that needs an application id/hash besides the bot token. The commands go through the
plain Bot API. GitHub starts the schedule every 5 minutes at best, so the bot answers in 5–15 minutes.

## Setup (once)

1. @BotFather → `/newbot` → copy the token.
2. https://my.telegram.org → API development tools → create an application → copy `api_id` and `api_hash`.
3. GitHub → repository Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `TELEGRAM_BOT_TOKEN` | the bot token |
| `TELEGRAM_API_ID` | `api_id` (a number) |
| `TELEGRAM_API_HASH` | `api_hash` |
| `TELEGRAM_OWNER` | the owner's username without `@` (or numeric id, shown by `/status`): the only account the bot obeys |
| `TELEGRAM_CHAT` | optional, instead of `/here`: `@channel`, `-100…` id or a topic link `https://t.me/c/<id>/<topic>` |
| `TELEGRAM_TOPIC` | optional: topic id for `TELEGRAM_CHAT` |

4. Push to `main` (the schedule runs only from the default branch).
5. Open the bot, press Start: it pins its settings message.
6. Add the bot to the group (as an admin, or a member allowed to send messages and files) and write `/here` in the
   topic that should get the builds. The bot confirms there and in the private chat.

## Commands (private chat, owner only)

| Command | |
|---|---|
| `/status` | settings and your numeric id |
| `/on`, `/off` | auto-posting of new builds (`/post` works either way) |
| `/post`, `/post 30830` | post the latest successful `main` build, or a given build (artifacts are kept 90 days) |
| `/here` | in a group or one of its topics: post builds there |
| `/commits on\|off`, `/sha on\|off`, `/cilink on\|off` | commit list, SHA-256, CI link in the caption |
| `/header text`, `/header -` | your text at the top of each post / remove it |

Other people get a refusal; commands of other bots in the group are ignored. The settings message must stay pinned
(unpinned = defaults; `/start` pins a new one). A Telegram error fails only the post step (the build stays green,
the APK is in the artifact) and the bot sends the owner the reason, e.g. `ChatWriteForbiddenError` = the bot may not
write to that topic. If the username changes, update `TELEGRAM_OWNER`.

## Check locally

```bash
python tools/telegram_publish.py --apk path/to/SCAMERA-0.98-30831-SCAM.apk --from-rev HEAD~5 --dry-run --settings-json '{"sha": false}'
```

prints the caption without posting.
