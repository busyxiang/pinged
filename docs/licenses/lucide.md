# Lucide icons

`feature/ledger/src/main/res/drawable/ic_category_*.xml` are the fourteen
category icons spec section 4 names, converted from the upstream Lucide SVGs.
`ic_row_not_counted.xml` (`circle-slash`) and `ic_row_uncategorized.xml`
(`circle-plus`) are the two marks a transaction row draws instead of a
category icon -- for an excluded row and for one still to be filed -- and come
from the same place by the same route.
The conversion is mechanical -- each `<path d>` becomes an `android:pathData`,
`<circle>` becomes a two-arc path and `<line>` a move-and-line, because Android
vector drawables have none of those elements. Arc parameters are re-emitted
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

## Which licence covers which file

All but one are Lucide's own and carry the **ISC** licence below.
One -- `shopping-bag`, drawn as `ic_category_shopping_bag.xml` -- is derived
from Feather and carries the **MIT** licence below instead. That split is
upstream's own, checked against its LICENSE rather than assumed.

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

## MIT (Feather, for `shopping-bag`)

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
