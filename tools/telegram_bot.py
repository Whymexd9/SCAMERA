#!/usr/bin/env python3
"""The SCAMERA bot's /post and its polling fallback (docs/telegram-channel.md); workflow telegram-bot.yml.

The bot answers in real time from a Cloudflare Worker (tools/telegram-worker/worker.js, a Telegram webhook). Its /post
starts telegram-bot.yml, which runs this with --post: take the APK out of a build artifact and post it through
tools/telegram_publish.py (the Worker cannot upload 50+ MB). Without --post this answers the owner's commands by
polling (Bot API getUpdates) once and exits: the same commands as the Worker, for when no webhook is set.
The settings live in the bot's pinned message of the owner's chat; tools/telegram_publish.py reads them there.

Environment: TELEGRAM_BOT_TOKEN, TELEGRAM_OWNER (@username or numeric id); for posting also TELEGRAM_API_ID,
TELEGRAM_API_HASH, GH_TOKEN and GITHUB_REPOSITORY (the workflow's token reads the build artifacts).
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from telegram_publish import HEADER_LIMIT, MAX_TARGETS, default_settings, describe_settings, describe_targets, \
    parse_settings, redact, render_settings, sha256, t, target_name

TOOLS = Path(__file__).resolve().parent
WORKFLOW = 'build-test-apk.yml'
ARTIFACT = re.compile(r'SCAMERA-Build-(\d+)')
ZIP_LIMIT = 512 << 20
TOGGLES = ('commits', 'sha')


class TelegramError(Exception):
    def __init__(self, method, description):
        super().__init__(f'{method}: {description}')
        self.description = description


def bot(method, **params):
    params = {k: v for k, v in params.items() if v is not None}
    request = urllib.request.Request(f'https://api.telegram.org/bot{os.environ["TELEGRAM_BOT_TOKEN"].strip()}/{method}',
                                     data=json.dumps(params).encode(), headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = json.load(response)
    except urllib.error.HTTPError as e:   # Telegram explains its errors in a JSON body
        try:
            body = json.load(e)
        except ValueError:
            raise TelegramError(method, f'HTTP {e.code}') from None
    except (OSError, ValueError) as e:    # the URL holds the token: never print the exception text
        raise TelegramError(method, type(e).__name__) from None
    if not body.get('ok'):
        raise TelegramError(method, body.get('description', 'error'))
    return body['result']


def say(chat_id, text, topic=None):
    return bot('sendMessage', chat_id=chat_id, message_thread_id=topic, text=text, parse_mode='HTML',
               link_preview_options={'is_disabled': True})


def take_updates():
    """Everything since the last run, confirmed at once: a command that fails is not repeated every 5 minutes."""
    updates, offset = [], None
    while True:
        batch = bot('getUpdates', offset=offset, timeout=0, limit=100, allowed_updates=['message'])
        updates += batch
        if len(batch) < 100:
            break
        offset = batch[-1]['update_id'] + 1
    if updates:
        bot('getUpdates', offset=updates[-1]['update_id'] + 1, timeout=0, limit=1)
    return updates


def is_owner(user):
    ref = os.environ.get('TELEGRAM_OWNER', '').strip().lstrip('@').lower()
    if not ref or not user or user.get('is_bot'):
        return False
    return ref == str(user['id']) or ref == (user.get('username') or '').lower()


def help_text(lang):
    return t(lang, '\n'.join([
        '<b>Бот сборок SCAMERA</b>',
        'Новые сборки из main приходят в группу сами (только APK).',
        '',
        '/status — текущие настройки',
        '/on, /off — автопостинг новых сборок',
        '/post — выложить последнюю сборку, /post 30830 — конкретную',
        '/here — написать в теме группы: сборки пойдут и туда (до 5 мест)',
        '/where — куда выкладываются сборки, /forget 2 — убрать место №2',
        '/commits on|off — «Что нового» (чейнжлог) в подписи',
        '/sha on|off — SHA-256 в подписи',
        '/header текст — свой текст в начале поста, /header - убрать']), '\n'.join([
        '<b>SCAMERA builds bot</b>',
        'New builds of main are posted to the group automatically (the APK only).',
        '',
        '/status — current settings',
        '/on, /off — auto-posting of new builds',
        '/post — post the latest build, /post 30830 — a given one',
        '/here — write it in a topic of a group: builds go there too (up to 5 places)',
        '/where — where builds go, /forget 2 removes place 2',
        '/commits on|off — the “What is new” changelog in the caption',
        '/sha on|off — SHA-256 in the caption',
        '/header text — your text at the top of the post, /header - removes it']))


def set_commands():
    for lang, code in (('en', None), ('ru', 'ru')):
        private = [('status', t(lang, 'текущие настройки', 'current settings')),
                   ('post', t(lang, 'выложить последнюю сборку', 'post the latest build')),
                   ('on', t(lang, 'включить автопостинг', 'auto-posting on')),
                   ('off', t(lang, 'выключить автопостинг', 'auto-posting off')),
                   ('commits', t(lang, 'чейнжлог в подписи on|off', 'changelog in the caption on|off')),
                   ('sha', t(lang, 'SHA-256 в подписи on|off', 'SHA-256 in the caption on|off')),
                   ('header', t(lang, 'текст в начале поста', 'text at the top of the post')),
                   ('where', t(lang, 'куда выкладываются сборки', 'where builds go')),
                   ('forget', t(lang, 'убрать место: /forget 2', 'remove a place: /forget 2')),
                   ('help', t(lang, 'все команды', 'all commands'))]
        group = [('here', t(lang, 'выкладывать сборки и в эту тему', 'post builds to this topic too'))]
        for scope, commands in (('all_private_chats', private), ('all_group_chats', group)):
            bot('setMyCommands', scope={'type': scope}, language_code=code,
                commands=[{'command': c, 'description': d} for c, d in commands])


class Settings:
    """The pinned settings message in the owner's chat (the chat id of a private chat is the user id)."""

    def __init__(self, owner_id):
        self.chat_id = owner_id
        pinned = bot('getChat', chat_id=owner_id).get('pinned_message') or {}
        found = parse_settings(pinned.get('text')) if (pinned.get('from') or {}).get('is_bot') else None
        self.values = found or default_settings()
        self.message_id = pinned['message_id'] if found else None
        self.dirty = False

    def save(self):
        self.dirty = False
        text = render_settings(self.values)
        if self.message_id:
            try:
                bot('editMessageText', chat_id=self.chat_id, message_id=self.message_id, text=text, parse_mode='HTML',
                    link_preview_options={'is_disabled': True})
                return
            except TelegramError as e:
                if 'not modified' in e.description:
                    return
        message = say(self.chat_id, text)
        bot('pinChatMessage', chat_id=self.chat_id, message_id=message['message_id'], disable_notification=True)
        self.message_id = message['message_id']


class Run:
    def __init__(self):
        self._me = None
        self._settings = None
        self.post = None   # (build number or '', owner chat id) of the last /post

    @property
    def username(self):
        if self._me is None:
            self._me = bot('getMe')
        return self._me['username']

    def settings(self, user):
        if self._settings is None:
            self._settings = Settings(user['id'])
        lang = 'ru' if (user.get('language_code') or '').startswith('ru') else 'en'
        if self._settings.values['lang'] != lang:
            self._settings.values['lang'] = lang
            self._settings.dirty = True
        return self._settings

    @property
    def lang(self):
        return self._settings.values['lang'] if self._settings else 'ru'

    def flush(self):
        if self._settings is not None and self._settings.dirty:
            self._settings.save()


def parse_command(msg, username):
    m = re.match(r'/(\w+)(?:@(\w+))?(?:\s+(.*))?$', (msg.get('text') or '').strip(), re.S)
    if not m or (m[2] and m[2].lower() != username.lower()):
        return None, ''
    return m[1].lower(), (m[3] or '').strip()


def switch(value, current):
    v = value.lower()
    if v in ('on', 'вкл', '1', 'да', 'yes'):
        return True
    if v in ('off', 'выкл', '0', 'нет', 'no'):
        return False
    return None if v else not current


def handle_private(msg, cmd, arg, run):
    user, chat_id = msg['from'], msg['chat']['id']
    s = run.settings(user)
    v, lang = s.values, s.values['lang']
    if cmd in ('start', 'help'):
        if cmd == 'start':
            set_commands()
            if s.message_id is None:
                s.save()   # the pinned settings message appears right away
        say(chat_id, help_text(lang) + '\n\n' + describe_settings(v))
    elif cmd == 'status':
        say(chat_id, describe_settings(v) + '\n\n' + t(lang, f'Твой ID: <code>{user["id"]}</code>',
                                                      f'Your id: <code>{user["id"]}</code>'))
    elif cmd in ('on', 'off'):
        v['auto'] = cmd == 'on'
        s.save()
        say(chat_id, t(lang, 'Автопостинг ' + ('включён.' if v['auto'] else 'выключен: сборки не выкладываются, '
                                                                          '/post работает.'),
                       'Auto-posting ' + ('on.' if v['auto'] else 'off: builds are not posted, /post still works.')))
    elif cmd in TOGGLES:
        value = switch(arg, v[cmd])
        if value is None:
            say(chat_id, t(lang, f'Напиши /{cmd} on или /{cmd} off.', f'Write /{cmd} on or /{cmd} off.'))
            return
        v[cmd] = value
        s.save()
        say(chat_id, describe_settings(v))
    elif cmd == 'header':
        if not arg:
            say(chat_id, t(lang, 'Текст сверху: ', 'Header: ') + (v['header'] and f'<i>{escape(v["header"])}</i>' or '—')
                + '\n' + t(lang, '/header текст — задать, /header - убрать', '/header text sets it, /header - removes it'))
            return
        v['header'] = '' if arg == '-' else arg[:HEADER_LIMIT]
        s.save()
        say(chat_id, describe_settings(v))
    elif cmd == 'here':
        say(chat_id, t(lang, 'Напиши /here в нужной теме группы (бот должен быть в группе с правом писать).',
                       'Write /here in the topic of the group (the bot must be a member allowed to post).'))
    elif cmd == 'where':
        say(chat_id, describe_targets(v) + ('\n\n' + t(lang, 'Убрать место: /forget номер', 'Remove one: /forget number')
                                            if v['targets'] else ''))
    elif cmd == 'forget':
        if not arg.isdigit() or not 1 <= int(arg) <= len(v['targets']):
            say(chat_id, describe_targets(v) + '\n\n' + t(lang, 'Напиши /forget и номер места из списка.',
                                                           'Write /forget and the number of a place in the list.'))
            return
        gone = v['targets'].pop(int(arg) - 1)
        s.save()
        say(chat_id, t(lang, f'Убрал «{escape(target_name(gone))}».', f'Removed “{escape(target_name(gone))}”.')
            + '\n' + describe_targets(v))
    elif cmd == 'post':
        if arg and not arg.isdigit():
            say(chat_id, t(lang, 'Номер сборки — число: /post 30830.', 'The build number is a number: /post 30830.'))
            return
        run.post = (arg, chat_id)
    else:
        say(chat_id, t(lang, 'Не знаю такой команды. /help — список.', 'Unknown command. /help lists them.'))


def handle_here(msg, run):
    """/here in a group or one of its topics: posts go there too from now on (up to MAX_TARGETS places)."""
    chat, user = msg['chat'], msg['from']
    topic = msg.get('message_thread_id') if msg.get('is_topic_message') else None
    topic_name = ((msg.get('reply_to_message') or {}).get('forum_topic_created') or {}).get('name')
    where = chat.get('title') or str(chat['id'])
    if topic:
        where += ' › ' + (topic_name or f'#{topic}')
    try:
        s = run.settings(user)
    except TelegramError:   # the settings live in the owner's chat: the owner has not opened the bot yet
        s = None
    lang = s.values['lang'] if s else user.get('language_code') or ''
    open_first = t(lang, f'Сначала открой @{run.username} в личке и нажми «Начать», потом повтори /here.',
                   f'Open @{run.username} in a private chat and press Start first, then repeat /here.')
    if s is None:
        say(chat['id'], open_first, topic)
        return
    targets = s.values['targets']
    known = next((i for i, x in enumerate(targets) if x['chat'] == chat['id'] and x.get('topic') == topic), None)
    if known is None and len(targets) >= MAX_TARGETS:
        notify_owner(user['id'], t(lang, f'Уже {MAX_TARGETS} мест, больше нельзя. Убери лишнее: /forget номер.\n',
                                   f'{MAX_TARGETS} places already, no more. Remove one: /forget number.\n')
                     + describe_targets(s.values))
        return
    try:
        say(chat['id'], t(lang, 'Сборки SCAMERA будут приходить сюда.', 'SCAMERA builds will be posted here.'), topic)
    except TelegramError as e:
        notify_owner(user['id'], t(lang, f'Не могу писать в «{escape(where)}»: {escape(e.description)}. '
                                         'Дай боту право отправлять сообщения и повтори /here.',
                                   f'Cannot post to “{escape(where)}”: {escape(e.description)}. '
                                   'Allow the bot to send messages and repeat /here.'))
        return
    entry = {'chat': chat['id'], 'topic': topic, 'where': where}
    if known is None:
        targets.append(entry)
    else:
        targets[known] = entry   # the same place again: its name may have changed
    try:
        s.save()
    except TelegramError:   # the owner blocked the bot or never pressed Start
        say(chat['id'], open_first, topic)
        return
    notify_owner(user['id'], (t(lang, f'✅ Добавил «{escape(where)}».', f'✅ Added “{escape(where)}”.') if known is None
                              else t(lang, f'«{escape(where)}» уже в списке.', f'“{escape(where)}” is already there.'))
                 + '\n' + describe_targets(s.values))


def notify_owner(chat_id, text):
    try:
        say(chat_id, text)
    except TelegramError as e:
        print(f'::warning::Could not message the owner: {redact(str(e))}')


def escape(text):
    return str(text).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


# ---- /post: the APK of a CI build -----------------------------------------------------------------------------------

def github(url, save_to=None):
    if not url.startswith('https://'):
        url = os.environ.get('GITHUB_API_URL', 'https://api.github.com') + url
    request = urllib.request.Request(url, headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'SCAMERA-bot'})
    # Unredirected: the artifact download redirects to signed storage, which must never receive the token.
    request.add_unredirected_header('Authorization', 'Bearer ' + os.environ['GH_TOKEN'])
    with urllib.request.urlopen(request, timeout=300) as response:
        if save_to is None:
            return json.load(response)
        with open(save_to, 'wb') as out:
            copied = 0
            while block := response.read(1 << 20):
                copied += len(block)
                if copied > ZIP_LIMIT:
                    raise RuntimeError('artifact larger than expected')
                out.write(block)


def find_build(number):
    """(artifact, workflow run) of build <number>, or of the latest successful push build of main."""
    repo = os.environ['GITHUB_REPOSITORY']
    if number:
        found = [a for a in github(f'/repos/{repo}/actions/artifacts?name=SCAMERA-Build-{number}&per_page=20')['artifacts']
                 if not a['expired']]
        if not found:
            return None, None
        artifact = max(found, key=lambda a: a['id'])
        return artifact, github(f'/repos/{repo}/actions/runs/{artifact["workflow_run"]["id"]}')
    runs = github(f'/repos/{repo}/actions/workflows/{WORKFLOW}/runs?branch=main&event=push&status=success&per_page=10')
    for wf_run in runs['workflow_runs']:
        for artifact in github(f'/repos/{repo}/actions/runs/{wf_run["id"]}/artifacts?per_page=50')['artifacts']:
            if ARTIFACT.fullmatch(artifact['name']) and not artifact['expired']:
                return artifact, wf_run
    return None, None


def extract_apk(zip_path, folder):
    """Only the APK leaves the artifact; SHA256SUMS.txt is read to verify it and nothing else is touched."""
    with zipfile.ZipFile(zip_path) as archive:
        names = archive.namelist()
        apks = [n for n in names if n.lower().endswith('.apk') and '/' not in n]
        if len(apks) != 1:
            raise RuntimeError(f'expected one APK in the artifact, found {len(apks)}')
        sums = {}
        if 'SHA256SUMS.txt' in names:
            for line in archive.read('SHA256SUMS.txt').decode('utf-8', 'replace').splitlines():
                parts = line.split()
                if len(parts) == 2:
                    sums[parts[1].lstrip('*')] = parts[0].lower()
        apk = folder / apks[0]
        with archive.open(apks[0]) as src, open(apk, 'wb') as dst:
            shutil.copyfileobj(src, dst, 1 << 20)
    if apks[0] in sums and sha256(apk) != sums[apks[0]]:
        raise RuntimeError('the APK does not match SHA256SUMS.txt')
    return apk


def publisher_python():
    """Python with Telethon for tools/telegram_publish.py (installed only when /post needs it)."""
    try:
        import telethon  # noqa: F401
        return sys.executable
    except ImportError:
        pass
    venv = Path(os.environ.get('RUNNER_TEMP') or tempfile.gettempdir()) / 'telegram-venv'
    python = venv / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    if not python.exists():
        subprocess.run([sys.executable, '-m', 'venv', str(venv)], check=True)
    subprocess.run([str(python), '-m', 'pip', 'install', '-q', '-r', str(TOOLS / 'telegram-requirements.txt')],
                   check=True)
    return str(python)


def post_build(number, chat_id, lang, announce=True):
    """0 when posted; 1 when not (the owner is told why)."""
    if not (os.environ.get('TELEGRAM_API_ID', '').strip() and os.environ.get('TELEGRAM_API_HASH', '').strip()):
        say(chat_id, t(lang, 'Для /post нужны секреты TELEGRAM_API_ID и TELEGRAM_API_HASH.',
                       '/post needs the TELEGRAM_API_ID and TELEGRAM_API_HASH secrets.'))
        return 1
    try:
        artifact, wf_run = find_build(number)
    except (OSError, ValueError, KeyError) as e:
        say(chat_id, t(lang, f'GitHub не ответил: {type(e).__name__}. Повтори позже.',
                       f'GitHub did not answer: {type(e).__name__}. Try again later.'))
        return 1
    if artifact is None:
        say(chat_id, t(lang, f'Сборки {number} нет: номер неверный или её артефакт уже удалён (хранится 3 дня).'
                       if number else 'Готовых сборок main не нашёл.',
                       f'No build {number}: a wrong number, or its artifact expired (kept 3 days).'
                       if number else 'No finished build of main found.'))
        return 1
    build = ARTIFACT.fullmatch(artifact['name'])[1]
    if announce:   # the Worker has already said it is posting
        say(chat_id, t(lang, f'⏳ Выкладываю сборку {build}…', f'⏳ Posting build {build}…'))
    with tempfile.TemporaryDirectory() as folder:
        try:
            github(artifact['archive_download_url'], Path(folder) / 'build.zip')
            apk = extract_apk(Path(folder) / 'build.zip', Path(folder))
        except (OSError, RuntimeError, zipfile.BadZipFile) as e:
            say(chat_id, t(lang, f'❌ Не скачал сборку {build}: {escape(e)}', f'❌ Could not fetch build {build}: {escape(e)}'))
            return 1
        subject = ((wf_run.get('head_commit') or {}).get('message') or '').strip().splitlines()[:1]
        command = [publisher_python(), str(TOOLS / 'telegram_publish.py'), '--apk', str(apk), '--force',
                   '--notify', 'all', '--to-rev', wf_run['head_sha']]
        for line in subject:
            command += ['--subject', line]
        if subprocess.run(command).returncode:   # telegram_publish has told the owner why, when it got that far
            print('::warning::/post failed')
            return 1
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--post', metavar='BUILD', help='post build BUILD ("" = the latest main build) and exit')
    ap.add_argument('--chat', default='', help="with --post: the owner's chat id for the replies")
    ap.add_argument('--lang', default='ru', help='with --post: reply language')
    args = ap.parse_args()
    if not os.environ.get('TELEGRAM_BOT_TOKEN', '').strip():
        print('::notice::TELEGRAM_BOT_TOKEN is not set; the bot is off')
        return 0
    if args.post is not None:   # workflow inputs: never trust their shape
        number, chat = args.post.strip(), args.chat.strip()
        if not re.fullmatch(r'\d*', number) or not re.fullmatch(r'\d+', chat):
            print('::error::--post takes a build number (or "") and --chat a numeric chat id')
            return 1
        try:
            return post_build(number, int(chat), 'en' if args.lang == 'en' else 'ru', announce=False)
        except TelegramError as e:
            print(f'::error::{redact(str(e))}')
            return 1
    try:
        updates = take_updates()
    except TelegramError as e:   # Telegram unreachable: the next run picks the messages up
        print(f'::warning::{redact(str(e))}')
        return 0
    run = Run()
    for update in updates:
        msg = update.get('message') or {}
        user, chat = msg.get('from'), msg.get('chat') or {}
        try:
            if chat.get('type') == 'private':
                if not is_owner(user):
                    say(chat['id'], t(user.get('language_code', '') if user else '',
                                      'Это закрытый бот сборок SCAMERA.', 'This is the private SCAMERA builds bot.')
                        + ('' if os.environ.get('TELEGRAM_OWNER', '').strip() else
                           f'\nTELEGRAM_OWNER is not set; your id: <code>{user["id"] if user else "?"}</code>'))
                    continue
                cmd, arg = parse_command(msg, run.username)
                handle_private(msg, cmd, arg, run)
            elif chat.get('type') in ('group', 'supergroup') and is_owner(user):
                cmd, _ = parse_command(msg, run.username)
                if cmd == 'here':
                    handle_here(msg, run)
        except TelegramError as e:
            print(f'::warning::{redact(str(e))}')
    try:
        run.flush()
    except TelegramError as e:
        print(f'::warning::{redact(str(e))}')
    if run.post is not None:
        number, chat_id = run.post
        post_build(number, chat_id, run.lang)
    print(f'{len(updates)} update(s) handled')
    return 0


if __name__ == '__main__':
    sys.exit(main())
