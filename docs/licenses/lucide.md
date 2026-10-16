# Lucide icons

`feature/ledger/src/main/res/drawable/ic_category_*.xml` are the 54 icons of
spec section 4's picker set -- the sixteen seeded categories' among them --
converted from the upstream Lucide SVGs.
`ic_row_not_counted.xml` (`circle-slash`) and `ic_row_uncategorized.xml`
(`circle-plus`) are the two marks a transaction row draws instead of a
category icon -- for an excluded row and for one still to be filed -- and come
from the same place by the same route.
The conversion is mechanical, and is `lucide_to_vector.py` beside this file,
which reproduces every one of those drawables from the pinned release byte for
byte. Each `<path d>` becomes an `android:pathData`, `<circle>` becomes a
two-arc path, `<line>` a move-and-line and `<rect>` a closed path with an arc
at each rounded corner, because Android vector drawables have none of those
elements. An implicit repeat in path data is written out as a command of its
own, a line after a move. Arc parameters are re-emitted
comma-separated: Android's `PathParser` will not read the packed flags the SVG
grammar allows (`a1.5 1.5 0 00-2.474-1.561`), and a path it cannot read throws
on first draw rather than at build time. The converter refuses any SVG element
it does not handle and checks it converted as many elements as it found, since
a silently dropped one still inflates and simply draws the wrong picture. Stroke width, caps and joins are upstream's;
the stroke colour in the file is a placeholder and every call site tints it.

Spec section 4 is why these are bundled files rather than a library
dependency: the icon is resolved from the category row at render time, and
only bundled icons can be offered.

Upstream: https://github.com/lucide-icons/lucide

Pinned release: `lucide-static@1.50.0`, as published on npm. Every drawable
listed above is the conversion of that release's `icons/<name>.svg`, checked
file for file. Elements are emitted paths first, then circles, then lines,
whatever order upstream lists them in, so a file that only reorders its
elements upstream converts to the same drawable. Rects come last, after lines.

## Which licence covers which file

All but four are Lucide's own and carry the **ISC** licence below. Four --
`shopping-bag`, `music`, `smartphone` and `trash-2`, drawn as
`ic_category_shopping_bag.xml`, `ic_category_music.xml`,
`ic_category_smartphone.xml` and `ic_category_trash_2.xml` -- are derived from
Feather and carry the **MIT** licence below instead. That split is upstream's
own, checked against the pinned release's LICENSE rather than assumed.

## ISC (Lucide)

```
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## MIT (Feather, for `shopping-bag`, `music`, `smartphone` and `trash-2`)

```
The MIT License (MIT) (for the icons listed above)

Copyright (c) 2013-present Cole Bemis

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
