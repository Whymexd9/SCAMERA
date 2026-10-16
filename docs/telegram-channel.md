# SCAMERA builds bot (Telegram)

Every successful CI build of a push to `main` posts its APK (`SCAMERA-0.98-<code>-SCAM.apk`) to a Telegram group
topic or channel and pins it there (silently). Only the APK is posted: `SHA256SUMS.txt`, the neural-assets bundle
and the test reports stay in Actions, and the caption has no links to GitHub. The owner configures the bot in a
private chat with it; it answers at once. The bot needs the rights to send messages and to pin them.

| Piece | What it does |
|---|---|
| `tools/telegram-worker/worker.js` | Cloudflare Worker = the Telegram webhook: answers the owner's commands at once |
| `tools/telegram_publish.py` | posts one APK; step "Post the APK to Telegram" of `build-test-apk.yml` |
| `tools/telegram_bot.py --post` | `/post`: the Worker starts `telegram-bot.yml`, which takes the APK out of the artifact |
| bot's pinned message in the owner's chat | the settings (summary + JSON line); the Worker and both scripts read it there |

The APK (~54 MB) is above the Bot API upload limit (50 MB), so posting logs the bot in over MTProto (Telethon),
where bots upload up to 2 GB; that needs an application id/hash besides the bot token. The Worker only sends text.
`telegram_bot.py` without `--post` answers waiting messages once by polling; it works only while no webhook is set.

Changelog ("Что нового после <previous build>"): `telegram_publish.py` finds the previous successful push build of
`main` (Actions API), takes the commits between the two builds (compare API, merges left out) and has Cloudflare
Workers AI (`@cf/meta/llama-3.3-70b-instruct-fp8-fast`, free plan: 10k neurons a day, one changelog ~200) retell them
as 1–6 short items for users, in the bot's language. Without the Cloudflare secrets, or when the model fails, the
changelog is the commit subjects without task numbers and plan/merge commits.

## Setup (once)

GitHub → repository Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `TELEGRAM_BOT_TOKEN` | token from @BotFather |
| `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` | https://my.telegram.org → API development tools |
| `TELEGRAM_OWNER` | the owner's username without `@` (or numeric id, shown by `/status`): the only account the bot obeys |
| `TELEGRAM_CHAT`, `TELEGRAM_TOPIC` | optional, instead of `/here`: `@channel`, `-100…` id or a topic link `https://t.me/c/<id>/<topic>` |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare account id (dashboard URL `dash.cloudflare.com/<id>/…`, or Workers & Pages → Account details) |
| `CLOUDFLARE_AI_TOKEN` | Cloudflare → AI → Workers AI → Use REST API → "Create a Workers AI API Token" (Workers AI Read + Edit) |

GitHub token for `/post`: GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens →
Generate: Repository access "Only select repositories" → SCAMERA; Permissions → Repository → Actions: Read and write.

Cloudflare Worker (free plan, no card):
1. https://dash.cloudflare.com → sign up → Workers & Pages → Create → Worker → name `scamera-bot` → Deploy.
2. Edit code → replace everything with `tools/telegram-worker/worker.js` → Deploy.
3. Worker → Settings → Variables and Secrets → Add, type Secret:
   `TELEGRAM_BOT_TOKEN`, `TELEGRAM_OWNER`, `GITHUB_TOKEN` (the fine-grained token), `WEBHOOK_SECRET` (any 20–40
   letters/digits, invented once). Deploy.
4. Open `https://scamera-bot.<your-subdomain>.workers.dev/setup?key=<WEBHOOK_SECRET>`: it must print
   `Webhook set: …`. From then on Telegram delivers every message to the Worker.

Then: in the bot's private chat press Start (it pins its settings message); make the bot an admin of the group and
write `/here` in the topic that should get the builds.

## Commands (private chat, owner only)

| Command | |
|---|---|
| `/status` | settings and your numeric id |
| `/on`, `/off` | auto-posting of new builds (`/post` works either way) |
| `/post`, `/post 30830` | post the latest successful `main` build, or a given build (artifacts are kept 90 days); 1–3 min |
| `/here` | in a group or one of its topics: post builds there |
| `/commits on\|off`, `/sha on\|off` | the changelog, SHA-256 in the caption |
| `/header text`, `/header -` | your text at the top of each post / remove it |

Other people get a refusal; commands of other bots in the group are ignored. The settings message must stay pinned
(unpinned = defaults; `/start` pins a new one). A Telegram error fails only the post step (the build stays green,
the APK is in the artifact) and the bot sends the owner the reason, e.g. `ChatWriteForbiddenError` = the bot may not
write to that topic. If the username changes, update `TELEGRAM_OWNER` in GitHub and in the Worker.

## Check locally

```bash
python tools/telegram_publish.py --apk path/to/SCAMERA-0.98-30831-SCAM.apk --from-rev HEAD~5 --dry-run --settings-json '{"sha": false}'
```

prints the caption without posting.
