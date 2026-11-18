# Сборка презентации защиты в стиле «Ведомость»: HTML и PPTX из content.py.
# Запуск: python build.py → ../campus-rating.html и ../campus-rating.pptx;
# проверка переполнения текста и скриншоты слайдов — во временной папке ОС.
# Нужны Python 3, Pillow, python-pptx и Playwright (Chromium).
import base64, html, io, os, re, sys, tempfile
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, 'assets')
sys.path.insert(0, HERE)
from content import SLIDES  # noqa: E402

LOGO_SVG = '<svg viewBox="0 0 34.63 34.63" fill="currentColor" aria-hidden="true"><path d="M11.07 0.64A14.5 14.5 0 0 1 22.1 26.34L20 22.89A10.5 10.5 0 0 0 11.07 4.69Z"/><path d="M9.07 30V9h4a6 6 0 0 1 0 12h-4" fill="none" stroke="currentColor" stroke-width="4"/><path d="M13.57 21L20.37 32.13" stroke="currentColor" stroke-width="4"/></svg>'

# ============================ ТЕМЫ ============================
# Стиль: (роль шрифта, размер px, жирность, курсив, межстрочный, трекинг em, прописные)
THEMES = {
    'vedomost': {
        'title': 'Campus Rating — защита',
        'fonts': {'serif': 'Literata', 'sans': 'PT Sans', 'mono': 'PT Mono'},
        'gfonts': 'https://fonts.googleapis.com/css2?family=Literata:ital,opsz,wght@0,7..72,500;0,7..72,700;0,7..72,800;1,7..72,500;1,7..72,700&family=PT+Sans:wght@400;700&family=PT+Mono&display=swap',
        'surf': {
            'dark': dict(bg='#0f1419', fg='#e6edf3', muted='#9da7b3', accent='#7aa2ff', rule='#2d3640', card='#161c23', cardline='#2d3640', ar='#8fb1ff', bua='#5fd0c6', boa='#f0a46b', logo='#e6edf3'),
            'light': dict(bg='#f5f6f8', fg='#161b22', muted='#57606a', accent='#2450c4', rule='#d6dbe1', card='#ffffff', cardline='#d6dbe1', ar='#2450c4', bua='#0b6e69', boa='#a3480a', logo='#161b22'),
        },
        'st': {
            'display': ('serif', 104, 800, False, 1.02, -0.025, False), 'hero': ('serif', 220, 700, False, 1.0, -0.03, False),
            'h1': ('serif', 132, 800, False, 1.05, -0.025, False), 'h2': ('serif', 64, 800, False, 1.1, -0.02, False),
            'h3': ('serif', 40, 700, False, 1.15, -0.01, False), 'h4': ('sans', 32, 700, False, 1.22, 0, False),
            'lead': ('sans', 34, 400, False, 1.4, 0, False), 'body': ('sans', 27, 400, False, 1.45, 0, False),
            'small': ('sans', 23, 400, False, 1.4, 0, False), 'kicker': ('sans', 24, 700, False, 1.3, 0, False),
            'mono': ('mono', 23, 400, False, 1.3, 0, False), 'stat': ('serif', 104, 700, False, 1.0, -0.02, False),
            'code': ('mono', 27, 400, False, 1.3, 0, False),
        },
        'em_weight': 500, 'card_radius': 16, 'card_line': 2, 'frame_radius': 12, 'bullet': '—', 'grid': False,
    },
}


def chrome(theme_id, s, i, total):
    """Шапка и подвал слайда — общие элементы для HTML и PPTX."""
    if not s['chrome']:
        return []
    n = f'{i + 1:02d} / {total:02d}'
    return [
        {'t': 'rule', 'x': 120, 'y': 52, 'w': 1680, 'c': 'fg', 'weight': 8},
        {'t': 'logo', 'x': 120, 'y': 78, 'w': 38, 'h': 38},
        {'t': 'text', 'x': 172, 'y': 76, 'w': 600, 'h': 44, 'st': 'h4', 'c': 'fg', 'text': 'Campus Rating', 'font': 'serif'},
        {'t': 'text', 'x': 1200, 'y': 82, 'w': 600, 'h': 34, 'st': 'small', 'c': 'muted', 'text': s['section'], 'align': 'right'},
        {'t': 'rule', 'x': 120, 'y': 1012, 'w': 1680, 'c': 'rule', 'weight': 2},
        {'t': 'text', 'x': 120, 'y': 1026, 'w': 1000, 'h': 34, 'st': 'mono', 'c': 'muted', 'text': 'Хакатон M-Social 2026 · защита 19.11 · БГТУ'},
        {'t': 'text', 'x': 1400, 'y': 1026, 'w': 400, 'h': 34, 'st': 'mono', 'c': 'muted', 'text': n, 'align': 'right'},
    ]


def runs(s):
    """'текст <em>акцент</em>' → [(текст, акцент?)]"""
    out = []
    for part in re.split(r'(<em>.*?</em>)', s):
        if not part:
            continue
        if part.startswith('<em>'):
            out.append((part[4:-5], True))
        else:
            out.append((part, False))
    return out


def cropped(src, w, h):
    """Обрезает картинку под пропорции рамки (верх сохраняется) и возвращает JPEG-байты."""
    im = Image.open(os.path.join(ASSETS, src)).convert('RGB')
    target = w / h
    if im.width / im.height > target:
        nw = int(im.height * target)
        im = im.crop(((im.width - nw) // 2, 0, (im.width - nw) // 2 + nw, im.height))
    else:
        im = im.crop((0, 0, im.width, int(im.width / target)))
    maxw = int(w * 1.6)
    if im.width > maxw:
        im = im.resize((maxw, int(maxw / target)), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, 'JPEG', quality=86, optimize=True)
    return buf.getvalue()


# ============================ HTML ============================
def html_deck(tid, local=True):
    T = THEMES[tid]
    F = T['fonts']
    fam = {'serif': f'"{F["serif"]}", Georgia, serif', 'sans': f'"{F["sans"]}", "Segoe UI", sans-serif', 'mono': f'"{F["mono"]}", Consolas, monospace'}
    css = [open(os.path.join(HERE, 'viewport-base.css'), encoding='utf-8').read()]
    css.append('/* === ТЕМА: цвета поверхностей === */')
    for sid, c in T['surf'].items():
        css.append(f'.s-{sid}{{' + ';'.join(f'--{k}:{v or "transparent"}' for k, v in c.items()) + f';background:var(--bg);color:var(--fg)}}')
    css.append(f':root{{--stage-bg:{T["surf"]["dark"]["bg"]};--slide-bg:{T["surf"]["light"]["bg"]};--duration-normal:.7s;--ease-out-expo:cubic-bezier(.16,1,.3,1)}}')
    css.append('/* === ТЕМА: типографика === */')
    for name, (role, px, wt, it, lh, ls, up) in T['st'].items():
        css.append(f'.st-{name}{{font-family:{fam[role]};font-size:{px}px;font-weight:{wt};font-style:{"italic" if it else "normal"};line-height:{lh};letter-spacing:{ls}em;{"text-transform:uppercase;" if up else ""}}}')
    css.append(f'''/* === ЭЛЕМЕНТЫ === */
.el{{position:absolute;margin:0;overflow:hidden;text-wrap:pretty}}
.el p{{margin:0}} .el p+p{{margin-top:.55em}}
.el.bul p{{display:grid;grid-template-columns:1.3em 1fr;gap:.2em}} .el.bul p::before{{content:"{T['bullet']}";color:var(--accent);font-family:{fam['mono']}}}
.el em{{font-style:italic;color:var(--accent);{"font-family:" + fam['serif'] + ";" }{"font-weight:" + str(T['em_weight']) + ";" if T['em_weight'] else ""}}}
.st-h2 em,.st-h1 em,.st-display em{{font-family:{fam['serif']}}}
.card{{position:absolute;background:var(--card);border:{T['card_line']}px solid var(--cardline);border-radius:{T['card_radius']}px}}
.rule{{position:absolute}}
.logo{{position:absolute;color:var(--logo)}} .logo svg{{width:100%;height:100%}}
.shot{{position:absolute;object-fit:cover;object-position:top;border:{T['card_line']}px solid var(--cardline);border-radius:{T['frame_radius']}px;background:#fff}}
{".s-dark::before{content:'';position:absolute;inset:0;pointer-events:none;background-image:linear-gradient(rgba(255,255,255,.035) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.035) 1px,transparent 1px);background-size:80px 80px}" if T['grid'] else ""}
/* === ПОЯВЛЕНИЕ === */
.reveal{{opacity:0;transform:translateY(24px);transition:opacity var(--duration-normal) var(--ease-out-expo),transform var(--duration-normal) var(--ease-out-expo)}}
.slide.visible .reveal{{opacity:1;transform:none}}
/* === ИНТЕРФЕЙС ВНЕ СЛАЙДА: прогресс и правка === */
.progress{{position:fixed;left:0;bottom:0;height:4px;background:{T['surf']['dark']['accent']};z-index:20;transition:width .4s}}
.edit-hotzone{{position:fixed;left:0;top:0;width:80px;height:80px;z-index:30}}
.edit-toggle{{position:fixed;left:16px;top:16px;z-index:31;opacity:0;pointer-events:none;padding:8px 14px;border:0;border-radius:6px;background:#161b22;color:#fff;font:600 14px sans-serif;cursor:pointer;transition:opacity .2s}}
.edit-toggle.show,.edit-toggle.active{{opacity:1;pointer-events:auto}} .edit-toggle.active{{background:#2450c4}}
body.editing .el[contenteditable]{{outline:2px dashed rgba(36,80,196,.6);outline-offset:4px;overflow:visible}}
@media print{{.progress,.edit-toggle,.edit-hotzone{{display:none}}}}
''')
    total = len(SLIDES)
    secs = []
    for i, s in enumerate(SLIDES):
        els = chrome(tid, s, i, total) + s['els']
        parts = []
        order = 0
        for e in els:
            pos = f'left:{e["x"]:.0f}px;top:{e["y"]:.0f}px;width:{e["w"]:.0f}px'
            is_chrome = e in chrome(tid, s, i, total)
            rv = '' if is_chrome else ' reveal'
            delay = '' if is_chrome else f';transition-delay:{min(order, 14) * 0.05:.2f}s'
            if not is_chrome:
                order += 1
            if e['t'] == 'text':
                st = e['st']
                font = f';font-family:{fam[e["font"]]}' if e.get('font') else ''
                align = f';text-align:{e.get("align", "left")}'
                body = ''.join(f'<p>{"".join(f"<em>{html.escape(t)}</em>" if em else html.escape(t) for t, em in runs(line))}</p>' for line in e['text'].split('\n'))
                cls = 'el st-' + st + (' bul' if e.get('bullets') else '') + rv
                parts.append(f'<div class="{cls}" data-edit style="{pos};height:{e["h"]:.0f}px;color:var(--{e["c"]}){font}{align}{delay}">{body}</div>')
            elif e['t'] == 'card':
                parts.append(f'<div class="card{rv}" style="{pos};height:{e["h"]:.0f}px{delay}"></div>')
            elif e['t'] == 'rule':
                parts.append(f'<div class="rule{rv}" style="{pos};height:{e["weight"]}px;background:var(--{e["c"]}){delay}"></div>')
            elif e['t'] == 'logo':
                parts.append(f'<div class="logo{rv}" style="{pos};height:{e["h"]}px{delay}">{LOGO_SVG}</div>')
            elif e['t'] == 'img':
                data = base64.b64encode(cropped(e['src'], e['w'], e['h'])).decode()
                parts.append(f'<img class="shot{rv}" alt="" src="data:image/jpeg;base64,{data}" style="{pos};height:{e["h"]:.0f}px{delay}">')
        note = html.escape(s['notes']).replace('--', '—')
        secs.append(f'<!-- Слайд {i + 1}. Текст выступающего: {note} -->\n<section class="slide s-{s["surface"]}{" active visible" if i == 0 else ""}" data-notes="{html.escape(s["notes"])}" aria-label="Слайд {i + 1}">\n' + '\n'.join(parts) + '\n</section>')
    js = r'''
/* === УПРАВЛЕНИЕ: стрелки, пробел, PageUp/PageDown, Home/End, свайп, колесо; #s5 открывает 5-й слайд === */
class SlidePresentation {
  constructor() {
    this.slides = [...document.querySelectorAll('.slide')];
    this.stage = document.getElementById('deckStage');
    this.bar = document.getElementById('progress');
    this.current = 0;
    const scale = () => {
      const k = Math.min(innerWidth / 1920, innerHeight / 1080);
      this.stage.style.transform = `translate(${(innerWidth - 1920 * k) / 2}px, ${(innerHeight - 1080 * k) / 2}px) scale(${k})`;
    };
    scale(); addEventListener('resize', scale);
    document.addEventListener('keydown', (e) => {
      if (document.body.classList.contains('editing')) return;
      if (['ArrowRight', 'ArrowDown', ' ', 'PageDown', 'Enter'].includes(e.key)) { e.preventDefault(); this.go(this.current + 1); }
      if (['ArrowLeft', 'ArrowUp', 'PageUp', 'Backspace'].includes(e.key)) { e.preventDefault(); this.go(this.current - 1); }
      if (e.key === 'Home') this.go(0);
      if (e.key === 'End') this.go(this.slides.length - 1);
    });
    let sx = null;
    addEventListener('touchstart', (e) => { sx = e.touches[0].clientX; }, { passive: true });
    addEventListener('touchend', (e) => { if (sx == null) return; const d = e.changedTouches[0].clientX - sx; if (Math.abs(d) > 40) this.go(this.current + (d < 0 ? 1 : -1)); sx = null; });
    let lock = 0;
    addEventListener('wheel', (e) => { if (document.body.classList.contains('editing')) return; const t = Date.now(); if (t - lock < 700 || Math.abs(e.deltaY) < 20) return; lock = t; this.go(this.current + (e.deltaY > 0 ? 1 : -1)); }, { passive: true });
    const m = /^#s(\d+)$/.exec(location.hash);
    this.go(m ? +m[1] - 1 : 0);
  }
  go(i) {
    this.current = Math.max(0, Math.min(i, this.slides.length - 1));
    this.slides.forEach((s, k) => { s.classList.toggle('active', k === this.current); s.classList.toggle('visible', k === this.current); });
    this.bar.style.width = ((this.current + 1) / this.slides.length * 100) + '%';
    try { history.replaceState(null, '', '#s' + (this.current + 1)); } catch (e) {}
  }
}
const deck = new SlidePresentation();

/* === ПРАВКА ТЕКСТА: клавиша E или кнопка в левом верхнем углу; правки хранятся в браузере === */
const KEY = 'deck-edits-' + document.title;
const editable = [...document.querySelectorAll('[data-edit]')];
try { const saved = JSON.parse(localStorage.getItem(KEY) || '{}'); editable.forEach((el, i) => { if (saved[i] != null) el.innerHTML = saved[i]; }); } catch (e) {}
const editor = {
  isActive: false,
  toggle() {
    this.isActive = !this.isActive;
    document.body.classList.toggle('editing', this.isActive);
    editable.forEach((el) => { if (this.isActive) el.setAttribute('contenteditable', 'true'); else el.removeAttribute('contenteditable'); });
    toggleBtn.classList.toggle('active', this.isActive);
    toggleBtn.textContent = this.isActive ? 'Готово' : 'Править';
    if (!this.isActive) this.save();
  },
  save() { try { const out = {}; editable.forEach((el, i) => { out[i] = el.innerHTML; }); localStorage.setItem(KEY, JSON.stringify(out)); } catch (e) {} },
};
const toggleBtn = document.getElementById('editToggle');
const hot = document.querySelector('.edit-hotzone');
let hideT = null;
hot.addEventListener('mouseenter', () => { clearTimeout(hideT); toggleBtn.classList.add('show'); });
hot.addEventListener('mouseleave', () => { hideT = setTimeout(() => { if (!editor.isActive) toggleBtn.classList.remove('show'); }, 400); });
toggleBtn.addEventListener('mouseenter', () => clearTimeout(hideT));
toggleBtn.addEventListener('mouseleave', () => { hideT = setTimeout(() => { if (!editor.isActive) toggleBtn.classList.remove('show'); }, 400); });
toggleBtn.addEventListener('click', () => editor.toggle());
hot.addEventListener('click', () => editor.toggle());
document.addEventListener('keydown', (e) => {
  if ((e.key === 'e' || e.key === 'E' || e.key === 'у' || e.key === 'У') && !e.target.isContentEditable) { e.preventDefault(); editor.toggle(); }
});
'''
    SAVE_JS = r'''
/* === Ctrl+S: сохранить HTML с правками (только в локальном файле) === */
document.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault(); editor.save();
    const clone = document.documentElement.cloneNode(true);
    clone.querySelectorAll('[contenteditable]').forEach((el) => el.removeAttribute('contenteditable'));
    clone.querySelector('body').classList.remove('editing');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob(['<!DOCTYPE html>\n' + clone.outerHTML], { type: 'text/html' }));
    a.download = location.pathname.split('/').pop() || 'presentation.html';
    a.click();
  }
});
'''
    head = f'<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>{T["title"]}</title><link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin><link rel="stylesheet" href="{T["gfonts"]}"><style>\n' + '\n'.join(css) + '\n</style>'
    body = '<div class="deck-viewport"><main class="deck-stage" id="deckStage">\n' + '\n'.join(secs) + '\n</main></div>\n<div class="progress" id="progress"></div>\n<div class="edit-hotzone" title="Правка (E)"></div><button class="edit-toggle" id="editToggle" type="button" title="Правка текста (E)">Править</button>\n<script>' + js + (SAVE_JS if local else '') + '</script>'
    return head, body


# ============================ PPTX ============================
def pptx_deck(tid, path, logos):
    from pptx import Presentation
    from pptx.util import Emu, Pt
    from pptx.dml.color import RGBColor
    from pptx.enum.shapes import MSO_SHAPE
    from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
    from pptx.oxml.ns import qn
    T = THEMES[tid]
    px = lambda v: Emu(int(round(v * 6350)))
    rgb = lambda h: RGBColor.from_string(h.lstrip('#'))
    prs = Presentation()
    prs.slide_width, prs.slide_height = px(1920), px(1080)
    total = len(SLIDES)
    for i, s in enumerate(SLIDES):
        C = T['surf'][s['surface']]
        sl = prs.slides.add_slide(prs.slide_layouts[6])
        sl.background.fill.solid(); sl.background.fill.fore_color.rgb = rgb(C['bg'])
        for e in chrome(tid, s, i, total) + s['els']:
            if e['t'] == 'card':
                shp = sl.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE if T['card_radius'] else MSO_SHAPE.RECTANGLE, px(e['x']), px(e['y']), px(e['w']), px(e['h']))
                if T['card_radius']:
                    shp.adjustments[0] = T['card_radius'] / min(e['w'], e['h'])
                if C['card']:
                    shp.fill.solid(); shp.fill.fore_color.rgb = rgb(C['card'])
                else:
                    shp.fill.background()
                shp.line.color.rgb = rgb(C['cardline']); shp.line.width = px(T['card_line'])
                shp.shadow.inherit = False
            elif e['t'] == 'rule':
                shp = sl.shapes.add_shape(MSO_SHAPE.RECTANGLE, px(e['x']), px(e['y']), px(e['w']), px(max(e['weight'], 1)))
                shp.fill.solid(); shp.fill.fore_color.rgb = rgb(C[e['c']]); shp.line.fill.background(); shp.shadow.inherit = False
            elif e['t'] == 'logo':
                sl.shapes.add_picture(logos[(tid, s['surface'])], px(e['x']), px(e['y']), px(e['w']), px(e['h']))
            elif e['t'] == 'img':
                pic = sl.shapes.add_picture(io.BytesIO(cropped(e['src'], e['w'], e['h'])), px(e['x']), px(e['y']), px(e['w']), px(e['h']))
                pic.line.color.rgb = rgb(C['cardline']); pic.line.width = px(T['card_line'])
            elif e['t'] == 'text':
                role, size, wt, it, lh, ls, up = T['st'][e['st']]
                if e.get('font'):
                    role = e['font']
                tb = sl.shapes.add_textbox(px(e['x']), px(e['y']), px(e['w']), px(e['h']))
                tf = tb.text_frame
                tf.word_wrap = True
                tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
                tf.vertical_anchor = MSO_ANCHOR.TOP
                for li, line in enumerate(e['text'].split('\n')):
                    p = tf.paragraphs[0] if li == 0 else tf.add_paragraph()
                    p.alignment = {'right': PP_ALIGN.RIGHT, 'center': PP_ALIGN.CENTER}.get(e.get('align'), PP_ALIGN.LEFT)
                    p.line_spacing = lh * 0.86
                    if li:
                        p.space_before = Pt(size * 0.5 * 0.55)
                    items = runs(line)
                    if e.get('bullets'):
                        items = [(T['bullet'] + '  ', 'bul')] + items
                    for t, em in items:
                        r = p.add_run()
                        r.text = t.upper() if up else t
                        f = r.font
                        f.size = Pt(size * 0.5)
                        f.bold = wt >= 600
                        f.italic = it or em is True
                        f.name = T['fonts']['serif'] if em is True and role != 'mono' else T['fonts'][role]
                        f.color.rgb = rgb(C['accent'] if em else C[e['c']])
                        if ls:
                            r._r.get_or_add_rPr().set('spc', str(int(ls * size * 0.5 * 100)))
        sl.notes_slide.notes_text_frame.text = s['notes']
    prs.save(path)


# ============================ ЗАПУСК ============================
def main():
    from playwright.sync_api import sync_playwright
    check = os.path.join(tempfile.gettempdir(), 'campus-rating-deck-check')
    os.makedirs(check, exist_ok=True)
    outs = {}
    for tid in THEMES:
        head, body = html_deck(tid)
        full = f'<!DOCTYPE html>\n<html lang="ru">\n<head>\n{head}\n</head>\n<body>\n{body}\n</body>\n</html>\n'
        p = os.path.join(ROOT, 'campus-rating.html')
        open(p, 'w', encoding='utf-8').write(full)
        outs[tid] = p
    report = []
    logos = {}
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page()
        for tid in THEMES:
            for sid, c in THEMES[tid]['surf'].items():
                pg.set_viewport_size({'width': 200, 'height': 200})
                pg.set_content(f'<body style="margin:0;background:transparent"><div id="l" style="width:200px;height:200px;color:{c["logo"]}">{LOGO_SVG}</div></body>')
                buf = pg.locator('#l').screenshot(omit_background=True)
                logos[(tid, sid)] = io.BytesIO(buf)
        for tid, p in outs.items():
            pg.set_viewport_size({'width': 1280, 'height': 720})
            pg.goto('file:///' + p.replace('\\', '/'))
            pg.wait_for_timeout(1500)
            for i in range(len(SLIDES)):
                pg.evaluate(f'deck.go({i})'); pg.wait_for_timeout(900)
                bad = pg.evaluate('''(i) => [...document.querySelectorAll('.slide')[i].querySelectorAll('.el')].filter(e => e.scrollHeight > e.clientHeight + 3 || e.scrollWidth > e.clientWidth + 3).map(e => e.textContent.slice(0, 50) + ` [${e.scrollHeight}>${e.clientHeight}]`)''', i)
                if bad:
                    report.append((tid, i + 1, bad))
                pg.screenshot(path=os.path.join(check, f'{i + 1:02d}.png'))
        b.close()
    for tid in THEMES:
        for k in logos: logos[k].seek(0)
        pptx_deck(tid, os.path.join(ROOT, 'campus-rating.pptx'), logos)
    print('Скриншоты слайдов:', check)
    print('OVERFLOW', len(report))
    for r in report:
        print(r)
    for f in sorted(os.listdir(ROOT)):
        fp = os.path.join(ROOT, f)
        if os.path.isfile(fp):
            print(f, os.path.getsize(fp) // 1024, 'KB')


if __name__ == '__main__':
    main()
