"""UI language check: English by default, Russian only in values-ru (stdlib only).

The interface is Russian on a Russian system and English on any other (util/Lang.java). This check keeps it that way:

  (a) default res/values*/ files (every values folder except values-ru*) have no Cyrillic in <string> / <item> text;
  (b) res/xml, res/layout, res/menu (and the other non-values res folders) have no Cyrillic in attribute values
      (XML comments and tools: attributes are ignored);
  (c) values-ru names exist in the defaults, every translatable default string / string-array / plurals has a
      values-ru entry, a translatable="false" default has none, arrays keep their item count and both languages
      use the same format placeholders; no locale folders other than the default (English) and values-ru;
  (d) Java string and char literals with Cyrillic under <module>/src/main/java are only the Russian argument of
      Lang.t(ru, en) / Lang.t(context, ru, en), text of a Log call, or on the ALLOW list below (with the reason).

Run from anywhere: python3 tools/check_ui_language.py
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODULES = ['app', 'circularbarlib']
CYRILLIC = re.compile('[%s-%s]' % (chr(0x0400), chr(0x04FF)))  # the Cyrillic block
TOOLS_NS = '{http://schemas.android.com/tools}'
# A Java / Android format placeholder (%s, %d, %1$s, %.2f ...); a lone '%' before a space ("40 % smaller") is text.
PLACEHOLDER = re.compile(r'%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?([sdfxXc])')

# (d) Cyrillic Java literals that are not UI text: file (relative to the module's src/main/java) -> [(reason, literals)].
# A literal is the exact source text between the quotes. Every listed literal must still exist (no stale entries).
SENSOR_CONFIG = [
    'Тип capture-сессии Camera2 (0 = обычная)',
    'Оптическая стабилизация. В режиме «Авто» OIS выключается на штативе и в Unlimited, чтобы кадр не дрейфовал',
    'Сдвиг баланса между выдержкой и ISO. Только режимы «Фото» и «Ночь»',
    'Ограничение наибольшей чувствительности',
    'Ограничение наибольшей выдержки',
]
SENSOR_CONFIG_PARAMETERS = [
    'Уровень белого для всех каналов вручную (-1 = из метаданных)',
    'Уровень чёрного этого сенсора вручную (-1 = авто)',
]
TUNABLE = [
    'Брать динамический уровень чёрного из результата съёмки Camera2, если он есть (на некоторых устройствах нестабильно)',
    'Брать динамический уровень белого из результата съёмки Camera2, если он есть (на некоторых устройствах нестабильно)',
    'Не зеркалить снимки фронтальной камеры',
]
ANNOTATION = ('annotation description (a compile-time constant, so no Lang.t): shown through the description() '
              'lookup of ModuleSensorSettings / TunablePreferenceGenerator')
LOOKUP = 'matcher: the Russian annotation descriptions, looked up to show them in the UI language'
ALLOW = {
    'com/particlesdevs/photoncamera/settings/ShadeCatalog.java': [
        ('search synonyms (KEYWORDS): the Russian words keep finding a row whatever the UI language', [
            'denoise|шумодав|шумопод|noise|шум|despeckle', 'шум шумоподавление шумодав denoise noise',
            'luma|люма|ярк|bright', 'яркость люма luma brightness',
            'chroma|хрома|цвет|colo', 'цвет хрома chroma color colour',
            'sharp|резк|usm|unsharp|деконвол|deconv|ореол|halo|чётк|четк', 'резкость sharpness sharpen usm детали detail',
            'bento бенто короткий ультракороткий кадр пересвет света short highlights',
            'shasta шаста длинный кадр тени long shadows',
            'zsl|кадр|frame', 'кадры frames zsl буфер buffer',
            'hdr|fusion|экспоз|exposure|тонмап|tone map|тон |tone |ae ', 'hdr экспозиция тон яркость exposure tone',
            'мозаик|mosaic|quad|tetra|байер|bayer|hp9', 'мозаика ремозаик quad tetra bayer mosaic',
            'сетк|grid', 'сетка grid',
            'фокус|focus|peak', 'фокус focus пик peaking',
            'отладк|hud|диагност|diagnost|журнал|debug', 'отладка debug hud лог log',
            'водян|подпис|watermark|caption', 'водяной знак watermark подпись caption',
            'agx|aces|кривая|гамма|gamma|curve', 'кривая curve тон agx гамма gamma',
            'dcp|матриц|matri', 'dcp матрица цвет профиль matrix colour color profile',
            'мерцан|flicker|antiband', 'мерцание flicker антибандинг antibanding',
            'вспышк|фонар|flash|torch', 'вспышка flash фонарик torch',
            'таймер|timer', 'таймер timer',
            'raw|jpeg|heic|webp|avif|формат|format', 'формат format raw jpeg dng heic webp avif кодек codec',
            'звук|sound', 'звук sound',
            r'замер|\\bmeter', 'замер metering экспозамер',
            'склейк|merge|route', 'склейка merge route hybrid scam',
            'разрешен|resolution|даунсемпл|downsampl', 'разрешение resolution мп mp размер size',
        ]),
        ('search normalisation: «ё» is searched as «е»', ['ё', 'е']),
    ],
    'com/particlesdevs/photoncamera/ui/settings/SettingsSearchFragment.java': [
        ('search normalisation: «ё» is searched as «е»', ['ё', 'е']),
    ],
    'com/particlesdevs/photoncamera/capture/CaptureController.java': [(ANNOTATION, SENSOR_CONFIG)],
    'com/particlesdevs/photoncamera/processing/render/Parameters.java': [(ANNOTATION, SENSOR_CONFIG_PARAMETERS + TUNABLE)],
    'com/particlesdevs/photoncamera/settings/ModuleSensorSettings.java': [(LOOKUP, SENSOR_CONFIG + SENSOR_CONFIG_PARAMETERS)],
    'com/particlesdevs/photoncamera/settings/TunablePreferenceGenerator.java': [(LOOKUP, TUNABLE)],
    'com/particlesdevs/photoncamera/processing/color/DcpProfiles.java': [
        ('stored format of imported DCP profile names (user data), shown through DcpProfiles.label()',
         ['Профиль DCP', ' · только матрицы']),
    ],
}

errors = []


def error(path, line, text):
    errors.append(f'{path.relative_to(ROOT).as_posix()}:{line}: {text}')


# ---------------------------------------------------------------- resources

def is_language(part):
    return part.startswith('b+') or re.fullmatch(r'[a-z]{2}', part) is not None or (
        re.fullmatch(r'[a-z]{3}', part) is not None and part not in ('car',))


def language(folder):
    """'' for a default folder, the language code for a localized one."""
    parts = folder.split('-')[1:]
    if parts and is_language(parts[0]):
        return parts[0][2:].split('+')[0] if parts[0].startswith('b+') else parts[0]
    return ''


def line_of(path, needle):
    try:
        for i, text in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            if needle in text:
                return i
    except OSError:
        pass
    return 0


def text_of(element):
    return ''.join(element.itertext())


def placeholders(text):
    out = []
    for m in PLACEHOLDER.finditer(text.replace('%%', '')):
        out.append((m.group(1) or '', m.group(2)))
    return sorted(out)


KINDS = {'string': 'string', 'string-array': 'array', 'array': 'array', 'plurals': 'plurals'}  # resource type by tag


def read_values(folder):
    """{(type, name): (element, path)} of string / string-array / array / plurals in one values folder."""
    out = {}
    for path in sorted(folder.glob('*.xml')):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as e:
            error(path, 0, f'not well-formed XML: {e}')
            continue
        for element in root:
            if element.tag in KINDS and element.get('name') is not None:
                out[(KINDS[element.tag], element.get('name'))] = (element, path)
    return out


def check_resources(module):
    res = ROOT / module / 'src/main/res'
    if not res.is_dir():
        return
    defaults, russian = {}, {}
    for folder in sorted(p for p in res.iterdir() if p.is_dir()):
        kind = folder.name.split('-')[0]
        lang = language(folder.name)
        if kind == 'values':
            if lang not in ('', 'ru'):
                error(folder, 0, 'only the default (English) and values-ru locales are allowed')
                continue
            values = read_values(folder)
            (russian if lang == 'ru' else defaults).update(values)
            if lang == '':
                # (a) no Cyrillic in default texts
                for (k, name), (element, path) in values.items():
                    items = [element] if k == 'string' else list(element)
                    for item in items:
                        if CYRILLIC.search(text_of(item)):
                            error(path, line_of(path, f'name="{name}"'), f'Cyrillic in default {k} "{name}" (Russian goes to values-ru)')
                            break
            continue
        if lang not in ('', 'ru'):
            error(folder, 0, 'only the default (English) and values-ru locales are allowed')
        # (b) no Cyrillic in attribute values of layouts, preference XML, menus, drawables ...
        for path in sorted(folder.glob('*.xml')):
            try:
                tree = ET.parse(path).getroot()
            except ET.ParseError as e:
                error(path, 0, f'not well-formed XML: {e}')
                continue
            for element in tree.iter():
                for key, value in element.attrib.items():
                    if key.startswith(TOOLS_NS) or not CYRILLIC.search(value):
                        continue
                    error(path, line_of(path, value), f'Cyrillic in attribute {key.split("}")[-1]}="{value}" (use @string/…)')
    # (c) pairs
    for (kind, name), (element, path) in russian.items():
        if (kind, name) not in defaults:
            error(path, line_of(path, f'name="{name}"'), f'values-ru {kind} "{name}" has no default (English) entry')
            continue
        default, dpath = defaults[(kind, name)]
        if default.get('translatable') == 'false':
            error(path, line_of(path, f'name="{name}"'), f'values-ru translates {kind} "{name}", which is translatable="false"')
        if kind == 'string':
            if placeholders(text_of(default)) != placeholders(text_of(element)):
                error(path, line_of(path, f'name="{name}"'), f'placeholders differ from the default: '
                      f'{placeholders(text_of(default))} vs {placeholders(text_of(element))}')
        elif kind == 'array':  # (plurals keep the quantities of their language)
            if len(list(default)) != len(list(element)):
                error(path, line_of(path, f'name="{name}"'), f'array "{name}" has {len(list(element))} items, the default {len(list(default))}')
            else:
                for i, (d, r) in enumerate(zip(default, element)):
                    if placeholders(text_of(d)) != placeholders(text_of(r)):
                        error(path, line_of(path, f'name="{name}"'), f'array "{name}" item {i}: placeholders differ from the default')
    for (kind, name), (element, path) in defaults.items():
        if element.get('translatable') == 'false' or (kind, name) in russian:
            continue
        if kind == 'string' and not re.search('[A-Za-z]', text_of(element)):
            continue  # nothing to translate (numbers, symbols, references)
        if kind != 'string' and all(not re.search('[A-Za-z]', text_of(i)) or text_of(i).strip().startswith('@') for i in element):
            continue
        error(path, line_of(path, f'name="{name}"'), f'translatable default {kind} "{name}" has no values-ru entry '
              f'(add the Russian text, or translatable="false" when it is not language text)')


# ---------------------------------------------------------------- Java

TOKEN = re.compile(r'''
    (?P<comment>//[^\n]*|/\*.*?\*/)
  | (?P<block>"""(?:\\.|[^\\])*?""")
  | (?P<string>"(?:\\.|[^"\\\n])*")
  | (?P<char>'(?:\\.|[^'\\\n])+')
  | (?P<name>[A-Za-z_$][\w$]*)
  | (?P<punct>[()\[\]{},.;@])
  | (?P<other>\S)
''', re.S | re.X)
LOG_CALL = re.compile(r'(^|\.)(Log|ScameraDebugLog|AsyncLog)\.[a-zA-Z]+$')


def tokens(source):
    out = []
    line = 1
    pos = 0
    for m in TOKEN.finditer(source):
        line += source.count('\n', pos, m.start())
        pos = m.start()
        kind = m.lastgroup
        if kind != 'comment':
            out.append((kind, m.group(), line))
    return out


def callee(toks, open_index):
    """The dotted name before '(' at open_index ('Lang.t', 'android.util.Log.w', 'setTitle'), or ''."""
    parts = []
    i = open_index - 1
    while i >= 0:
        kind, text, _ = toks[i]
        if kind == 'name':
            parts.append(text)
            if i > 0 and toks[i - 1][1] == '.':
                i -= 2
                continue
        break
    return '.'.join(reversed(parts))


def check_java(module):
    base = ROOT / module / 'src/main/java'
    if not base.is_dir():
        return
    seen = {}
    for path in sorted(base.rglob('*.java')):
        rel = path.relative_to(base).as_posix()
        source = path.read_text(encoding='utf-8')
        if not CYRILLIC.search(source):
            continue
        toks = tokens(source)
        # matching brackets, argument counts and argument index of every token
        stack, frames, arg_of, nargs = [], [], {}, {}
        frame_of = [None] * len(toks)
        for i, (kind, text, _) in enumerate(toks):
            if text in '([{' and kind == 'punct':
                stack.append([i, 0, text])
            elif text in ')]}' and kind == 'punct':
                if stack:
                    open_index, commas, _ = stack.pop()
                    nargs[open_index] = commas + 1
            elif text == ',' and kind == 'punct' and stack:
                stack[-1][1] += 1
            frame_of[i] = [(f[0], f[1], f[2]) for f in stack]
        for i, (kind, text, line) in enumerate(toks):
            if kind not in ('string', 'block', 'char') or not CYRILLIC.search(text):
                continue
            literal = text[3:-3] if kind == 'block' else text[1:-1]
            verdict = None
            for open_index, arg, bracket in reversed(frame_of[i]):
                if bracket != '(':
                    continue
                name = callee(toks, open_index)
                if LOG_CALL.search(name):
                    verdict = 'ok'
                    break
                if name == 'Lang.t' or name.endswith('.Lang.t'):
                    n = nargs.get(open_index, 0)
                    ru = 0 if n == 2 else 1 if n == 3 else -1
                    if arg == ru:
                        verdict = 'ok'
                    elif arg == ru + 1:
                        verdict = 'Cyrillic in the English argument of Lang.t'
                    else:
                        verdict = f'Cyrillic in argument {arg} of a {n}-argument Lang.t'
                    break
            if verdict == 'ok':
                continue
            if verdict is None:
                allowed = False
                for reason, literals in ALLOW.get(rel, []):
                    if literals is None or literal in literals:
                        allowed = True
                        seen.setdefault((rel, reason), set()).add(literal)
                        break
                if allowed:
                    continue
                verdict = 'Cyrillic literal outside Lang.t / Log (UI text goes through Lang.t(ru, en) or resources)'
            error(path, line, f'{verdict}: "{literal}"')
    for rel, entries in ALLOW.items():
        if not (base / rel).is_file():
            continue
        for reason, literals in entries:
            found = seen.get((rel, reason), set())
            if literals is None:
                if not found:
                    error(base / rel, 0, f'stale ALLOW entry ({reason}): no Cyrillic literal left')
            else:
                for literal in literals:
                    if literal not in found:
                        error(base / rel, 0, f'stale ALLOW entry ({reason}): "{literal}" not found')
    return seen


def main():
    listing = '--list' in sys.argv
    for module in MODULES:
        check_resources(module)
        seen = check_java(module)
        if listing and seen:
            for (rel, reason), literals in sorted(seen.items()):
                print(f'{module}/{rel}: {reason}: {len(literals)} literal(s)')
                for literal in sorted(literals):
                    print('   ', literal)
    if errors:
        print('UI language FAIL:')
        for e in errors:
            print('  ' + e)
        sys.exit(1)
    print('UI language PASS: English defaults, Russian only in values-ru, Java UI text through Lang.t, '
          'Cyrillic Java literals only in Lang.t / Log / the allow list')


if __name__ == '__main__':
    main()
