import re, html

def inner(path):
    s = open(path).read()
    m = re.search(r'<x-dc>(.*)</x-dc>', s, re.S)
    body = m.group(1)
    body = re.sub(r'<helmet>.*?</helmet>', '', body, flags=re.S)
    return body.strip()

boards = [
    ("Receipt", "Main.dc.html", True,
     "The metaphor is the product — this app collects receipts you never asked for. Mono numerals, dot leaders, a torn edge under the summary.",
     "Strongest identity of the four, but ornament is what wears thin first. Mono stays on numbers and metadata only; body copy never gets it."),
    ("Quiet utility", "Quiet.dc.html", False,
     "One typeface, near-monochrome, hairline rules, information first. Sits closest to the spec’s honesty stance.",
     "Safe. Reads as a well-built tool rather than something you’d mention to a friend."),
    ("Warung warmth", "Warung.dc.html", False,
     "Malay-first copy, kopi and pandan palette, rounded cards, a friendly display face.",
     "Most local and most likeable. Hardest to keep credible once real money is on screen."),
    ("Data-first dark", "Dark.dc.html", False,
     "Tabular numerals throughout, category bars promoted onto home instead of hidden behind a charts tab.",
     "Best for the numbers. Dark-only is a big commitment for an app checked in daylight."),
]

rows = [
    ("Typeface", ["Instrument Serif, Karla, Plex Mono", "Public Sans alone", "Baloo 2, Mulish", "Plex Sans, Plex Mono"]),
    ("Ground", ["Warm paper", "Warm off-white", "Cream", "Near-black"]),
    ("Accent", ["Stamp red", "Deep pine", "Pandan and ochre", "Mint"]),
    ("Density", ["Dense, dot leaders", "Dense, hairline rows", "Roomy cards", "Dense, bar-forward"]),
    ("Copy language", ["English", "English", "Malay first", "English"]),
    ("Numerals", ["Mono tabular", "Sans tabular", "Sans bold", "Mono tabular"]),
    ("Excluded transfer", ["Struck through, stamped label", "Greyed and tinted row", "Faded card, Malay label", "Dimmed, mono label"]),
]

FONTS = ("https://fonts.googleapis.com/css2"
 "?family=Newsreader:ital,opsz,wght@0,6..72,400;0,6..72,500;1,6..72,400"
 "&family=Archivo:wght@400;500;600"
 "&family=Instrument+Serif"
 "&family=Karla:wght@400;500;700"
 "&family=IBM+Plex+Mono:wght@400;500;600"
 "&family=IBM+Plex+Sans:wght@400;500;600"
 "&family=Public+Sans:wght@400;500;600"
 "&family=Baloo+2:wght@500;600;700"
 "&family=Mulish:wght@400;500;600;700"
 "&display=swap")

css = """
:root{
  --ground:#f2f2ef; --panel:#fbfbf9; --ink:#1a1b1e; --ink-2:#5c5e64; --ink-3:#8a8c92;
  --line:rgba(26,27,30,0.13); --line-soft:rgba(26,27,30,0.07);
  --accent:#3c5a72; --accent-soft:rgba(60,90,114,0.10);
  --shadow:rgba(26,27,30,0.10);
}
@media (prefers-color-scheme: dark){
  :root:not([data-theme="light"]){
    --ground:#131417; --panel:#1a1c20; --ink:#ececeb; --ink-2:#a7a9af; --ink-3:#7c7e85;
    --line:rgba(236,236,235,0.14); --line-soft:rgba(236,236,235,0.07);
    --accent:#8fb4ce; --accent-soft:rgba(143,180,206,0.13);
    --shadow:rgba(0,0,0,0.45);
  }
}
:root[data-theme="dark"]{
  --ground:#131417; --panel:#1a1c20; --ink:#ececeb; --ink-2:#a7a9af; --ink-3:#7c7e85;
  --line:rgba(236,236,235,0.14); --line-soft:rgba(236,236,235,0.07);
  --accent:#8fb4ce; --accent-soft:rgba(143,180,206,0.13);
  --shadow:rgba(0,0,0,0.45);
}
*{box-sizing:border-box}
body{
  margin:0; background:var(--ground); color:var(--ink);
  font-family:Archivo,system-ui,-apple-system,sans-serif;
  font-size:16px; line-height:1.65; -webkit-font-smoothing:antialiased;
}
.wrap{max-width:1180px; margin:0 auto; padding:56px 28px 80px}
.eyebrow{
  font-size:11.5px; letter-spacing:0.15em; text-transform:uppercase;
  color:var(--ink-3); margin:0 0 18px;
}
h1{
  font-family:Newsreader,Georgia,serif; font-weight:400; font-size:clamp(34px,5vw,52px);
  line-height:1.08; letter-spacing:-0.015em; margin:0 0 20px; text-wrap:balance;
}
.stand{max-width:62ch; color:var(--ink-2); font-size:17px; margin:0 0 10px}
.stand strong{color:var(--ink); font-weight:500}
.controls{display:flex; align-items:center; gap:14px; margin:34px 0 26px; flex-wrap:wrap}
button.sizer{
  font-family:Archivo,sans-serif; font-size:13px; font-weight:500; color:var(--ink);
  background:var(--panel); border:1px solid var(--line); border-radius:7px;
  padding:9px 15px; cursor:pointer; min-height:40px;
}
button.sizer:hover{border-color:var(--accent); color:var(--accent)}
button.sizer:focus-visible{outline:2px solid var(--accent); outline-offset:2px}
.hint{font-size:13px; color:var(--ink-3)}
.strip{
  display:flex; gap:34px; overflow-x:auto; padding:6px 6px 26px;
  scrollbar-width:thin;
}
.col{display:flex; flex-direction:column; gap:16px; flex:none; width:calc(390px * var(--s))}
.cap{display:flex; flex-direction:column; gap:8px; min-height:200px}
.cap h2{
  font-family:Newsreader,Georgia,serif; font-weight:500; font-size:23px;
  line-height:1.15; margin:0; letter-spacing:-0.01em;
}
.pill{
  align-self:flex-start; font-size:10.5px; letter-spacing:0.12em; text-transform:uppercase;
  color:var(--accent); background:var(--accent-soft); border-radius:4px; padding:4px 9px;
  font-weight:600;
}
.cap p{margin:0; font-size:14px; line-height:1.6; color:var(--ink-2)}
.cap .trade{color:var(--ink-3); font-size:13.5px}
.cap .trade b{
  color:var(--ink-3); font-weight:600; letter-spacing:0.07em; text-transform:uppercase;
  font-size:10.5px; display:block; margin-bottom:3px;
}
.frame{
  width:calc(390px * var(--s)); height:calc(844px * var(--s));
  border:1px solid var(--line); border-radius:calc(26px * var(--s));
  overflow:hidden; box-shadow:0 14px 34px -18px var(--shadow); flex:none;
}
.frame > *{transform:scale(var(--s)); transform-origin:top left}
.tablewrap{overflow-x:auto; margin:52px 0 0; border-top:1px solid var(--line)}
table{border-collapse:collapse; width:100%; min-width:760px; font-size:14px}
caption{
  caption-side:top; text-align:left; padding:26px 0 16px;
  font-family:Newsreader,Georgia,serif; font-size:22px; font-weight:500; color:var(--ink);
}
th,td{text-align:left; padding:13px 16px 13px 0; border-bottom:1px solid var(--line-soft); vertical-align:top}
thead th{
  font-size:11px; letter-spacing:0.1em; text-transform:uppercase; color:var(--ink-3);
  font-weight:600; border-bottom:1px solid var(--line); padding-top:0;
}
tbody th{font-weight:500; color:var(--ink-2); width:150px; font-size:13.5px}
tbody td{color:var(--ink)}
.close{margin:52px 0 0; border-top:1px solid var(--line); padding-top:34px; max-width:64ch}
.close h2{font-family:Newsreader,Georgia,serif; font-weight:500; font-size:26px; margin:0 0 14px; letter-spacing:-0.01em}
.close p{margin:0 0 14px; color:var(--ink-2)}
.close p strong{color:var(--ink); font-weight:500}
@media (prefers-reduced-motion:no-preference){
  .col,.frame,.frame>*{transition:width .28s ease,height .28s ease,transform .28s ease,border-radius .28s ease}
}
"""

def col(name, path, rec, motive, trade):
    pill = '<span class="pill">Recommended</span>' if rec else ''
    return f'''      <div class="col">
        <div class="cap">
          {pill}
          <h2>{name}</h2>
          <p>{motive}</p>
          <p class="trade"><b>Trade-off</b>{trade}</p>
        </div>
        <div class="frame">{inner(path)}</div>
      </div>'''

cols = "\n".join(col(*b) for b in boards)

thead = "".join(f"<th scope='col'>{b[0]}</th>" for b in boards)
tbody = "\n".join(
    "<tr><th scope='row'>" + label + "</th>" + "".join(f"<td>{v}</td>" for v in vals) + "</tr>"
    for label, vals in rows
)

page = f'''<title>Pinged Direction Study</title>
<link rel="stylesheet" href="{FONTS}">
<style>{css}</style>
<div class="wrap">
  <p class="eyebrow">Pinged &middot; direction study</p>
  <h1>Four ways this app could look</h1>
  <p class="stand">Every artboard shows the <strong>same screen</strong> so the comparison is fair: the transaction list home for September, carrying the states that actually decide the design &mdash; a row that needs a category, an excluded Touch &rsquo;n Go reload, day subtotals, and the review-inbox badge.</p>
  <p class="stand">Pick one and the remaining five screens get built in it: onboarding and permissions, the capture-source allow-list, the review inbox, charts, and manual cash entry.</p>

  <div class="controls">
    <button class="sizer" id="sizer" type="button">View at actual size</button>
    <span class="hint">Shown at 72% so more than one fits at a time. Frames are 390 &times; 844.</span>
  </div>

  <div class="strip" id="strip" style="--s:0.72">
{cols}
  </div>

  <div class="tablewrap">
    <table>
      <caption>What actually differs</caption>
      <thead><tr><th scope="col">&nbsp;</th>{thead}</tr></thead>
      <tbody>
{tbody}
      </tbody>
    </table>
  </div>

  <div class="close">
    <h2>The recommendation</h2>
    <p><strong>Receipt.</strong> The app&rsquo;s whole premise is that it quietly collects proof of spending you never wrote down, and that is what a receipt is. It is also the only one of the four that nobody would mistake for another fintech app.</p>
    <p>The honest risk is ornament fatigue: dot leaders and a torn edge are charming on day one and can grate by week three. The mitigation is already in the mockup &mdash; mono type carries numerals and metadata only, never body copy, and the ornament appears once per screen rather than on every row.</p>
    <p>If you want the safest daily-use choice instead, take <strong>Quiet utility</strong>. If you want the one people would actually talk about, <strong>Warung warmth</strong> is the risk worth considering &mdash; it just needs discipline to stay credible around money.</p>
  </div>
</div>
<script>
(function(){{
  var b = document.getElementById('sizer'), s = document.getElementById('strip'), full = false;
  b.addEventListener('click', function(){{
    full = !full;
    s.style.setProperty('--s', full ? '1' : '0.72');
    b.textContent = full ? 'Fit more on screen' : 'View at actual size';
  }});
}})();
</script>
'''

open('direction-study.html','w').write(page)
print("written", len(page), "bytes")
