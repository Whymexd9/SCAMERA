// SCAMERA builds bot on Cloudflare Workers: the Telegram webhook (docs/telegram-channel.md).
//
// Answers the owner's private commands at once. The settings live in the bot's pinned message of the owner's chat,
// in the format tools/telegram_publish.py reads when CI posts a build (keep both in step with
// tools/telegram_publish.py: DEFAULTS, describe_settings, render_settings). /post starts the GitHub Actions workflow
// telegram-bot.yml, which takes the APK out of the build artifact and posts it: a bot cannot upload 50+ MB through
// the Bot API, and the Worker should not download the artifact.
//
// Worker secrets (Settings -> Variables and Secrets): TELEGRAM_BOT_TOKEN, TELEGRAM_OWNER (@username or numeric id),
// WEBHOOK_SECRET (letters, digits, _ and -), GITHUB_TOKEN (fine-grained token: this repository only, Actions read and
// write). Optional plain variable GITHUB_REPO (default Whymexd9/SCAMERA).
// Once after deploying: open https://<worker>/setup?key=<WEBHOOK_SECRET> to register the webhook and the commands.

const DEFAULTS = {auto: true, chat: null, topic: null, where: '', commits: true, sha: true, header: '', lang: 'ru'};
const HEADER_LIMIT = 300;
const TOGGLES = ['commits', 'sha'];
const WORKFLOW = 'telegram-bot.yml';

class TelegramError extends Error {
  constructor(method, description) {
    super(`${method}: ${description}`);
    this.description = description;
  }
}

const t = (lang, ru, en) => (String(lang).startsWith('ru') ? ru : en);
const esc = (text) => String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

async function bot(env, method, params = {}) {
  const body = Object.fromEntries(Object.entries(params).filter(([, v]) => v !== null && v !== undefined));
  let data;
  try {
    const response = await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/${method}`, {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    data = await response.json();
  } catch (e) {   // never pass the error text on: the URL holds the token
    throw new TelegramError(method, (e && e.name) || 'network error');
  }
  if (!data.ok) throw new TelegramError(method, data.description || 'error');
  return data.result;
}

const say = (env, chatId, text, topic = null) => bot(env, 'sendMessage', {chat_id: chatId, message_thread_id: topic,
  text, parse_mode: 'HTML', link_preview_options: {is_disabled: true}});

let me = null;   // getMe, cached while the isolate lives
async function botUsername(env) {
  if (!me) me = await bot(env, 'getMe');
  return me.username;
}

// ---- settings: the pinned message in the owner's chat (a private chat's id is the user id) -------------------------

function parseSettings(text) {
  const lines = String(text || '').split('\n').reverse();
  for (const line of lines) {
    if (!line.startsWith('{')) continue;
    let data;
    try {
      data = JSON.parse(line);
    } catch (e) {
      return null;
    }
    if (!data || typeof data !== 'object' || data.scamera_bot !== 1) return null;
    const out = {...DEFAULTS};
    for (const key of Object.keys(DEFAULTS)) if (key in data) out[key] = data[key];
    return out;
  }
  return null;
}

function describeSettings(s) {
  const lang = s.lang;
  const on = t(lang, 'вкл', 'on');
  const off = t(lang, 'выкл', 'off');
  const where = esc(s.where || (s.chat ? String(s.chat) : '')) ||
    t(lang, 'не задано — напиши /here в нужной теме группы', 'not set: write /here in the topic of the group');
  const parts = [['commits', t(lang, 'что нового', 'what is new')], ['sha', 'SHA-256']];
  return [
    t(lang, 'Автопостинг новых сборок: ', 'Auto-posting new builds: ') + `<b>${s.auto ? on : off}</b>`,
    t(lang, 'Куда: ', 'Where: ') + where,
    t(lang, 'Подпись: ', 'Caption: ') + parts.map(([key, name]) => `${name} ${s[key] ? on : off}`).join(', '),
    t(lang, 'Текст сверху: ', 'Header: ') + (esc(s.header) || '—'),
  ].join('\n');
}

function renderSettings(s) {
  const data = {scamera_bot: 1};
  for (const key of Object.keys(DEFAULTS)) data[key] = s[key];
  const title = t(s.lang, '⚙️ <b>Настройки бота</b> (закреплено, не открепляй)',
    '⚙️ <b>Bot settings</b> (pinned, keep it pinned)');
  return `${title}\n${describeSettings(s)}\n\n<code>${esc(JSON.stringify(data))}</code>`;
}

async function loadSettings(env, user) {
  const chat = await bot(env, 'getChat', {chat_id: user.id});
  const pinned = chat.pinned_message || {};
  const found = (pinned.from || {}).is_bot ? parseSettings(pinned.text) : null;
  const s = {chatId: user.id, values: found || {...DEFAULTS}, messageId: found ? pinned.message_id : null, dirty: false};
  const lang = String(user.language_code || '').startsWith('ru') ? 'ru' : 'en';
  if (s.values.lang !== lang) {
    s.values.lang = lang;
    s.dirty = true;
  }
  return s;
}

async function saveSettings(env, s) {
  s.dirty = false;
  const text = renderSettings(s.values);
  if (s.messageId) {
    try {
      await bot(env, 'editMessageText', {chat_id: s.chatId, message_id: s.messageId, text, parse_mode: 'HTML',
        link_preview_options: {is_disabled: true}});
      return;
    } catch (e) {
      if (!(e instanceof TelegramError)) throw e;
      if (e.description.includes('not modified')) return;
    }
  }
  const message = await say(env, s.chatId, text);
  await bot(env, 'pinChatMessage', {chat_id: s.chatId, message_id: message.message_id, disable_notification: true});
  s.messageId = message.message_id;
}

// ---- commands ---------------------------------------------------------------------------------------------------------

function isOwner(env, user) {
  const ref = String(env.TELEGRAM_OWNER || '').trim().replace(/^@/, '').toLowerCase();
  if (!ref || !user || user.is_bot) return false;
  return ref === String(user.id) || ref === String(user.username || '').toLowerCase();
}

async function parseCommand(env, msg) {
  const m = /^\/(\w+)(?:@(\w+))?(?:\s+([\s\S]*))?$/.exec(String(msg.text || '').trim());
  if (!m) return [null, ''];
  if (m[2] && m[2].toLowerCase() !== (await botUsername(env)).toLowerCase()) return [null, ''];
  return [m[1].toLowerCase(), (m[3] || '').trim()];
}

function helpText(lang) {
  return t(lang, [
    '<b>Бот сборок SCAMERA</b>',
    'Новые сборки из main приходят в группу сами (только APK).',
    '',
    '/status — текущие настройки',
    '/on, /off — автопостинг новых сборок',
    '/post — выложить последнюю сборку, /post 30830 — конкретную',
    '/here — написать в нужной теме группы: сборки пойдут туда',
    '/commits on|off — «Что нового» (чейнжлог) в подписи',
    '/sha on|off — SHA-256 в подписи',
    '/header текст — свой текст в начале поста, /header - убрать',
  ].join('\n'), [
    '<b>SCAMERA builds bot</b>',
    'New builds of main are posted to the group automatically (the APK only).',
    '',
    '/status — current settings',
    '/on, /off — auto-posting of new builds',
    '/post — post the latest build, /post 30830 — a given one',
    '/here — write it in the topic of the group: builds go there',
    '/commits on|off — the “What is new” changelog in the caption',
    '/sha on|off — SHA-256 in the caption',
    '/header text — your text at the top of the post, /header - removes it',
  ].join('\n'));
}

async function setCommands(env) {
  for (const [lang, code] of [['en', null], ['ru', 'ru']]) {
    const privateCommands = [
      ['status', t(lang, 'текущие настройки', 'current settings')],
      ['post', t(lang, 'выложить последнюю сборку', 'post the latest build')],
      ['on', t(lang, 'включить автопостинг', 'auto-posting on')],
      ['off', t(lang, 'выключить автопостинг', 'auto-posting off')],
      ['commits', t(lang, 'чейнжлог в подписи on|off', 'changelog in the caption on|off')],
      ['sha', t(lang, 'SHA-256 в подписи on|off', 'SHA-256 in the caption on|off')],
      ['header', t(lang, 'текст в начале поста', 'text at the top of the post')],
      ['help', t(lang, 'все команды', 'all commands')]];
    const groupCommands = [['here', t(lang, 'выкладывать сборки в эту тему', 'post builds to this topic')]];
    for (const [scope, commands] of [['all_private_chats', privateCommands], ['all_group_chats', groupCommands]]) {
      await bot(env, 'setMyCommands', {scope: {type: scope}, language_code: code,
        commands: commands.map(([command, description]) => ({command, description}))});
    }
  }
}

function switchValue(value, current) {
  const v = value.toLowerCase();
  if (['on', 'вкл', '1', 'да', 'yes'].includes(v)) return true;
  if (['off', 'выкл', '0', 'нет', 'no'].includes(v)) return false;
  return v ? null : !current;
}

async function startPost(env, chatId, number, lang) {
  if (!env.GITHUB_TOKEN) {
    await say(env, chatId, t(lang, 'Для /post нужен секрет GITHUB_TOKEN у воркера.', '/post needs the GITHUB_TOKEN secret of the Worker.'));
    return;
  }
  const repo = env.GITHUB_REPO || 'Whymexd9/SCAMERA';
  let status;
  try {
    const response = await fetch(`https://api.github.com/repos/${repo}/actions/workflows/${WORKFLOW}/dispatches`, {
      method: 'POST',
      headers: {'Authorization': `Bearer ${env.GITHUB_TOKEN}`, 'Accept': 'application/vnd.github+json',
        'User-Agent': 'SCAMERA-bot', 'Content-Type': 'application/json'},
      body: JSON.stringify({ref: 'main', inputs: {build: number, chat: String(chatId), lang}})});
    status = response.status;
  } catch (e) {
    status = (e && e.name) || 'network error';
  }
  if (status === 204) {
    await say(env, chatId, t(lang, `⏳ Выкладываю ${number ? `сборку ${number}` : 'последнюю сборку main'}, это 1–3 минуты.`,
      `⏳ Posting ${number ? `build ${number}` : 'the latest build of main'}, 1–3 minutes.`));
  } else {
    await say(env, chatId, t(lang, `❌ GitHub не запустил выкладку: ${esc(status)}. Проверь GITHUB_TOKEN (Actions: read and write).`,
      `❌ GitHub did not start the post: ${esc(status)}. Check GITHUB_TOKEN (Actions: read and write).`));
  }
}

async function handlePrivate(env, msg, cmd, arg, s) {
  const user = msg.from;
  const chatId = msg.chat.id;
  const v = s.values;
  const lang = v.lang;
  if (cmd === 'start' || cmd === 'help') {
    if (cmd === 'start') {
      await setCommands(env);
      if (s.messageId === null) await saveSettings(env, s);   // the pinned settings message appears right away
    }
    await say(env, chatId, `${helpText(lang)}\n\n${describeSettings(v)}`);
  } else if (cmd === 'status') {
    await say(env, chatId, `${describeSettings(v)}\n\n` + t(lang, `Твой ID: <code>${user.id}</code>`, `Your id: <code>${user.id}</code>`));
  } else if (cmd === 'on' || cmd === 'off') {
    v.auto = cmd === 'on';
    await saveSettings(env, s);
    await say(env, chatId, t(lang, 'Автопостинг ' + (v.auto ? 'включён.' : 'выключен: сборки не выкладываются, /post работает.'),
      'Auto-posting ' + (v.auto ? 'on.' : 'off: builds are not posted, /post still works.')));
  } else if (TOGGLES.includes(cmd)) {
    const value = switchValue(arg, v[cmd]);
    if (value === null) {
      await say(env, chatId, t(lang, `Напиши /${cmd} on или /${cmd} off.`, `Write /${cmd} on or /${cmd} off.`));
      return;
    }
    v[cmd] = value;
    await saveSettings(env, s);
    await say(env, chatId, describeSettings(v));
  } else if (cmd === 'header') {
    if (!arg) {
      await say(env, chatId, t(lang, 'Текст сверху: ', 'Header: ') + (v.header ? `<i>${esc(v.header)}</i>` : '—') + '\n' +
        t(lang, '/header текст — задать, /header - убрать', '/header text sets it, /header - removes it'));
      return;
    }
    v.header = arg === '-' ? '' : arg.slice(0, HEADER_LIMIT);
    await saveSettings(env, s);
    await say(env, chatId, describeSettings(v));
  } else if (cmd === 'here') {
    await say(env, chatId, t(lang, 'Напиши /here в нужной теме группы (бот должен быть в группе с правом писать).',
      'Write /here in the topic of the group (the bot must be a member allowed to post).'));
  } else if (cmd === 'post') {
    if (arg && !/^\d+$/.test(arg)) {
      await say(env, chatId, t(lang, 'Номер сборки — число: /post 30830.', 'The build number is a number: /post 30830.'));
      return;
    }
    if (s.dirty) await saveSettings(env, s);   // the workflow reads the language from the pinned settings too
    await startPost(env, chatId, arg, lang);
  } else {
    await say(env, chatId, t(lang, 'Не знаю такой команды. /help — список.', 'Unknown command. /help lists them.'));
  }
}

async function notifyOwner(env, chatId, text) {
  try {
    await say(env, chatId, text);
  } catch (e) {
    if (!(e instanceof TelegramError)) throw e;
    console.log(`could not message the owner: ${e.description}`);
  }
}

// /here in a group or one of its topics: posts go there from now on.
async function handleHere(env, msg) {
  const chat = msg.chat;
  const user = msg.from;
  const topic = msg.is_topic_message ? msg.message_thread_id : null;
  const topicName = ((msg.reply_to_message || {}).forum_topic_created || {}).name;
  let where = chat.title || String(chat.id);
  if (topic) where += ' › ' + (topicName || `#${topic}`);
  let s = null;
  try {
    s = await loadSettings(env, user);
  } catch (e) {   // the settings live in the owner's chat: the owner has not opened the bot yet
    if (!(e instanceof TelegramError)) throw e;
  }
  const lang = s ? s.values.lang : String(user.language_code || '');
  const username = await botUsername(env);
  const openFirst = t(lang, `Сначала открой @${username} в личке и нажми «Начать», потом повтори /here.`,
    `Open @${username} in a private chat and press Start first, then repeat /here.`);
  if (!s) {
    await say(env, chat.id, openFirst, topic);
    return;
  }
  try {
    await say(env, chat.id, t(lang, 'Сборки SCAMERA будут приходить сюда.', 'SCAMERA builds will be posted here.'), topic);
  } catch (e) {
    if (!(e instanceof TelegramError)) throw e;
    await notifyOwner(env, user.id, t(lang,
      `Не могу писать в «${esc(where)}»: ${esc(e.description)}. Дай боту право отправлять сообщения и повтори /here.`,
      `Cannot post to “${esc(where)}”: ${esc(e.description)}. Allow the bot to send messages and repeat /here.`));
    return;
  }
  Object.assign(s.values, {chat: chat.id, topic, where});
  try {
    await saveSettings(env, s);
  } catch (e) {   // the owner blocked the bot or never pressed Start
    if (!(e instanceof TelegramError)) throw e;
    await say(env, chat.id, openFirst, topic);
    return;
  }
  await notifyOwner(env, user.id, t(lang, `✅ Сборки будут приходить в «${esc(where)}».`, `✅ Builds will be posted to “${esc(where)}”.`));
}

async function handleUpdate(env, update) {
  const msg = update.message;
  if (!msg || !msg.chat) return;
  const user = msg.from;
  const chat = msg.chat;
  if (chat.type === 'private') {
    if (!isOwner(env, user)) {
      await say(env, chat.id, t((user && user.language_code) || '', 'Это закрытый бот сборок SCAMERA.',
        'This is the private SCAMERA builds bot.') +
        (String(env.TELEGRAM_OWNER || '').trim() ? '' : `\nTELEGRAM_OWNER is not set; your id: <code>${user ? user.id : '?'}</code>`));
      return;
    }
    const [cmd, arg] = await parseCommand(env, msg);
    const s = await loadSettings(env, user);
    await handlePrivate(env, msg, cmd, arg, s);
    if (s.dirty) await saveSettings(env, s);
  } else if ((chat.type === 'group' || chat.type === 'supergroup') && isOwner(env, user) &&
             /^\/here(@\w+)?(\s|$)/i.test(String(msg.text || '').trim())) {
    const [cmd] = await parseCommand(env, msg);
    if (cmd === 'here') await handleHere(env, msg);
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/telegram' && request.method === 'POST') {
      if (!env.WEBHOOK_SECRET || request.headers.get('X-Telegram-Bot-Api-Secret-Token') !== env.WEBHOOK_SECRET) {
        return new Response('forbidden', {status: 403});
      }
      try {
        await handleUpdate(env, await request.json());
      } catch (e) {   // answer 200 anyway: Telegram would resend the update again and again
        console.log(`update failed: ${e instanceof TelegramError ? e.message : (e && e.name)}`);
      }
      return new Response('ok');
    }
    if (url.pathname === '/setup') {
      if (!env.WEBHOOK_SECRET || url.searchParams.get('key') !== env.WEBHOOK_SECRET) {
        return new Response('forbidden', {status: 403});
      }
      try {
        await bot(env, 'setWebhook', {url: `${url.origin}/telegram`, secret_token: env.WEBHOOK_SECRET,
          allowed_updates: ['message'], max_connections: 10});
        await setCommands(env);
        const info = await bot(env, 'getWebhookInfo');
        return new Response(`Webhook set: ${info.url}\nWaiting updates: ${info.pending_update_count}\n` +
          `Owner: ${env.TELEGRAM_OWNER ? 'set' : 'NOT SET'}, GitHub token: ${env.GITHUB_TOKEN ? 'set' : 'NOT SET'}\n`,
          {headers: {'Content-Type': 'text/plain; charset=utf-8'}});
      } catch (e) {
        return new Response(`Setup failed: ${e instanceof TelegramError ? e.message : (e && e.name)}\n`, {status: 500});
      }
    }
    return new Response('SCAMERA builds bot\n', {headers: {'Content-Type': 'text/plain; charset=utf-8'}});
  },
};
