"""Convert a lucide-static SVG to an Android vector drawable, per docs/licenses/lucide.md.

usage: python3 -I docs/licenses/lucide_to_vector.py <lucide-static>/icons <name> > out.xml

Reproduces every committed ic_category_*.xml and ic_row_*.xml byte for byte
from lucide-static@1.50.0. Elements go out paths, circles, lines, then rects.
"""
import re
import sys
import xml.etree.ElementTree as ET

HEADER = """<!-- Lucide (ISC, or MIT where upstream derives the icon from Feather). Generated from the upstream SVG.
     See docs/licenses/lucide.md for the conversion and the licences.
     Stroke colour is a placeholder: every call site tints it. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
"""
PATH = """    <path
        android:pathData="{d}"
        android:strokeColor="#FF000000"
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
"""
NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
CMD = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]")


ARITY = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "a": 7, "z": 0}


def convert_path(d):
    """Reads [d] by each command's arity, so a flag is one character and an
    implicit repeat becomes a command of its own (after M/m, an L/l)."""
    i = 0
    parts = []

    def skip():
        nonlocal i
        while i < len(d) and d[i] in " ,\t\n":
            i += 1

    def number():
        nonlocal i
        skip()
        m = NUM.match(d, i)
        if not m:
            raise SystemExit(f"cannot read a number at {d[i:]!r}")
        i = m.end()
        return m.group(0)

    def flag():
        nonlocal i
        skip()
        if i >= len(d) or d[i] not in "01":
            raise SystemExit(f"cannot read an arc flag at {d[i:]!r}")
        i += 1
        return d[i - 1]

    skip()
    while i < len(d):
        if not CMD.match(d[i]):
            raise SystemExit(f"expected a command at {d[i:]!r}")
        cmd = d[i]
        i += 1
        n = ARITY[cmd.lower()]
        first = True
        while True:
            if n == 0:
                parts.append(cmd)
                break
            if cmd in "Aa":
                args = [number(), number(), number(), flag(), flag(), number(), number()]
            else:
                args = [number() for _ in range(n)]
            parts.append(cmd + ",".join(args))
            if first and cmd in "Mm":
                cmd = "L" if cmd == "M" else "l"
            first = False
            skip()
            if i >= len(d) or CMD.match(d[i]):
                break
        skip()
    return " ".join(parts)


def fmt(v):
    return repr(float(v))


def circle(e):
    cx, cy, r = (float(e.get(k)) for k in ("cx", "cy", "r"))
    return f"M{fmt(cx - r)},{fmt(cy)} a{fmt(r)},{fmt(r)},0,1,0,{fmt(2 * r)},0 a{fmt(r)},{fmt(r)},0,1,0,{fmt(-2 * r)},0"


def line(e):
    return f"M{e.get('x1')},{e.get('y1')} L{e.get('x2')},{e.get('y2')}"


def rect(e):
    x, y, w, h = (float(e.get(k)) for k in ("x", "y", "width", "height"))
    rx = float(e.get("rx", e.get("ry", 0)))
    ry = float(e.get("ry", rx))
    if rx == 0:
        return f"M{fmt(x)},{fmt(y)} h{fmt(w)} v{fmt(h)} h{fmt(-w)} Z"
    return (
        f"M{fmt(x + rx)},{fmt(y)} h{fmt(w - 2 * rx)} a{fmt(rx)},{fmt(ry)},0,0,1,{fmt(rx)},{fmt(ry)}"
        f" v{fmt(h - 2 * ry)} a{fmt(rx)},{fmt(ry)},0,0,1,{fmt(-rx)},{fmt(ry)}"
        f" h{fmt(-(w - 2 * rx))} a{fmt(rx)},{fmt(ry)},0,0,1,{fmt(-rx)},{fmt(-ry)}"
        f" v{fmt(-(h - 2 * ry))} a{fmt(rx)},{fmt(ry)},0,0,1,{fmt(rx)},{fmt(-ry)} Z"
    )


def main(icons, name):
    root = ET.parse(f"{icons}/{name}.svg").getroot()
    ns = "{http://www.w3.org/2000/svg}"
    kids = list(root)
    by = {"path": [], "circle": [], "line": [], "rect": []}
    for e in kids:
        tag = e.tag.replace(ns, "")
        if tag not in by:
            raise SystemExit(f"{name}: unhandled element <{tag}>")
        by[tag].append(e)
    out = [convert_path(e.get("d")) for e in by["path"]]
    out += [circle(e) for e in by["circle"]]
    out += [line(e) for e in by["line"]]
    out += [rect(e) for e in by["rect"]]
    if len(out) != len(kids):
        raise SystemExit(f"{name}: converted {len(out)} of {len(kids)} elements")
    sys.stdout.write(HEADER + "".join(PATH.format(d=d) for d in out) + "</vector>\n")


main(sys.argv[1], sys.argv[2])
