# ADR-0002: `:posting/correct` — the 訂正 half of the 的確表示義務

## Status

Accepted. Third actuation added additively, following ADR-0001's own
"Extending coverage is additive" clause.

## Context

職業安定法5条の4 obliges a 募集情報等提供事業者 to keep posted
information accurate AND to correct/delete it (求人者の依頼、または
情報が正確・最新でなくなったとき). R0 covered the delete half
(`:posting/delist`, plus the stale-vacancy gate); the correct half was
out of scope. Correcting a LIVE posting changes what the public sees —
it is a real-world act, not bookkeeping.

## Decision

1. **Two-step shape.** Corrected field VALUES enter through plain
   `:posting/ingest` (normalization, auto-eligible, no public effect in
   the governed model); `:posting/correct` is the governed act that
   changes the public surface and stamps the correction record. This
   keeps the governor's discipline intact: checks recompute against the
   Store's own ground truth, never against proposal payloads.
2. **Same content gates as a publication.** A correction may not
   introduce what a publication would have been refused for:
   stale-vacancy, ad-content-discriminatory,
   displayed-compensation-mismatch and source-consent-unverified all
   apply to `:posting/correct` (`content-gated-ops` in the governor).
3. **`posting-not-live` guard.** Only a published, not-delisted posting
   can be corrected — an unpublished draft is corrected by ingest; a
   delisted posting has nothing public to correct. Off the dedicated
   `:published?`/`:delisted?` booleans, same discipline as the
   double-actuation guards.
4. **No double-correction guard, deliberately.** A posting may
   legitimately be corrected more than once; `correction-history` keeps
   every record (JPN-COR-000000, -000001, ...), and the posting carries
   the LATEST `:correction-number`.
5. **Always a human call.** `:actuation/correct-posting` joins
   `high-stakes`, and `:posting/correct` is NEVER in any phase's
   `:auto` set — the same two independent layers as publish/delist.

## Consequences

- 49 tests / 221 assertions green (was 40/184); lint clean; sim walks
  a wage-change → ingest → correct(approve) lifecycle plus a not-live
  hold.
- The demo page shows the correction record on the live card and the
  correct/hold facts in the audit ledger.
- Registry `operating-states` already contained `:refresh`; correction
  is its implementation.
