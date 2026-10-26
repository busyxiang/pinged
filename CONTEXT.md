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
The categories the app ships for well-known merchants, part of the parser pack. The user cannot edit it. It names each category by the name it was seeded under, so a category the user renames keeps its merchants. A rule the user taught outranks it, and a new pack changes how new payments are filed but never re-files old ones.
_Avoid_: bundled rules, shipped rules, seeded rules

**Learned rule**:
The category the user taught for a merchant identity, by saving the category chooser with "Always call this" on. It files every later payment of that merchant, whichever of its keys the payment arrives under, and outranks the dictionary. A merge or a pack change carries it with the merchant. The UI calls it a "learned merchant", and settings lists them under "Learned merchants" (read and delete only). Deleting one forgets the teaching for the whole identity, dormant source rules included, and re-files nothing.
_Avoid_: merchant rule, auto-category

**Filing**:
Where a payment from a merchant goes, and what put it there: the learned rule for its identity, else the dictionary's entry for the key it arrived under, else nothing, which leaves it Uncategorized. A row's category may differ from its merchant's filing today: a one-off, or a rule or pack that has since changed.
_Avoid_: categorisation, resolution, classification

**One-off**:
A category the user set on a single transaction without teaching a rule. It is history the user has confirmed, so no rule ever re-files it. An Uncategorized transaction is never confirmed.
_Avoid_: hand-set, manual category, user-edited


### Transactions

**Manual entry**:
A transaction the user typed in for a payment Pinged did not see: cash, or a payment whose notification never arrived. The name says how it reached the ledger, not how it was paid. It is the only transaction whose amount or date the user can change, and the only one they can delete, since the user is its only witness.
_Avoid_: cash entry, hand entry, manual transaction

**Awaiting review**:
A captured transaction Pinged is not sure of, kept out of every total until the user answers. Dismissing one keeps it as evidence of what the bank sent; it is never destroyed.
_Avoid_: pending (the stored state), held, flagged, needs review

**Review inbox**:
The one place every transaction awaiting review is answered.
_Avoid_: review queue, inbox (alone), corrections (that is the re-parse list)

**To sort**:
A counted transaction still filed under Uncategorized, waiting for the user to give it a category. Unlike one awaiting review, it is already in every total; only where it is filed is unknown.
_Avoid_: unsorted, uncategorised, needs a category


### Capture

**Capture evidence**:
Proof that Pinged was watching at some moment: the listener was recorded bound, or Pinged kept a notification, whatever became of it -- a transaction, an unread capture, or one the user said is never a transaction. A manual entry is never capture evidence, since Pinged did not see it.
_Avoid_: proof of capture

**Captured day**:
A past day that holds capture evidence. It says nothing about the whole day, so a listener that died at noon still leaves a captured day. Today is never a captured or a not-captured day until it ends.
_Avoid_: watched day, covered day, live day

**History start**:
The first day Pinged has any capture evidence for, whether gathered on this device or on one whose backup was restored onto it. Days before it are outside the record, not missing from it. A restore carries it over, deleting everything resets it, and a manual entry never moves it.
_Avoid_: install date, first install

**Capture record**:
Every day from the history start to yesterday, each one either captured or not captured. Days before the history start are outside the record, and today joins it when it ends. With no history start there is no record, and no day is captured or not captured.
_Avoid_: coverage, capture history, watch log

**Not captured**:
A past day, on or after the history start, with no capture evidence. Nothing is known about its spending, which is different from nothing spent.
_Avoid_: missed day, gap, unbound day

**Not read yet**:
A figure a screen has not finished reading from the ledger. It says nothing about the money: it is neither zero nor not captured, and it is shown as a dash until the read lands.
_Avoid_: loading, pending, skeleton, unread (that is an unread capture)

**Untrusted month**:
A month that contains at least one not-captured day. Every figure drawn from it may be incomplete, in either direction, since a missed refund lowers a total as surely as a missed payment raises it. A month still running is not untrusted merely because it has not ended.
_Avoid_: do-not-trust month, incomplete month, partial month

**Comparable month**:
A month that has ended, lies wholly on or after the history start, and is not untrusted. One month is set against another only when both are comparable. A month still running, a month the history start falls part-way through, and an untrusted month are never compared.
_Avoid_: complete month, closed month, full month

**Collecting**:
The stretch before Pinged holds fourteen captured days, counted across all history from the history start. Until it ends, the app shows each day it has but makes no claim about where the money tends to go. It ends once and stays ended, unless deleting everything resets the history start. A restored history counts in full.
_Avoid_: thin data, warm-up, onboarding period

**Unread capture**:
A notification Pinged kept that no rule read: no template matched it and no pack reject pattern did, and the user has not said it is never a transaction. It counts toward nothing. Settings lists them as "Notifications Pinged could not read"; the screen is "Could not read".
_Avoid_: unmatched notification, unparsed capture, failed capture

**Never a transaction**:
The user's statement that an unread capture, and every later one of the same shape from the same app, is not money. It hides them from the unread list and decides nothing else: a capture a rule reads is a transaction whatever the user said. Settings lists each one under IGNORED, and deleting it returns its captures to the list.
_Avoid_: reject, dismiss, ignore rule

**Taught message**:
A rule the user made by tapping a message's amount and merchant on the Teach screen. It reads only messages of exactly that shape from that app, and only once every rule Pinged shipped with has failed. Its first three transactions wait for review. Settings lists each under MESSAGES, and deleting one leaves the transactions it wrote.
_Avoid_: user template, custom rule, user regex
