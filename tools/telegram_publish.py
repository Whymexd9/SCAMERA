#!/usr/bin/env python3
"""Post a CI-built SCAMERA APK to a Telegram group or channel (docs/telegram-channel.md).

Only the APK is posted, and pinned; SHA256SUMS.txt and the other artifacts stay in Actions, and the caption has no
links to GitHub (owner's wish). The APK is larger than the Bot API upload limit (50 MB), so the bot logs in over
MTProto (Telethon), where a bot may upload files up to 2 GB.
Where to post and what the caption shows come from the bot's settings: the owner changes them in a private chat with
the bot (tools/telegram_bot.py) and they live in the bot's pinned message of that chat.

Environment (Actions secrets):
  TELEGRAM_BOT_TOKEN  token from @BotFather
  TELEGRAM_API_ID     application id and hash from https://my.telegram.org (any account; only identifies the client)
  TELEGRAM_API_HASH
  TELEGRAM_OWNER      the owner's @username or numeric id: the only account the bot obeys, settings are read there
  TELEGRAM_CHAT       optional target until /here is used: @username, -100... id or a topic link t.me/c/<id>/<topic>
  TELEGRAM_TOPIC      optional topic id for TELEGRAM_CHAT
  GH_TOKEN            the workflow's token: finds the previous build and the commits since it (changelog)
  CLOUDFLARE_ACCOUNT_ID, CLOUDFLARE_AI_TOKEN   Workers AI retells those commits for users; without them (or when it
                      fails) the changelog is the commit subjects
Without the token/id/hash the post is skipped (forks, a repository without the secrets). Never print the secrets.
"""
import argparse
import asyncio
import hashlib
import html
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

CAPTION_LIMIT = 1000        # Telegram allows 1024 visible characters; keep a margin
SUBJECT_LIMIT = 140
HEADER_LIMIT = 300
WORKFLOW = 'build-test-apk.yml'
AI_MODEL = '@cf/meta/llama-3.3-70b-instruct-fp8-fast'   # Workers AI free plan: 10k neurons a day, ~200 a changelog
AI_INPUT_LIMIT = 12000      # characters of commit messages sent to the model
CHANGELOG_ITEMS = 6
SECRETS = ('TELEGRAM_BOT_TOKEN', 'TELEGRAM_API_ID', 'TELEGRAM_API_HASH')
DEFAULTS = {'auto': True, 'chat': None, 'topic': None, 'where': '', 'commits': True, 'sha': True, 'header': '',
            'lang': 'ru'}


def t(lang, ru, en):
    return ru if str(lang).startswith('ru') else en


def redact(text):
    for name in ('TELEGRAM_BOT_TOKEN', 'TELEGRAM_API_HASH', 'GH_TOKEN', 'CLOUDFLARE_AI_TOKEN'):
        secret = os.environ.get(name, '').strip()
        if secret:
            text = text.replace(secret, '***')
    return text


# ---- settings: the bot's pinned message in the owner's private chat --------------------------------------------

def parse_settings(text):
    """Settings from the text of the pinned message (its last JSON line), or None when it is not the bot's one."""
    for line in reversed((text or '').splitlines()):
        if line.startswith('{'):
            try:
                data = json.loads(line)
            except ValueError:
                return None
            if isinstance(data, dict) and data.get('scamera_bot') == 1:
                return {**DEFAULTS, **{k: v for k, v in data.items() if k in DEFAULTS}}
            return None
    return None


def describe_settings(s):
    """Readable summary (HTML), shared by /status and the pinned message."""
    lang = s['lang']
    on, off = t(lang, 'вкл', 'on'), t(lang, 'выкл', 'off')
    where = html.escape(s['where'] or str(s['chat'] or ''), quote=False) or t(
        lang, 'не задано — напиши /here в нужной теме группы', 'not set: write /here in the topic of the group')
    header = html.escape(s['header'], quote=False) or '—'
    return '\n'.join([
        t(lang, 'Автопостинг новых сборок: ', 'Auto-posting new builds: ') + f'<b>{on if s["auto"] else off}</b>',
        t(lang, 'Куда: ', 'Where: ') + where,
        t(lang, 'Подпись: ', 'Caption: ') + ', '.join(
            f'{name} {on if s[key] else off}' for key, name in
            (('commits', t(lang, 'что нового', 'what is new')), ('sha', 'SHA-256'))),
        t(lang, 'Текст сверху: ', 'Header: ') + header])


def render_settings(s):
    """HTML of the pinned settings message: the summary and the JSON the scripts read back."""
    data = {'scamera_bot': 1, **{k: s[k] for k in DEFAULTS}}
    title = t(s['lang'], '⚙️ <b>Настройки бота</b> (закреплено, не открепляй)',
              '⚙️ <b>Bot settings</b> (pinned, keep it pinned)')
    return f'{title}\n{describe_settings(s)}\n\n<code>{html.escape(json.dumps(data, ensure_ascii=False), quote=False)}</code>'


# ---- caption ------------------------------------------------------------------------------------------------------

def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


# ---- changelog: the commits since the previous build, retold for users by Workers AI ------------------------------

def github_api(path):
    token, repo = os.environ.get('GH_TOKEN', '').strip(), os.environ.get('GITHUB_REPOSITORY', '').strip()
    if not token or not repo:
        raise RuntimeError('GH_TOKEN / GITHUB_REPOSITORY not set')
    request = urllib.request.Request(os.environ.get('GITHUB_API_URL', 'https://api.github.com') + f'/repos/{repo}{path}',
                                     headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'SCAMERA-bot',
                                              'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def previous_build(head):
    """(commit, build number or '') of the last successful push build of main before the build of commit <head>."""
    runs = github_api(f'/actions/workflows/{WORKFLOW}/runs?branch=main&event=push&status=success&per_page=30')
    runs = runs['workflow_runs']   # newest first
    same = [i for i, run in enumerate(runs) if run['head_sha'] == head]
    for run in runs[same[-1] + 1:] if same else runs:
        if run['head_sha'] == head:
            continue
        number = ''
        try:
            for artifact in github_api(f'/actions/runs/{run["id"]}/artifacts?per_page=50')['artifacts']:
                m = re.fullmatch(r'SCAMERA-Build-(\d+)', artifact['name'])
                if m:
                    number = m[1]
                    break
        except (OSError, ValueError, KeyError, RuntimeError):
            pass
        return run['head_sha'], number
    return None, ''


def commit_messages(base, head):
    """Full messages of the commits base..head, oldest first, merges left out: GitHub's compare API, else local git."""
    try:
        data = github_api(f'/compare/{base}...{head}')
        return [c['commit']['message'] for c in data['commits'] if len(c.get('parents') or []) < 2]
    except (OSError, ValueError, KeyError, RuntimeError):
        pass
    try:
        out = subprocess.run(['git', 'log', '--no-merges', '--reverse', '--format=%B%x00', f'{base}..{head}'],
                             check=True, capture_output=True, text=True, encoding='utf-8').stdout
    except (OSError, subprocess.CalledProcessError):
        return []
    return [m.strip() for m in out.split('\0') if m.strip()]


def plain_changes(messages):
    """Fallback without AI: commit subjects without task numbers, plan and merge commits."""
    out = []
    for message in messages:
        subject = message.strip().split('\n', 1)[0].strip()
        if not subject or re.match(r'(?i)(plan|merge)\b', subject):
            continue
        subject = re.sub(r'^P\d+[a-z]?(?:\s+v\d+)?(?:\s+fix)?\s*:\s*', '', subject)
        out.append(subject[:1].upper() + subject[1:])
    return out


AI_PROMPT = {
    'ru': '\n'.join([
        'Ты пишешь чейнжлог тестовой сборки Android-камеры SCAMERA для её пользователей в Telegram. Тебе дают '
        'сообщения коммитов между прошлой и новой сборкой (английский технический язык разработчика). Напиши '
        'по-русски, что изменилось для пользователя камеры.',
        'Правила:',
        '- от 1 до 6 пунктов, самое заметное первым; каждый пункт — одно короткое предложение до 100 символов;',
        '- простыми словами: что стало лучше или быстрее, что исправлено, что появилось; без номеров задач (P123), '
        'хэшей, имён файлов, классов, функций, шейдеров и тестов;',
        '- пропусти то, что пользователь не заметит: тесты, CI, планы, документацию, рефакторинг, отладку;',
        '- связанные изменения объединяй в один пункт; ничего не выдумывай сверх сообщений;',
        '- названия телефонов, режимов и форматов (X200 Ultra, SHDR, ISZ, DCG, HEIC, Ultra HDR) оставляй как есть;',
        '- если заметных пользователю изменений нет, ответь одной строкой: • Внутренние улучшения и исправления',
        'Формат ответа: только пункты, каждый с новой строки и с «• » в начале, без заголовка и пояснений.']),
    'en': '\n'.join([
        'You write the changelog of a test build of the SCAMERA Android camera app for its users on Telegram. You get '
        'the commit messages between the previous and the new build (developer jargon). Write in plain English what '
        'changed for a user of the camera.',
        'Rules:',
        '- 1 to 6 items, the most noticeable first; each item one short sentence under 100 characters;',
        '- plain words: what got better or faster, what was fixed, what is new; no task numbers (P123), hashes, file, '
        'class, function, shader or test names;',
        '- skip what a user would not notice: tests, CI, plans, docs, refactoring, debugging tools;',
        '- merge related changes into one item; invent nothing beyond the messages;',
        '- keep phone, mode and format names (X200 Ultra, SHDR, ISZ, DCG, HEIC, Ultra HDR) as they are;',
        '- if nothing a user would notice changed, answer one line: • Internal improvements and fixes',
        'Answer format: only the items, one per line, each starting with "• ", no title or remarks.'])}


def ai_changes(messages, lang):
    """Up to CHANGELOG_ITEMS plain-language items from Cloudflare Workers AI, or None (no secrets, an error)."""
    account = os.environ.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    token = os.environ.get('CLOUDFLARE_AI_TOKEN', '').strip()
    if not (account and token and messages):
        return None
    commits = []
    for message in messages:
        subject, _, body = message.strip().partition('\n')
        commits.append((subject.strip(), re.sub(r'(?im)^co-authored-by:.*$', '', body).strip()))
    # Every subject, and as much of each body as fits ('- ', '\n' and the '\n\n' between entries cost 5 characters)
    budget = max(0, AI_INPUT_LIMIT - sum(len(s) + 5 for s, _ in commits)) // len(commits)
    text = '\n\n'.join(f'- {s}\n{b[:min(600, budget)]}'.rstrip() for s, b in commits)[:AI_INPUT_LIMIT]
    lang = 'ru' if str(lang).startswith('ru') else 'en'
    request = urllib.request.Request(
        f'https://api.cloudflare.com/client/v4/accounts/{account}/ai/run/{os.environ.get("CHANGELOG_MODEL") or AI_MODEL}',
        data=json.dumps({'messages': [{'role': 'system', 'content': AI_PROMPT[lang]},
                                      {'role': 'user', 'content': t(lang, 'Коммиты, старые сверху:\n\n',
                                                                    'Commits, oldest first:\n\n') + text}],
                         'max_tokens': 500, 'temperature': 0.2}).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            answer = (json.load(response).get('result') or {}).get('response') or ''
    except (OSError, ValueError, AttributeError) as e:
        print(f'::warning::Changelog AI failed: {type(e).__name__}; commit subjects instead')
        return None
    items = []
    for line in str(answer).splitlines():
        m = re.match(r'\s*(?:[•\-*–]|\d+[.)])\s+(.*\S)', line)
        if m and (item := re.sub(r'[*_`]+', '', m[1]).strip()):
            items.append(item)
    return items[:CHANGELOG_ITEMS] or None


def make_changes(args, lang):
    """(changelog items, previous build number or '') for the build of --to-rev."""
    head = args.to_rev
    if not re.fullmatch(r'[0-9a-f]{40}', head):
        try:
            head = subprocess.run(['git', 'rev-parse', head], check=True, capture_output=True, text=True).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            head = ''
    base, since, messages = None, '', []
    if head:
        try:
            base, since = previous_build(head)
        except (OSError, ValueError, KeyError, RuntimeError) as e:
            print(f'::warning::Previous build not found: {type(e).__name__}')
        if not base and args.from_rev and set(args.from_rev) != {'0'}:
            base = args.from_rev   # the push's own range (github.event.before)
        if base:
            messages = commit_messages(base, head)
    if not messages:
        messages, since = list(args.subject), ''
    return ai_changes(messages, lang) or plain_changes(messages), since


def visible_length(text):
    plain = html.unescape(re.sub(r'<[^>]+>', '', text))
    return len(plain.encode('utf-16-le')) // 2   # Telegram counts UTF-16 code units


def build_title(apk):
    m = re.match(r'SCAMERA-(?P<ver>[^-]+)-(?P<code>\d+)', apk.name)
    return f'SCAMERA {m["ver"]} · {m["code"]}' if m else apk.stem


def build_caption(apk, digest, changes, settings=None, since=''):
    """Header, title, what is new since the previous build, SHA-256; no links to GitHub (owner's wish)."""
    s = settings or DEFAULTS
    lang = s['lang']
    head = f'<b>{html.escape(build_title(apk))}</b>'
    if s['header']:
        head = html.escape(s['header'][:HEADER_LIMIT], quote=False) + '\n\n' + head
    tail = [f'SHA-256 <code>{digest}</code>'] if s['sha'] else []
    changes = changes if s['commits'] else []
    title = [f'<i>{t(lang, f"Что нового после {since}:", f"New since {since}:")}</i>' if since
             else f'<i>{t(lang, "Что нового:", "What is new:")}</i>'] if changes else []

    def join(lines):
        return '\n'.join([head, *([''] + title + lines if lines else []), *([''] + tail if tail else [])])

    def more(n):
        return t(lang, f'… и ещё {n}', f'… +{n} more')

    lines = []
    for i, change in enumerate(changes):
        if len(change) > SUBJECT_LIMIT:
            change = change[:SUBJECT_LIMIT - 1].rstrip() + '…'
        line = '• ' + html.escape(change, quote=False)
        rest = len(changes) - i - 1
        if visible_length(join(lines + [line] + ([more(rest)] if rest else []))) > CAPTION_LIMIT:
            lines.append(more(len(changes) - i))
            break
        lines.append(line)
    caption = join(lines)
    if visible_length(caption) > CAPTION_LIMIT:   # only an absurd header gets here
        caption = join([])
    return caption


def parse_target(chat, topic=''):
    """(chat, topic id or None) from @username, a -100... id, or a chat/topic link: t.me/c/<id>/<topic>[/<message>]
    for a private chat, t.me/<username>/<topic>[/<message>] for a public one."""
    chat, _, query = chat.strip().partition('?')
    link_topic = None
    q = re.search(r'(?:^|&)(?:thread|topic)=(\d+)', query)   # some clients link a topic message as ...?thread=<topic>
    if q:
        chat = re.sub(r'/\d+/?$', '', chat)
    m = re.fullmatch(r'(?:https?://)?t\.me/c/(\d+)(?:/(\d+))?(?:/\d+)?/?', chat)
    if m:
        target, link_topic = int('-100' + m[1]), m[2]
    elif re.fullmatch(r'-?\d+', chat):
        target = int(chat)
    else:
        m = re.fullmatch(r'(?:https?://)?t\.me/(\w{4,})(?:/(\d+))?(?:/\d+)?/?', chat)
        target, link_topic = (m[1], m[2]) if m else (chat, None)
    topic = (topic or '').strip() or (q[1] if q else link_topic)
    topic = int(topic) if topic else None
    return target, (None if topic == 1 else topic)   # General (topic 1) takes plain messages


# ---- posting ------------------------------------------------------------------------------------------------------

async def read_settings(client, owner):
    from telethon.tl.functions.users import GetFullUserRequest
    full = await client(GetFullUserRequest(owner))
    if not full.full_user.pinned_msg_id:
        return None
    message = await client.get_messages(owner, ids=full.full_user.pinned_msg_id)
    return parse_settings(message.message) if message and message.out else None


async def notify(client, owner, text):
    if owner is None:
        return
    try:
        await client.send_message(owner, text, parse_mode='html', link_preview=False)
    except Exception as e:   # the owner never opened the bot, blocked it, ...
        print(f'::warning::Could not message the owner: {type(e).__name__}')


async def publish(args, digest):
    """Message id of the post, or None when skipped."""
    from telethon import TelegramClient
    from telethon.sessions import StringSession

    client = TelegramClient(StringSession(), int(os.environ['TELEGRAM_API_ID']), os.environ['TELEGRAM_API_HASH'])
    await client.start(bot_token=os.environ['TELEGRAM_BOT_TOKEN'])
    try:
        settings, owner = dict(DEFAULTS), None
        owner_ref = os.environ.get('TELEGRAM_OWNER', '').strip().lstrip('@')
        if owner_ref:
            try:
                owner = await client.get_input_entity(int(owner_ref) if owner_ref.isdigit() else owner_ref)
                settings = await read_settings(client, owner) or settings
            except Exception as e:
                print(f'::warning::Bot settings not read from the owner chat: {type(e).__name__}')
        lang, title = settings['lang'], html.escape(build_title(args.apk))
        if not settings['auto'] and not args.force:
            print('::notice::Telegram post skipped: auto-posting is off (/on in the bot)')
            return None
        if settings['chat']:
            target, topic, where = settings['chat'], settings['topic'], settings['where'] or str(settings['chat'])
        elif os.environ.get('TELEGRAM_CHAT', '').strip():
            target, topic = parse_target(os.environ['TELEGRAM_CHAT'], os.environ.get('TELEGRAM_TOPIC', ''))
            where = str(target) + (f' / {topic}' if topic else '')
        else:
            await notify(client, owner, t(lang, f'Сборка {title} готова, но я не знаю, куда её выложить: '
                                                'напиши /here в нужной теме группы.',
                                          f'Build {title} is ready, but there is nowhere to post it: '
                                          'write /here in the topic of the group.'))
            print('::notice::Telegram post skipped: no target (/here in the bot, or the TELEGRAM_CHAT secret)')
            return None
        changes, since = make_changes(args, lang) if settings['commits'] else ([], '')
        caption = build_caption(args.apk, digest, changes, settings, since)
        try:
            # A reply to a topic's first message (its id is the topic id) is posted inside that topic.
            message = await client.send_file(target, str(args.apk), caption=caption, parse_mode='html',
                                             force_document=True, reply_to=topic)
        except Exception as e:
            if args.notify in ('fail', 'all'):
                detail = html.escape(redact(f'{type(e).__name__}: {e}'), quote=False)
                await notify(client, owner, t(lang, f'❌ Не удалось выложить {title} в «{html.escape(where)}»: {detail}',
                                              f'❌ Could not post {title} to “{html.escape(where)}”: {detail}'))
            raise
        try:   # the newest APK on top of its topic; silent, the post itself has notified
            await client.pin_message(target, message, notify=False)
        except Exception as e:
            print(f'::warning::Post not pinned: {type(e).__name__}')
            if args.notify in ('fail', 'all'):
                detail = html.escape(redact(f'{type(e).__name__}: {e}'), quote=False)
                await notify(client, owner, t(lang, f'⚠️ {title} выложена, но не закреплена: {detail}. '
                                                    'Дай боту право закреплять сообщения.',
                                              f'⚠️ {title} is posted but not pinned: {detail}. '
                                              'Allow the bot to pin messages.'))
        if args.notify == 'all':
            await notify(client, owner, t(lang, f'✅ {title} выложена в «{html.escape(where)}».',
                                          f'✅ {title} posted to “{html.escape(where)}”.'))
        return message.id
    finally:
        await client.log_out()   # the session is throwaway; do not leave a live bot authorization behind


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--apk', required=True, type=Path)
    ap.add_argument('--to-rev', default='HEAD', help='commit of the build (a full sha needs no local git history)')
    ap.add_argument('--from-rev', default='', help='changelog range start when no previous build is found '
                                                   '(github.event.before)')
    ap.add_argument('--subject', action='append', default=[], help='changelog source when no range is known (repeat)')
    ap.add_argument('--force', action='store_true', help='post even when auto-posting is off (/post in the bot)')
    ap.add_argument('--notify', choices=('none', 'fail', 'all'), default='fail', help='message the owner about it')
    ap.add_argument('--dry-run', action='store_true', help='print the caption and do not post')
    ap.add_argument('--settings-json', default='', help='with --dry-run: settings to preview the caption with')
    args = ap.parse_args()

    if not args.apk.is_file() or args.apk.suffix.lower() != '.apk':
        print(f'::error::Not an APK: {args.apk}')
        return 1
    digest = sha256(args.apk)
    if args.dry_run:
        settings = {**DEFAULTS, **(json.loads(args.settings_json) if args.settings_json else {})}
        changes, since = make_changes(args, settings['lang'])
        caption = build_caption(args.apk, digest, changes, settings, since)
        print(caption)
        print(f'[{visible_length(caption)} visible characters]')
        return 0

    missing = [name for name in SECRETS if not os.environ.get(name, '').strip()]
    if missing:
        print(f'::notice::Telegram post skipped: secrets not set ({", ".join(missing)})')
        return 0
    size_mb = args.apk.stat().st_size / (1 << 20)
    try:
        message_id = asyncio.run(publish(args, digest))
    except Exception as e:
        print(f'::error::Telegram post failed: {redact(f"{type(e).__name__}: {e}")}')
        return 1
    if message_id is None:
        return 0
    print(f'Posted {args.apk.name} ({size_mb:.1f} MB) to Telegram, message {message_id}')
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as f:
            f.write(f'\nPosted `{args.apk.name}` to Telegram (message {message_id}).\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
