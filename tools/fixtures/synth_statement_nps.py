"""Build a synthetic Protean NPS statement PDF from a real one.

Only the GEOMETRY (word positions, fonts, table rectangles) and public template text
of the real PDF are reused. Every personal value (name, PRAN, address, phone, email,
nominee, registration no., dates, amounts, units, NAVs, %s) is replaced with invented,
internally consistent dummy data. The PDF is written from scratch with reportlab:
no original bytes, streams, fonts, images or metadata are copied.
A leak check fails the run if any original personal token survives.

usage: synth_nps.py <real.pdf> <wide|stacked> <rebalance|contribution|empty> <out.pdf> <password>
Prints only dummy text and counts.
"""
import re, sys, subprocess, datetime as dt
from decimal import Decimal as D, ROUND_HALF_UP
import pdfplumber
from reportlab.pdfgen import canvas

SRC, LAYOUT, VARIANT, OUT, PW = sys.argv[1:6]
Q2, Q4 = D('0.01'), D('0.0001')
def q2(x): return D(x).quantize(Q2, ROUND_HALF_UP)
def q4(x): return D(x).quantize(Q4, ROUND_HALF_UP)

# ---------------- dummy subscriber ----------------
DUMMY = dict(pran='110012345678', reg='12-Mar-19', name='RAVI KUMAR SHARMA',
             addr=['12, 4TH CROSS, MG ROAD', 'INDIRANAGAR, BENGALURU,', 'KARNATAKA-560038, INDIA'],
             mobile='+919800000001', email='ravi.sharma@example.com', reg_no='6000001',
             nominee='SUNITA SHARMA', nominee_pct='100%')
SCHEMES = ['E', 'C', 'G']
MON = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']

if LAYOUT == 'wide':
    start, end, gen = dt.date(2025,1,1), dt.date(2025,1,31), dt.datetime(2025,2,6,10,15)
    bill_d, txn_d = dt.date(2025,1,7), dt.date(2025,1,15)
    pref = {'E': D('50.00'), 'C': D('30.00'), 'G': D('20.00')}
else:
    start, end, gen = dt.date(2026,6,1), dt.date(2026,6,30), dt.datetime(2026,7,9,10,15)
    bill_d, txn_d = None, dt.date(2026,6,14)          # 14-Jun = dummy birthday (LC rebalance)
    pref = {'E': D('73.50'), 'C': D('14.25'), 'G': D('12.25')}

open_u = {'E': D('4210.5521'), 'C': D('1890.3307'), 'G': D('2405.7712')}
if VARIANT == 'empty':      # Feb 2025, no transactions; opening = Jan 2025 wide-contribution closing
    assert LAYOUT == 'wide'
    start, end, gen = dt.date(2025,2,1), dt.date(2025,2,28), dt.datetime(2025,3,6,10,15)
    bill_d = txn_d = None
    open_u = {'E': D('4508.9644'), 'C': D('2157.5713'), 'G': D('2604.4642')}
nav_t  = {'E': D('62.4417'), 'C': D('41.8820'), 'G': D('37.5541')}     # NAV on txn date
nav_b  = {'E': D('61.9032'), 'C': D('41.7105'), 'G': D('37.4988')}     # NAV on billing date
nav_e  = {'E': D('63.1086'), 'C': D('41.9907'), 'G': D('37.6123')}     # NAV on statement end
bill_amt = {'E': D('41.27'), 'C': D('12.38'), 'G': D('8.25')}

lines = []          # (kind, {scheme: (amount, nav, units)})
cur = dict(open_u)
if bill_d:
    b = {s: (-bill_amt[s], nav_b[s], -q4(bill_amt[s] / nav_b[s])) for s in SCHEMES}
    for s in SCHEMES: cur[s] += b[s][2]
    lines.append(('bill', b))
if VARIANT == 'contribution':
    total = D('37350.00')
    amts = {'E': q2(total * pref['E'] / 100), 'C': q2(total * pref['C'] / 100)}
    amts['G'] = total - amts['E'] - amts['C']
    c = {s: (amts[s], nav_t[s], q4(amts[s] / nav_t[s])) for s in SCHEMES}
    for s in SCHEMES: cur[s] += c[s][2]
    lines.append(('contrib', c))
elif VARIANT == 'empty':
    pass
else:   # rebalance: sell equity, buy C and G, net zero
    sellE = D('8125.40'); buyC = D('4870.15'); buyG = sellE - buyC
    r = {'E': (-sellE, nav_t['E'], -q4(sellE / nav_t['E'])),
         'C': (buyC, nav_t['C'], q4(buyC / nav_t['C'])),
         'G': (buyG, nav_t['G'], q4(buyG / nav_t['G']))}
    for s in SCHEMES: cur[s] += r[s][2]
    lines.append(('rebal', r))
close_u = cur
value = {s: q2(close_u[s] * nav_e[s]) for s in SCHEMES}
A = sum(value.values()); n_contrib = 6 if VARIANT == 'rebalance' else 7
B = D('275400.00') if VARIANT == 'rebalance' else D('312750.00')
Cw = D('0.00'); Dg = A - B + Cw; Ech = D('612.40')
summary_vals = [A, D(n_contrib), B, Cw, Dg] + ([Ech] if LAYOUT == 'wide' and VARIANT != 'empty' else [])
xirr = ['10.84%', '1.12%']

# ---------------- formatting ----------------
def indian(n):
    s = str(n); neg = s.startswith('-'); s = s.lstrip('-')
    ip, _, fp = s.partition('.')
    if len(ip) > 3:
        head, tail = ip[:-3], ip[-3:]
        head = re.sub(r'(\d)(?=(\d\d)+$)', r'\1,', head)
        ip = head + ',' + tail
    return ('-' if neg else '') + ip + ('.' + fp if fp else '')
def fmt(val, like):
    """format dummy val in the style of original token `like` (commas, decimals, parens)."""
    dec = len(like.split('.')[-1].rstrip(')%')) if '.' in like else 0
    v = D(val).quantize(D(1).scaleb(-dec), ROUND_HALF_UP) if dec else D(int(val))
    neg = v < 0; v = abs(v)
    s = indian(v) if (',' in like or LAYOUT == 'stacked') else str(v)
    if '%' in like: s += '%'
    return f'({s})' if neg else s
def ddmonyy(d): return f'{d.day:02d}-{MON[d.month-1]}-{d.year % 100:02d}'

NUM = re.compile(r'^\(?-?[\d,]*\d\.\d+\)?%?$')
DATE = re.compile(r'^\d\d-[A-Z][a-z]{2}-\d\d$')

# ---------------- read geometry ----------------
pdf = pdfplumber.open(SRC)
pages = []
for p in pdf.pages:
    ws = p.extract_words(extra_attrs=['size', 'fontname'])
    for w in ws: w['font'] = w['fontname'].split('+')[-1]
    rects = [dict(x0=r['x0'], top=r['top'], w=r['width'], h=r['height'],
                  fill=r.get('fill'), stroke=r.get('stroke'),
                  fc=r.get('non_stroking_color'), sc=r.get('stroking_color')) for r in p.rects]
    pages.append(dict(w=p.width, h=p.height, words=ws, rects=rects))

def group_lines(ws, tol=2.5):
    out = []
    for w in sorted(ws, key=lambda w: (w['top'], w['x0'])):
        if out and abs(out[-1][0]['top'] - w['top']) <= tol: out[-1].append(w)
        else: out.append([w])
    return [sorted(l, key=lambda w: w['x0']) for l in out]
def txt(l): return ' '.join(w['text'] for w in l)

# flatten all lines with page index
all_lines = [(pi, l) for pi, pg in enumerate(pages) for l in group_lines(pg['words'])]
def find(pred, start=0):
    for i in range(start, len(all_lines)):
        if pred(txt(all_lines[i][1])): return i
    raise SystemExit('anchor not found')
i_pref = find(lambda t: 'Current Scheme Preference' in t)
i_sum = find(lambda t: t.strip() == 'Investment Summary', i_pref)
i_sws = find(lambda t: 'Scheme Wise Summary' in t, i_sum)
i_chg = find(lambda t: t.startswith('Changes made'), i_sws)
i_con = find(lambda t: t.startswith('Contribution/Redemption'), i_chg)
i_txn = find(lambda t: t.strip() == 'Transaction Details', i_con)
i_note = find(lambda t: t.startswith('Note:'), i_txn)

secret = set()          # original personal tokens, for the leak check
def sec(tok):
    t = tok.strip('(),%:')
    if len(re.sub(r'\D', '', t)) >= 3 or (t.isalpha() and len(t) >= 3): secret.add(t.lower())

out_words = []          # (page, x, top, font, size, text)
def emit(pi, w, text, x=None):
    out_words.append((pi, x if x is not None else w['x0'], w['top'], w['font'], w['size'], text))
def keep(pi, w): emit(pi, w, w['text'])

# ---------------- header (page 0, before preference) ----------------
KEEP_HDR = set('''NPS TRANSACTION STATEMENT Statement Generation Date: Scan the QR code to access PRIDE disha
Calculator Transaction for Tier I II Account PRAN Registration Date Subscriber Name Status Address Virtual
Not Activated ACTIVE INACTIVE Mobile Number POP-SP Reg No Email ID IRA Compliant Non-Compliant eNPS - Online
POP Nominee Name/s Percentage Tax Saver To Active Inactive Applicable the'''.split())
FULLMON = ['January','February','March','April','May','June','July','August','September','October','November','December']
mode = None; addr_i = 0; nominee_next = False
for li in range(0, i_pref):
    pi, l = all_lines[li]; t = txt(l)
    left = [w for w in l if w['x0'] < 296]; right = [w for w in l if w['x0'] >= 296]
    # top banner lines
    if re.search(r'\b(19|20)\d\d\b', t) and ('To' in t.split() or 'Generation' in t):
        mon_i = 0
        for w in l:
            if w['text'] in MON or (w['text'].rstrip(',') in MON):
                pass
        seq = [start, end] if 'To' in t.split() else [gen]
        k = 0
        for j, w in enumerate(l):
            x = w['text']
            if x in MON:
                d = seq[min(k, len(seq)-1)]; emit(pi, w, MON[d.month-1])
            elif re.fullmatch(r'\d{1,2},', x):
                d = seq[min(k, len(seq)-1)]; emit(pi, w, f'{d.day:02d},')
            elif re.fullmatch(r'\d{4}', x):
                d = seq[min(k, len(seq)-1)]; emit(pi, w, str(d.year)); k += 1
            elif re.fullmatch(r'\d\d:\d\d', x): emit(pi, w, gen.strftime('%I:%M'))
            elif x in ('AM', 'PM'): emit(pi, w, gen.strftime('%p'))
            elif x.startswith('Date:'): emit(pi, w, 'Date:' + FULLMON[gen.month-1])
            elif x in KEEP_HDR: keep(pi, w)
            else: sec(x)
        continue
    if nominee_next and l and l[0]['x0'] > 300:
        for w in l: sec(w['text'])
        emit(pi, l[0], DUMMY['nominee'])
        pct = [w for w in l if w['text'].endswith('%')]
        if pct: emit(pi, pct[-1], DUMMY['nominee_pct'])
        nominee_next = False; continue
    if 'Nominee' in t: nominee_next = True
    # left column
    lab = left[0]['text'] if left else ''
    if lab in ('PRAN', 'Subscriber', 'Address', 'Mobile', 'Email', 'IRA'): mode = lab
    elif left and left[0]['x0'] < 60: mode = 'other'
    vals = [w for w in left if w['x0'] >= 138]
    labels = [w for w in left if w['x0'] < 138]
    for w in labels:
        if w['text'] in KEEP_HDR: keep(pi, w)
        else: sec(w['text'])
    if vals:
        v0 = vals[0]
        for w in vals: sec(w['text'])
        if mode == 'PRAN': emit(pi, v0, DUMMY['pran'])
        elif mode == 'Subscriber': emit(pi, v0, DUMMY['name'])
        elif mode == 'Address':
            if addr_i < len(DUMMY['addr']): emit(pi, v0, DUMMY['addr'][addr_i])
            addr_i += 1
        elif mode == 'Mobile': emit(pi, v0, DUMMY['mobile'])
        elif mode == 'Email': emit(pi, v0, DUMMY['email'])
        elif mode == 'IRA': [keep(pi, w) if w['text'] in KEEP_HDR else None for w in vals]
        elif mode in (None, 'other'): [keep(pi, w) if w['text'] in KEEP_HDR else None for w in vals]
    # right column
    rl = [w for w in right if w['x0'] < 375]; rv = [w for w in right if w['x0'] >= 375]
    rlab = ' '.join(w['text'] for w in rl)
    for w in rl:
        if w['text'] in KEEP_HDR: keep(pi, w)
    if rv:
        if 'Registration' in rlab:
            sec(rv[0]['text']); emit(pi, rv[0], DUMMY['reg'])
        elif 'Reg' in rlab:
            sec(rv[0]['text']); emit(pi, rv[0], DUMMY['reg_no'])
        elif all(w['text'] in KEEP_HDR for w in rv):
            for w in rv: keep(pi, w)
        elif 'Nominee' in t or 'Percentage' in t:
            for w in rv: keep(pi, w) if w['text'] in KEEP_HDR else None
        else:
            # POP address lines and anything else: public POP office text is replaced too
            for w in rv: sec(w['text'])
            if 'Address' in rlab: emit(pi, rv[0], 'PROTEAN EGOV TECHNOLOGIES, MUMBAI')

# ---------------- body sections ----------------
def rest_dates(pi, w, default):
    x = w['text']
    if DATE.match(x): emit(pi, w, ddmonyy(default)); return True
    if x in MON: emit(pi, w, MON[default.month-1]); return True
    if re.fullmatch(r'\d{1,2},', x): emit(pi, w, f'{default.day:02d},'); return True
    if re.fullmatch(r'(19|20)\d\d', x): emit(pi, w, str(default.year)); return True
    return False

# preference: % per scheme in row order
k = 0
for li in range(i_pref, i_sum):
    pi, l = all_lines[li]
    for w in l:
        if w['text'].endswith('%') and NUM.match(w['text']):
            sec(w['text']); emit(pi, w, fmt(pref[SCHEMES[k]], w['text'])); k += 1
        elif re.fullmatch(r'\(?\d\dE', w['text']) or re.fullmatch(r'\d\d', w['text']) and 'Cycle' in txt(l):
            keep(pi, w)       # "Life Cycle 75 - High (75E / 35 Y)" is product text
        else: keep(pi, w)
assert k == 3, ('pref', k)

# summary
nums, pct = [], []
for li in range(i_sum, i_sws):
    pi, l = all_lines[li]
    value_row = any(NUM.match(y['text']) and not y['text'].endswith('%') for y in l)
    for w in l:
        x = w['text']
        if NUM.match(x) and x.endswith('%'): pct.append((pi, w))
        elif value_row and (NUM.match(x) or re.fullmatch(r'\d+', x)): nums.append((pi, w))
        elif rest_dates(pi, w, end): sec(x)
        else: keep(pi, w)
nums.sort(key=lambda p: (round(p[1]['top']), p[1]['x0']))
assert len(nums) == len(summary_vals), ('summary', len(nums), len(summary_vals))
for (pi, w), v in zip(nums, summary_vals): sec(w['text']); emit(pi, w, fmt(v, w['text']))
for j, (pi, w) in enumerate(pct): sec(w['text']); emit(pi, w, xirr[min(j, 1)])

# scheme-wise summary
rows = []
for li in range(i_sws, i_chg):
    pi, l = all_lines[li]
    ns = [w for w in l if NUM.match(w['text'])]
    for w in l:
        if w in ns: continue
        if not rest_dates(pi, w, end): keep(pi, w)
        else: sec(w['text'])
    if ns: rows.append((pi, ns))
if LAYOUT == 'wide':        # rows: values, units, navs ; columns E C G
    assert len(rows) == 3, ('sws', len(rows))
    for (pi, ns), src in zip(rows, (value, close_u, nav_e)):
        for w, s in zip(ns, SCHEMES): sec(w['text']); emit(pi, w, fmt(src[s], w['text']))
else:                       # one row per scheme: value, units, nav
    assert len(rows) == 3, ('sws', len(rows))
    for (pi, ns), s in zip(rows, SCHEMES):
        for w, v in zip(ns, (value[s], close_u[s], nav_e[s])): sec(w['text']); emit(pi, w, fmt(v, w['text']))

# changes section: keep text
for li in range(i_chg, i_con):
    pi, l = all_lines[li]
    for w in l: keep(pi, w)

# contribution details
contrib = dict(lines).get('contrib')
for li in range(i_con, i_txn):
    pi, l = all_lines[li]; t = txt(l)
    if t.startswith('No change affected') and contrib and LAYOUT == 'stacked':
        tot = sum(contrib[s][0] for s in SCHEMES)
        y = l[0]
        for x, s in ((45, ddmonyy(txn_d)), (100, 'By Voluntary Contributions'), (288, 'eNPS - Online'),
                     (409, fmt(tot, '9,999.99')), (470, '0.00'), (525, fmt(tot, '9,999.99'))):
            emit(pi, y, s, x=x)
        continue
    ns = [w for w in l if NUM.match(w['text'])]
    if ns:
        tot = sum(contrib[s][0] for s in SCHEMES) if contrib else D(0)
        for w, v in zip(ns, (tot, D('0.00'), tot)): sec(w['text']); emit(pi, w, fmt(v, w['text']))
    for w in l:
        if w in ns: continue
        if DATE.match(w['text']): sec(w['text']); emit(pi, w, ddmonyy(txn_d))
        else: keep(pi, w)

# transaction details
kind_of = lambda t: 'open' if 'Opening' in t else 'close' if 'Closing' in t else \
    'bill' if 'Billing' in t else 'contrib' if 'Voluntary' in t else 'rebal' if 'Rebalancing' in t else None
date_for = {'open': start, 'close': end, 'bill': bill_d, 'contrib': txn_d, 'rebal': txn_d}
by_kind = dict(lines)
tx = all_lines[i_txn:i_note]
if LAYOUT == 'stacked':
    scheme = None; pending_letter = False
    for pi, l in tx:
        t = txt(l); kd = kind_of(t)
        if 'SCHEME' in t:
            toks = [w['text'] for w in l]; j = toks.index('SCHEME')
            if j + 1 < len(toks): scheme = toks[j + 1]
            else: pending_letter = True
        elif pending_letter and l and l[0]['text'] in SCHEMES:
            scheme = l[0]['text']; pending_letter = False
        ns = [w for w in l if NUM.match(w['text'])]
        if VARIANT == 'contribution' and kd == 'rebal': kd = 'contrib'
        for w in l:
            if w in ns: continue
            if DATE.match(w['text']): sec(w['text']); emit(pi, w, ddmonyy(date_for[kd])); continue
            if VARIANT == 'contribution' and w['text'] in ('On', 'account', 'of', 'Rebalancing', 'Assets', 'as', 'per', 'Regulatory') and 'Rebalancing' in t:
                if w['text'] == 'On': emit(pi, w, 'By Voluntary Contributions')
                continue
            if VARIANT == 'contribution' and t.strip() == 'Requirement': continue
            keep(pi, w)
        if not ns: continue
        if kd == 'open': vals = [open_u[scheme]]
        elif kd == 'close': vals = [close_u[scheme]]
        else: a, n, u = by_kind[kd][scheme]; vals = [a, n, u]
        assert len(ns) == len(vals), (kd, scheme, len(ns))
        for w, v in zip(ns, vals): sec(w['text']); emit(pi, w, fmt(v, w['text']))
else:
    # wide: numeric tokens belong to the nearest label row at or above (top + 2)
    label_rows = []
    for pi, l in tx:
        kd = kind_of(txt(l))
        if kd: label_rows.append((pi, l[0]['top'], kd))
    groups = {}
    for pi, l in tx:
        t = txt(l)
        for w in l:
            x = w['text']
            if NUM.match(x):
                cands = [r for r in label_rows if r[0] == pi and r[1] <= w['top'] + 2.5]
                r = max(cands, key=lambda r: r[1]); groups.setdefault(r, []).append((pi, w))
            elif DATE.match(x):
                kd = kind_of(t); sec(x); emit(pi, w, ddmonyy(date_for[kd]))
            elif re.fullmatch(r'Q\d,', x): emit(pi, w, 'Q3,')
            elif re.fullmatch(r'\d{4}-\d{4}', x): emit(pi, w, '2024-2025')
            else: keep(pi, w)
    col = lambda x: 'X' if x < 240 else 'E' if x < 350 else 'C' if x < 455 else 'G'
    for (pi, top, kd), ws in groups.items():
        if kd in ('open', 'close'):
            src = open_u if kd == 'open' else close_u
            for p2, w in ws: sec(w['text']); emit(p2, w, fmt(src[col(w['x0'])], w['text']))
            continue
        subrows = group_lines([w for _, w in ws])
        assert len(subrows) == 3, (kd, len(subrows))
        data = by_kind[kd]
        for idx, row in enumerate(subrows):
            for w in row:
                c = col(w['x0']); sec(w['text'])
                if c == 'X': v = sum(data[s][0] for s in SCHEMES)     # charge column total
                else: v = data[c][idx if idx < 1 else (2 if idx == 1 else 1)]
                emit(pi, w, fmt(v, w['text']))

# boilerplate after Note: keep verbatim (public template text)
for pi, l in all_lines[i_note:]:
    for w in l: keep(pi, w)

# ---------------- render ----------------
BASE14 = {'Helvetica', 'Helvetica-Bold', 'Helvetica-Oblique', 'Helvetica-BoldOblique', 'Times-Roman',
          'Times-Bold', 'Times-Italic', 'Times-BoldItalic', 'Courier', 'Courier-Bold'}
def col3(c):
    if c is None: return None
    c = tuple(c) if isinstance(c, (list, tuple)) else (c,)
    if len(c) == 1: return (c[0],) * 3
    if len(c) == 4: k_ = c[3]; return tuple((1 - min(1, v + k_)) for v in c[:3])
    return c[:3]
cv = canvas.Canvas(OUT, pagesize=(pages[0]['w'], pages[0]['h']), invariant=1)
cv.setTitle('NPS Transaction Statement'); cv.setAuthor(''); cv.setCreator(''); cv.setProducer('')
for pi, pg in enumerate(pages):
    H = pg['h']
    for r in pg['rects']:
        fc, sc = col3(r['fc']), col3(r['sc'])
        if r['fill'] and fc: cv.setFillColorRGB(*fc)
        if r['stroke'] and sc: cv.setStrokeColorRGB(*sc)
        cv.setLineWidth(0.5)
        cv.rect(r['x0'], H - r['top'] - r['h'], r['w'], r['h'], fill=1 if (r['fill'] and fc) else 0,
                stroke=1 if r['stroke'] else 0)
    cv.setFillColorRGB(0, 0, 0)
    for (p2, x, top, font, size, text) in out_words:
        if p2 != pi: continue
        f = font if font in BASE14 else ('Helvetica-Bold' if 'Bold' in font else 'Helvetica')
        cv.setFont(f, size)
        cv.drawString(x, H - top - size * 0.8, text)
    cv.showPage()
cv.save()

# ---------------- leak check ----------------
plain = subprocess.run(['pdftotext', '-layout', OUT, '-'], capture_output=True, text=True).stdout
low = plain.lower(); norm = re.sub(r'[,()]', '', low)
out_tokens = {x.strip('%:') for x in norm.split()}
dummy_text = ('protean egov technologies mumbai ' + ' '.join(DUMMY['addr']) + ' ' + DUMMY['name'] + ' ' + DUMMY['email'] + ' ' + DUMMY['nominee']).lower()
public = set(w.lower() for w in KEEP_HDR) | {'scheme', 'tier', 'direct', 'hdfc', 'pension', 'management', 'company',
         'limited', 'fund', 'trust', 'online', 'enps'} | {m.lower() for m in MON + FULLMON}
public |= {w['text'].lower().strip('(),.:') for _, l in all_lines[i_note:] for w in l}
secret = {s for s in secret if not re.fullmatch(r'(19|20)\d\d', s)}
leaks = []
for s in secret:
    s2 = re.sub(r'[,()]', '', s)
    if s in public or s2 in public: continue
    if s.isalpha():
        if re.search(r'\b' + re.escape(s) + r'\b', low) and s not in dummy_text: leaks.append('word')
    elif len(re.sub(r'\D', '', s2)) >= 4 and s2.strip('%') in out_tokens: leaks.append('num')
    elif re.search(r'\b\d\d-[a-z]{3}-\d\d\b', s) and s in low: leaks.append('date')
print('secret tokens checked', len(secret), 'leaks', len(leaks), sorted(set(leaks)))
if leaks and '--why' in sys.argv:
    for s in secret:
        s2 = re.sub(r'[,()]', '', s)
        if s in public or s2 in public: continue
        hit = (s.isalpha() and re.search(r'\b' + re.escape(s) + r'\b', low) and s not in dummy_text) or \
              (not s.isalpha() and len(re.sub(r'\D', '', s2)) >= 4 and s2.strip('%') in out_tokens)
        if hit:
            for ln in plain.splitlines():
                L = re.sub(r'[,()]', '', ln.lower())
                if (s in ln.lower()) or (s2 in L):
                    print('  LEAK len', len(s), 'type', 'word' if s.isalpha() else 'num', '| line:', re.sub(re.escape(s), '<LEAK>', ln.strip(), flags=re.I)[:160]); break
if leaks: sys.exit(2)
r = subprocess.run(['qpdf', '--encrypt', PW, 'owner-' + PW[::-1] + 'x9', '256', '--', OUT, OUT + '.enc'])
subprocess.run(['mv', OUT + '.enc', OUT])
print('wrote', OUT)
