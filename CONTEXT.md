# Pinged

An offline expense ledger built from the push notifications that Malaysian banks and e-wallets send. These are the terms its issues, specs and code use.

## Language

### Merchants

**Merchant raw**:
The merchant string exactly as the bank's notification sent it. It is never altered.
_Avoid_: original name, bank string

**Merchant key**:
A merchant's identity, derived from the merchant raw by the parser pack's normalisation. Two transactions are the same merchant exactly when their merchant keys are equal, and a pack change can give the same shop a new key.
_Avoid_: normalised form, learned-rule form, cleaned name

**Merchant display**:
The merchant's name as the user sees it, derived from the merchant raw and editable by the user. It plays no part in identity.
_Avoid_: merchant name, label

