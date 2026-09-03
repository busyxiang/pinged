import re

def inner(path):
    s = open(path).read()
    m = re.search(r'<x-dc>(.*)</x-dc>', s, re.S)
    return re.sub(r'<helmet>.*?</helmet>', '', m.group(1), flags=re.S).strip()

groups = [
 ("First run", "Four screens before a single ringgit is recorded. This stretch decides whether anyone still has the app installed next week.", [
  ("Onboarding", "Onboarding.dc.html", "Step 1 of 3",
   "Leads with the three things people actually worry about &mdash; including the one most trackers quietly omit: nothing from before you switch it on can ever be recovered."),
  ("Battery", "Battery.dc.html", "Step 2 of 3",
   "The screen most likely to lose a user. Manufacturer detected, literal menu path printed. The honest part: battery state is checked by Pinged and stamped confirmed, while MIUI autostart cannot be read at all &mdash; so that one asks you, and says why it has to."),
  ("Capture sources", "Sources.dc.html", "Step 3 of 3, and settings later",
   "WhatsApp reads &ldquo;3,204 seen &middot; not one word stored&rdquo;. That row is the two-stage allow-list made concrete, and the screen to point at when anyone asks what this app can see."),
  ("Day one", "Empty.dc.html", "The empty state",
   "Pure anxiety if handled badly, so it proves the plumbing instead of apologising: a live listening dot, the four watched apps with em-dashes where amounts will land, and a blank stub saying nothing is missing."),
 ]),
 ("Every day", "The app in ordinary use, a month in. Scenario pinned to Wednesday 30 September 2026 so every figure agrees with every other.", [
  ("Spending", "Main.dc.html", "Home",
   "Day subtotals, a row waiting for a category, and an excluded Touch &rsquo;n Go reload struck through rather than deleted &mdash; you still get to see that the money moved."),
  ("Needs a look", "Inbox.dc.html", "Review inbox",
   "All four review reasons at once. Every card carries the notification word for word, so the app is never more confident than the evidence it holds."),
  ("Detail", "Detail.dc.html", "One transaction",
   "The audit trail, visible: the raw notification, the acquirer string it came from (TNG*99SPEEDMART), and which rule read it. &ldquo;Not edited&rdquo; means nobody has touched what the machine decided."),
  ("Cash", "Cash.dc.html", "Manual entry",
   "The only typing in the whole app. Keypad open and focused, category chips ordered by what you use most, merchant optional."),
 ]),
 ("Looking back", "Two ways of asking where the money went. Neither needs a charting library.", [
  ("September", "Charts.dc.html", "Charts",
   "Category bars on a single-hue ramp and a merchant ranking. The comparison against August appears only because the month is complete."),
  ("Search", "Search.dc.html", "Search and filter",
   "Any query becomes its own total. Searching &ldquo;grab&rdquo; answers the question a category total buries: eighteen rides, RM312.40, RM17.36 each &mdash; more than a month of petrol."),
 ]),
 ("When it breaks", "The failure this whole category gets wrong, plus the small print.", [
  ("Capture stopped", "Stopped.dc.html", "The banner that keeps step 2 honest",
   "Xiaomi killed the listener on 27 September. The total goes grey and is labelled do-not-trust, and the missing days are drawn as a dashed hole reading &ldquo;not because you did not spend&rdquo;. The app admits the gap rather than serving a confident wrong number."),
  ("Settings", "Settings.dc.html", "Changed, third tab",
   "A tab, not a drawer. In the first weeks this is where you live &mdash; adding banks, checking capture is alive, teaching merchants &mdash; so capture sits at the top and export and delete at the bottom. Named in plain words: &ldquo;merchants you have taught Pinged&rdquo;, &ldquo;notifications Pinged could not read&rdquo;. The footer states the privacy claim as fact, because a missing permission enforces it."),
 ]),
 ("Sheets, pickers and lists", "The layer behind the twelve. I called these derivative before drawing them; three turned out to carry real decisions.", [
  ("What was this?", "Chooser.dc.html", "Assigning a category",
   "The most repeated action in the app. It shows the acquirer string it is deciding about, and the rule it is about to write &mdash; on by default, with the ten past transactions it will retroactively fix. Teaching Pinged is visible, not a side effect."),
  ("Edit a category", "Category.dc.html", "Name and icon",
   "Name and icon in one sheet, because a renamed category carrying a stale icon is worse than no icon. The rename blast radius is stated before you commit, and Makan cannot be deleted while 412 transactions point at it &mdash; the sheet says so rather than greying out a button and explaining nothing."),
  ("Jump to", "Months.dc.html", "Month picker",
   "Every month with its total, and May flagged &ldquo;3 days not captured&rdquo; so a low month is never mistaken for a frugal one. The footer names your install date, because that is where history genuinely stops."),
  ("Export", "Export.dc.html", "Two real decisions",
   "Whether transfers and pending items are included was an assumption until this screen forced it into the open. Both default to out, since neither counts toward a total."),
  ("Delete everything", "Wipe.dc.html", "The destructive one",
   "Counts what dies, states plainly that no copy exists anywhere, offers an export first, and requires typing DELETE. Harsher than a normal confirmation because there is no cloud to restore from and Android will not replay the past."),
  ("Things you taught Pinged", "Rules.dc.html", "Changed, now three lists in one",
   "Merchants, message rules and ignored messages are all &ldquo;things you taught&rdquo;, so they share one screen rather than adding two more settings rows. Bundled merchants stay unlisted, so the list is only ever yours."),
  ("Teach Pinged to read this", "Teach.dc.html", "New, and the reason the rest changed",
   "No regex field, ever &mdash; a bad pattern does not fail loudly, it invents transactions. So it asks for the two facts it cannot infer: tap the amount, tap the merchant. Every other word stays literal and every other number generalises, so the rule matches this message shape and nothing else."),
  ("A pack of new rules", "PackImport.dc.html", "New, import with a dry run",
   "The payoff for never deleting a raw capture: a pack is tried against your own notifications before you accept it. Thirty-one more would be read; zero of the 112 you already have would change. That second number should always be zero, and it is shown whether or not it is."),
  ("Could not read", "Unread.dc.html", "Changed, the authoring loop",
   "Its primary action was &ldquo;copy as test case&rdquo;, which is a developer loop, not a user one. It now leads with teaching. The third card still inverts the pair, because an FD maturity notice is genuinely never spending &mdash; and copy-as-fixture survives as the small icon by each timestamp."),
 ]),
]

tokens = [
 ("Paper", "#fbf6ea", "Page ground"),
 ("Card", "#efe7d8", "Second surface, below the torn edge"),
 ("Ink", "#1a1714", "Primary text, heaviest chart bar"),
 ("Muted", "#6b6459", "Secondary text, outlined controls"),
 ("Faint", "#8a8175", "Mono labels, counts, meta"),
 ("Rule", "#cfc4ae", "Dotted separators"),
 ("Border", "#ded5c4", "Card and control edges"),
 ("Stamp", "#a03826", "Actions, review reasons, and anything wrong"),
]
ramp = ["#1a1714", "#3d372f", "#5c554a", "#7a7264", "#98907f", "#b6ad99"]
typeroles = [
 ("Instrument Serif", "Display", "Totals and screen titles. Nothing else."),
 ("Karla", "Body", "All copy, merchant names, buttons. Weights 400 and 500."),
 ("IBM Plex Mono", "Utility", "Numerals, uppercase labels, raw notification text."),
]
rules = [
 "Mono never carries body copy &mdash; only numbers, labels and quoted notification text.",
 "One ornament per screen. The torn edge marks a summary-to-detail break; the rotated stamp marks a verified fact.",
 "2px radii throughout. Pills and rounded cards belong to a different direction.",
 "Stamp red means &ldquo;this needs you&rdquo; &mdash; actions, review reasons, and broken capture. It is never decoration.",
 "Every touch target is at least 44px tall, including keypad keys and toggles.",
 "Three tabs: Spending, Charts, Settings. Cash stays a button on the list because it is a task, not a place. Everything pushed &mdash; capture sources, detail, search, the inbox &mdash; carries a back arrow.",
 "A number the app is unsure of goes grey and gets labelled. It never renders as confident black type.",
]

FONTS = ("https://fonts.googleapis.com/css2"
 "?family=Newsreader:ital,opsz,wght@0,6..72,400;0,6..72,500"
 "&family=Archivo:wght@400;500;600"
 "&family=Instrument+Serif&family=Karla:wght@400;500;700"
 "&family=IBM+Plex+Mono:wght@400;500&display=swap")

css = """
:root{--ground:#f2f2ef;--panel:#fbfbf9;--ink:#1a1b1e;--ink-2:#5c5e64;--ink-3:#8a8c92;
 --line:rgba(26,27,30,0.13);--line-soft:rgba(26,27,30,0.07);--accent:#3c5a72;
 --accent-soft:rgba(60,90,114,0.10);--shadow:rgba(26,27,30,0.10);}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){
 --ground:#131417;--panel:#1a1c20;--ink:#ececeb;--ink-2:#a7a9af;--ink-3:#7c7e85;
 --line:rgba(236,236,235,0.14);--line-soft:rgba(236,236,235,0.07);--accent:#8fb4ce;
 --accent-soft:rgba(143,180,206,0.13);--shadow:rgba(0,0,0,0.45);}}
:root[data-theme="dark"]{--ground:#131417;--panel:#1a1c20;--ink:#ececeb;--ink-2:#a7a9af;
 --ink-3:#7c7e85;--line:rgba(236,236,235,0.14);--line-soft:rgba(236,236,235,0.07);
 --accent:#8fb4ce;--accent-soft:rgba(143,180,206,0.13);--shadow:rgba(0,0,0,0.45);}
*{box-sizing:border-box}
body{margin:0;background:var(--ground);color:var(--ink);
 font-family:Archivo,system-ui,-apple-system,sans-serif;font-size:16px;line-height:1.65;
 -webkit-font-smoothing:antialiased;}
.wrap{max-width:1240px;margin:0 auto;padding:56px 28px 84px}
.eyebrow{font-size:11.5px;letter-spacing:0.15em;text-transform:uppercase;color:var(--ink-3);margin:0 0 18px}
h1{font-family:Newsreader,Georgia,serif;font-weight:400;font-size:clamp(34px,5vw,52px);
 line-height:1.08;letter-spacing:-0.015em;margin:0 0 20px;text-wrap:balance}
h2.sec{font-family:Newsreader,Georgia,serif;font-weight:500;font-size:26px;letter-spacing:-0.01em;margin:0 0 12px}
.stand{max-width:62ch;color:var(--ink-2);font-size:17px;margin:0 0 10px}
.stand strong{color:var(--ink);font-weight:500}
.sysgrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:34px;margin-top:24px}
.sw{display:flex;align-items:center;gap:12px;padding:9px 0;border-bottom:1px solid var(--line-soft)}
.chip{width:26px;height:26px;border-radius:3px;border:1px solid var(--line);flex:none}
.sw .nm{font-size:13.5px;font-weight:500;width:64px;flex:none}
.sw .hex{font-family:'IBM Plex Mono',monospace;font-size:12px;color:var(--ink-3);width:74px;flex:none}
.sw .role{font-size:12.5px;color:var(--ink-2);line-height:1.45}
.ramp{display:flex;margin-top:14px;border:1px solid var(--line);border-radius:3px;overflow:hidden}
.ramp span{flex:1;height:26px}
.rampnote{font-size:12.5px;color:var(--ink-3);margin-top:9px}
.trow{padding:12px 0;border-bottom:1px solid var(--line-soft)}
.trow .role{font-size:10.5px;letter-spacing:0.12em;text-transform:uppercase;color:var(--ink-3);font-weight:600}
.trow .face{font-size:19px;margin-top:3px}
.trow .use{font-size:12.5px;color:var(--ink-2);margin-top:3px;line-height:1.5}
ul.rules{margin:20px 0 0;padding:0 0 0 20px;max-width:66ch}
ul.rules li{color:var(--ink-2);font-size:14.5px;margin-bottom:9px;line-height:1.6}
.controls{display:flex;align-items:center;gap:14px;margin:44px 0 8px;flex-wrap:wrap}
button.sizer{font-family:Archivo,sans-serif;font-size:13px;font-weight:500;color:var(--ink);
 background:var(--panel);border:1px solid var(--line);border-radius:7px;padding:9px 15px;
 cursor:pointer;min-height:40px}
button.sizer:hover{border-color:var(--accent);color:var(--accent)}
button.sizer:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.hint{font-size:13px;color:var(--ink-3)}
.grp{margin-top:52px;border-top:1px solid var(--line);padding-top:30px}
.grp .lede{max-width:60ch;color:var(--ink-2);font-size:15px;margin:0 0 28px}
.screens{--s:0.72;display:grid;grid-template-columns:repeat(auto-fit,calc(390px * var(--s)));
 justify-content:start;gap:40px 34px}
.col{display:flex;flex-direction:column;gap:15px;width:calc(390px * var(--s))}
.cap{display:flex;flex-direction:column;gap:7px}
.cap .step{font-size:10.5px;letter-spacing:0.12em;text-transform:uppercase;color:var(--accent);font-weight:600}
.cap h3{font-family:Newsreader,Georgia,serif;font-weight:500;font-size:22px;line-height:1.15;margin:0;letter-spacing:-0.01em}
.cap p{margin:0;font-size:13.5px;line-height:1.6;color:var(--ink-2)}
.frame{width:calc(390px * var(--s));height:calc(844px * var(--s));border:1px solid var(--line);
 border-radius:calc(26px * var(--s));overflow:hidden;box-shadow:0 14px 34px -18px var(--shadow);flex:none}
.frame > *{transform:scale(var(--s));transform-origin:top left}
.gap{margin:56px 0 0;border-top:1px solid var(--line);padding-top:34px}
.gap ul{margin:16px 0 0;padding:0 0 0 20px;max-width:66ch}
.gap li{color:var(--ink-2);font-size:14.5px;margin-bottom:9px;line-height:1.6}
.gap li strong{color:var(--ink);font-weight:500}
@media (prefers-reduced-motion:no-preference){
 .col,.frame,.frame>*{transition:width .28s ease,height .28s ease,transform .28s ease,border-radius .28s ease}}
"""

STUB = 'M32 29 H76 V71 L70.5 78 L65 71 L59.5 78 L54 71 L48.5 78 L43 71 L37.5 78 L32 71 Z'

def icon(bg, stub, bar, line, size, stroke=False):
    body = (f'<path d="{STUB}" fill="none" stroke="{stub}" stroke-width="3.5" stroke-linejoin="round"/>'
            if stroke else f'<path d="{STUB}" fill="{stub}"/>')
    return (f'<svg viewBox="0 0 108 108" width="{size}" height="{size}" role="img" aria-label="Pinged app icon">'
            f'<rect width="108" height="108" fill="{bg}"/>{body}'
            f'<rect x="40" y="43" width="22" height="5" fill="{bar}"/>'
            f'<rect x="40" y="54" width="14" height="4" fill="{line}"/></svg>')

VARIANTS = [
 ("A. Ink", "#1a1714", "#fbf6ea", "#a03826", "#cfc4ae", False,
  "Recommended. An icon lives among other icons, not among its own screens, and a dark ground is what earns a glance on a crowded home screen. The red line is the only colour and it reads at 48dp."),
 ("B. Paper", "#fbf6ea", "#1a1714", "#a03826", "#cfc4ae", True,
  "Matches the app you actually open. Truer to the product, but it is the third pale rounded square in a row on most home screens, and the stroked stub loses definition at 48dp."),
 ("C. Stamp", "#a03826", "#fbf6ea", "#1a1714", "#e0b9ae", False,
  "Loudest, and the easiest to find by muscle memory. It also spends the accent colour on the icon, which in the app means only ‘this needs you’."),
]

def masked(shape, radius, size=76):
    return (f'<div style="display:flex;flex-direction:column;align-items:center;gap:8px">'
            f'<div style="width:{size}px;height:{size}px;border-radius:{radius};overflow:hidden;line-height:0">'
            f'{icon("#1a1714","#fbf6ea","#a03826","#cfc4ae",size)}</div>'
            f'<span style="font-size:11px;color:var(--ink-3)">{shape}</span></div>')

variant_cards = "".join(
 f'<div style="display:flex;flex-direction:column;gap:12px">'
 f'<div style="display:flex;align-items:center;gap:14px">'
 f'<div style="width:88px;height:88px;border-radius:20px;overflow:hidden;line-height:0;flex:none;border:1px solid var(--line)">{icon(bg,st,bar,ln,88,stroke)}</div>'
 f'<div style="display:flex;flex-direction:column;gap:6px">'
 f'<div style="width:48px;height:48px;border-radius:11px;overflow:hidden;line-height:0;border:1px solid var(--line)">{icon(bg,st,bar,ln,48,stroke)}</div>'
 f'<span style="font-size:10.5px;color:var(--ink-3);font-family:\'IBM Plex Mono\',monospace">48dp</span>'
 f'</div></div>'
 f'<div><div style="font-size:15px;font-weight:500;margin-bottom:4px">{name}</div>'
 f'<p style="margin:0;font-size:13px;line-height:1.55;color:var(--ink-2)">{note}</p></div>'
 f'</div>'
 for name, bg, st, bar, ln, stroke, note in VARIANTS)

ICON_SECTION = f'''  <div class="grp">
    <h2 class="sec">The launcher icon</h2>
    <p class="lede">A receipt stub with a torn foot, reduced until it survives 48dp: four teeth, one red line, nothing else. Three grounds, shown large and at real launcher size.</p>
    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:34px">
{variant_cards}
    </div>
    <div style="margin-top:44px;border-top:1px solid var(--line-soft);padding-top:28px">
      <p class="lede" style="margin-bottom:22px">The recommended ground under every launcher mask, plus the monochrome layer Android 13 tints for themed icons.</p>
      <div style="display:flex;flex-wrap:wrap;gap:26px;align-items:flex-start">
        {masked("Circle", "50%")}
        {masked("Squircle", "28%")}
        {masked("Rounded square", "18px")}
        {masked("Square", "0")}
        <div style="display:flex;flex-direction:column;align-items:center;gap:8px">
          <div style="width:76px;height:76px;border-radius:28%;overflow:hidden;line-height:0;background:var(--panel);border:1px solid var(--line)">
            <svg viewBox="0 0 108 108" width="76" height="76" role="img" aria-label="Monochrome icon layer"><path d="{{STUB}}" fill="var(--ink)"/></svg>
          </div>
          <span style="font-size:11px;color:var(--ink-3)">Monochrome</span>
        </div>
      </div>
      <p class="lede" style="margin-top:26px">Artwork sits inside the central 72dp of the 108dp canvas and the stub itself inside 66dp, so no mask clips a tooth. The monochrome layer drops both interior lines &mdash; at tint-only fidelity the silhouette carries it, and two bars at 4dp would fill in.</p>
    </div>
  </div>
'''.replace("{STUB}", STUB)

sw = "\n".join(f'<div class="sw"><span class="chip" style="background:{h}"></span><span class="nm">{n}</span><span class="hex">{h}</span><span class="role">{r}</span></div>' for n,h,r in tokens)
ramphtml = "".join(f'<span style="background:{c}"></span>' for c in ramp)
ty = "\n".join(f'<div class="trow"><div class="role">{role}</div><div class="face" style="font-family:\'{f}\',serif">{f}</div><div class="use">{u}</div></div>' for f,role,u in typeroles)
rl = "\n".join(f"<li>{r}</li>" for r in rules)

def col(name, path, step, desc):
    return f'''      <div class="col">
        <div class="cap"><span class="step">{step}</span><h3>{name}</h3><p>{desc}</p></div>
        <div class="frame">{inner(path)}</div>
      </div>'''

blocks = []
for gname, glede, items in groups:
    body = "\n".join(col(*i) for i in items)
    blocks.append(f'''  <div class="grp">
    <h2 class="sec">{gname}</h2>
    <p class="lede">{glede}</p>
    <div class="screens">
{body}
    </div>
  </div>''')

page = f'''<title>Pinged Screen Set</title>
<link rel="stylesheet" href="{FONTS}">
<style>{css}</style>
<div class="wrap">
  <p class="eyebrow">Pinged &middot; Receipt direction</p>
  <h1>Twenty-one screens, one paper trail</h1>
  <p class="stand">The whole v1 surface &mdash; every screen, sheet and list &mdash; grouped the way a person meets it. The populated scenario is pinned to <strong>Wednesday 30 September 2026</strong>, month end, so every figure reconciles: RM2,847.30 across 112 captured transactions, RM100.00 of transfers excluded, four items awaiting review.</p>
  <p class="stand">The system below is the handoff &mdash; those hex values and type roles become the Compose theme directly.</p>

  <div class="grp" style="margin-top:52px">
    <h2 class="sec">The system</h2>
    <div class="sysgrid">
      <div>
        <div>
{sw}
        </div>
        <div class="ramp">{ramphtml}</div>
        <p class="rampnote">Chart ramp: one hue, six steps, darkest for the largest category. No categorical colour anywhere &mdash; magnitude is the only thing encoded.</p>
      </div>
      <div>
{ty}
        <ul class="rules">
{rl}
        </ul>
      </div>
    </div>
  </div>

{ICON_SECTION}
  <div class="controls">
    <button class="sizer" id="sizer" type="button">View at actual size</button>
    <span class="hint">Shown at 72%. Frames are 390 &times; 844, no fake status bars &mdash; Android draws its own.</span>
  </div>

{chr(10).join(blocks)}

  <div class="gap">
    <h2 class="sec">What is left</h2>
    <p class="stand" style="font-size:15px">One sheet: the filter panel behind the four chips on the search screen. Date range, category, source app, amount range &mdash; four controls whose behaviour the chips already describe, and the only screen here I am comfortable calling derivative.</p>
    <p class="stand" style="font-size:15px;margin-top:20px">Worth saying plainly: the review-inbox ratio is still a guess. If real capture routes far more than a handful of items into review, that screen stops being a quiet badge and becomes the app&rsquo;s main surface &mdash; a redesign only your own notification stream can settle.</p>
  </div>
</div>
</div>
<script>
(function(){{
  var b=document.getElementById('sizer'),g=[...document.querySelectorAll('.screens')],full=false;
  b.addEventListener('click',function(){{
    full=!full;
    g.forEach(function(s){{s.style.setProperty('--s',full?'1':'0.72');}});
    b.textContent=full?'Fit more on screen':'View at actual size';
  }});
}})();
</script>
'''
open('screen-set.html','w').write(page)
print("written", len(page))
