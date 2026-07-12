# ADR-0003: `:application/refer` — the human-carried handoff, implemented

## Status
Accepted. Implements superproject ADR-2607131000 (which designed this
op and deferred it); the deferral was lifted the next day.

## Decision (as built)
- `:application/refer` drafts a referral record (`JPN-REF-000000`, ...)
  holding the posting reference, an APPLICANT REFERENCE (operator-held
  pointer — PII never enters this public actor's store) and the target
  (`cloud-itonami-isic-7810`). Effect `:referral/record` writes history
  + sequence only; nothing on the posting changes, and multiple
  referrals per posting are normal (no double guard, like corrections).
- HARD gates: `applicant-consent-missing` (the applicant's own
  consent flag, operator-attested request input — the same posture as
  the spec-basis check reading proposal citations), `posting-not-live`
  (extended from corrections: you can only apply to a live posting),
  plus the standard spec-basis/evidence gates.
- Not an actuation: no `:actuation/*` stake (nothing public changes).
  The phase gate still routes every referral to a human at phase 3 —
  the carry IS the human act — and the op is phase-disabled below 3.

## Consequences
- 56 tests / 261 assertions green; sim walks consented referral
  (escalate → carry → record) and the consent-missing hold; the demo
  page shows the handoff section, the consent-missing verdict and the
  referral ledger facts.
- 7810's side stays unchanged, exactly per the superproject ADR: its
  own governor re-checks everything at candidacy intake.
