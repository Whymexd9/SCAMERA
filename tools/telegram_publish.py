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
from pathlib import Path

CAPTION_LIMIT = 1000        # Telegram allows 1024 visible characters; keep a margin
SUBJECT_LIMIT = 140
HEADER_LIMIT = 300
SECRETS = ('TELEGRAM_BOT_TOKEN', 'TELEGRAM_API_ID', 'TELEGRAM_API_HASH')
DEFAULTS = {'auto': True, 'chat': None, 'topic': None, 'where': '', 'commits': True, 'sha': True, 'header': '',
            'lang': 'ru'}


def t(lang, ru, en):
    return ru if str(lang).startswith('ru') else en


def redact(text):
    for name in ('TELEGRAM_BOT_TOKEN', 'TELEGRAM_API_HASH', 'GH_TOKEN'):
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
            (('commits', t(lang, 'коммиты', 'commits')), ('sha', 'SHA-256'))),
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


def commit_subjects(rev_from, rev_to, limit=1000):
    """Subjects of the commits this push added; only the head commit for a new branch, a force push or a manual run."""
    ranges = []
    if rev_from and set(rev_from) != {'0'}:
        ranges.append([f'{rev_from}..{rev_to}'])
    ranges.append(['-1', rev_to])
    for rng in ranges:
        try:
            out = subprocess.run(['git', 'log', '--no-merges', '--format=%s', f'--max-count={limit}', *rng],
                                 check=True, capture_output=True, text=True, encoding='utf-8').stdout
        except (OSError, subprocess.CalledProcessError):
            continue
        subjects = [s.strip() for s in out.splitlines() if s.strip()]
        if subjects:
            return subjects
    return []


def visible_length(text):
    plain = html.unescape(re.sub(r'<[^>]+>', '', text))
    return len(plain.encode('utf-16-le')) // 2   # Telegram counts UTF-16 code units


def build_title(apk):
    m = re.match(r'SCAMERA-(?P<ver>[^-]+)-(?P<code>\d+)', apk.name)
    return f'SCAMERA {m["ver"]} · {m["code"]}' if m else apk.stem


def build_caption(apk, digest, subjects, settings=None):
    """Header, title, commit subjects, SHA-256; no links to GitHub (the owner does not want them in the group)."""
    s = settings or DEFAULTS
    head = f'<b>{html.escape(build_title(apk))}</b>'
    if s['header']:
        head = html.escape(s['header'][:HEADER_LIMIT], quote=False) + '\n\n' + head
    tail = [f'SHA-256 <code>{digest}</code>'] if s['sha'] else []

    def join(lines):
        return '\n'.join([head, *([''] + lines if lines else []), *([''] + tail if tail else [])])

    lines = []
    for i, subject in enumerate(subjects if s['commits'] else []):
        if len(subject) > SUBJECT_LIMIT:
            subject = subject[:SUBJECT_LIMIT - 1].rstrip() + '…'
        line = '• ' + html.escape(subject, quote=False)
        rest = len(subjects) - i - 1
        if visible_length(join(lines + [line] + ([f'… +{rest} more'] if rest else []))) > CAPTION_LIMIT:
            lines.append(f'… +{len(subjects) - i} more')
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


async def publish(args, digest, subjects):
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
        caption = build_caption(args.apk, digest, subjects, settings)
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
    ap.add_argument('--from-rev', default='', help='previous head of the branch (github.event.before)')
    ap.add_argument('--to-rev', default='HEAD')
    ap.add_argument('--subject', action='append', default=[], help='caption commit line instead of git log (repeat)')
    ap.add_argument('--force', action='store_true', help='post even when auto-posting is off (/post in the bot)')
    ap.add_argument('--notify', choices=('none', 'fail', 'all'), default='fail', help='message the owner about it')
    ap.add_argument('--dry-run', action='store_true', help='print the caption and do not post')
    ap.add_argument('--settings-json', default='', help='with --dry-run: settings to preview the caption with')
    args = ap.parse_args()

    if not args.apk.is_file() or args.apk.suffix.lower() != '.apk':
        print(f'::error::Not an APK: {args.apk}')
        return 1
    subjects = args.subject or commit_subjects(args.from_rev, args.to_rev)
    digest = sha256(args.apk)
    if args.dry_run:
        settings = {**DEFAULTS, **(json.loads(args.settings_json) if args.settings_json else {})}
        caption = build_caption(args.apk, digest, subjects, settings)
        print(caption)
        print(f'[{visible_length(caption)} visible characters]')
        return 0

    missing = [name for name in SECRETS if not os.environ.get(name, '').strip()]
    if missing:
        print(f'::notice::Telegram post skipped: secrets not set ({", ".join(missing)})')
        return 0
    size_mb = args.apk.stat().st_size / (1 << 20)
    try:
        message_id = asyncio.run(publish(args, digest, subjects))
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
