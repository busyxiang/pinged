# Pinged

An offline expense ledger built from the push notifications that Malaysian banks and e-wallets send. These are the terms its issues, specs and code use.

## Language

### Merchants

**Merchant raw**:
The merchant string exactly as the bank's notification sent it. It is never altered.
_Avoid_: original name, bank string

**Merchant key**:
The merchant identity a capture produced, derived from the merchant raw by the parser pack's normalisation. One shop can arrive under more than one key, because the rail that moved the money composes the string, and a pack change can give the same shop a new key.
_Avoid_: normalised form, learned-rule form, cleaned name

**Merchant identity**:
The merchant a transaction belongs to: its merchant key, or the key that one is merged into. Totals and rankings group by it, so two transactions are the same merchant exactly when their identities are equal.
_Avoid_: canonical merchant, resolved key

**Merge**:
The user's statement that one merchant key is the same shop as another, made from the merchant sheet. It changes no transaction, so separating restores the split exactly.
_Avoid_: link, combine, dedupe (that is spec 7.2's duplicate detection)

**Merchant display**:
A transaction's own name for its merchant, derived from the merchant raw. The user does not edit it per transaction: a rename is a merchant name. It plays no part in identity.
_Avoid_: label

**Merchant name**:
The name the user gave a merchant identity. Every transaction of that identity shows it in place of its own merchant display.
_Avoid_: alias, nickname


### Categories

**Dictionary**:
The categories the app ships for well-known merchants, part of the parser pack. The user cannot edit it. A rule the user taught outranks it, and a new pack changes how new payments are filed but never re-files old ones.
_Avoid_: bundled rules, shipped rules, seeded rules

**Learned rule**:
The category the user taught for a merchant identity, by saving the category chooser with "Always call this" on. It files every later payment of that merchant, whichever of its keys the payment arrives under, and outranks the dictionary. A merge or a pack change carries it with the merchant. The UI calls it a "learned merchant", and settings lists them under "Learned merchants" (read and delete only). Deleting one forgets the teaching for the whole identity, dormant source rules included, and re-files nothing.
_Avoid_: merchant rule, auto-category

**One-off**:
A category the user set on a single transaction without teaching a rule. It is history the user has confirmed, so no rule ever re-files it. An Uncategorized transaction is never confirmed.
_Avoid_: hand-set, manual category, user-edited


### Capture

**Captured day**:
A past day on which Pinged has evidence it was watching: the listener was recorded bound at some moment that day, or the day has a transaction captured from a notification, whatever its status. A transaction the user entered by hand is not evidence. It says nothing about the whole day, so a listener that died at noon still leaves a captured day. Today is never a captured or a not-captured day until it ends.
_Avoid_: watched day, covered day, live day

**History start**:
The first day Pinged has any capture evidence for, whether gathered on this device or on one whose backup was restored onto it. Days before it are outside the record, not missing from it. A restore carries it over, deleting everything resets it, and a transaction the user enters by hand never moves it.
_Avoid_: install date, first install

**Not captured**:
A past day, on or after the history start, with no evidence Pinged was watching. Nothing is known about its spending, which is different from nothing spent.
_Avoid_: missed day, gap, unbound day

**Untrusted month**:
A month that contains at least one not-captured day. Every figure drawn from it may be incomplete, in either direction, since a missed refund lowers a total as surely as a missed payment raises it. A month still running is not untrusted merely because it has not ended.
_Avoid_: do-not-trust month, incomplete month, partial month
