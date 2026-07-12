# ADR-0001: JobSearch-LLM ⊣ Job Search Portal Governor architecture

## Status

Accepted. `cloud-itonami-isic-6399` published directly at
`:implemented` in the `kotoba-lang/industry` registry, replacing the
prior `:spec`-tier J-prefix placeholder (`gftdcojp/cloud-itonami-J6399`,
a registry row whose repo was never created).

## Context

`cloud-itonami-isic-6399` publishes an OSS business blueprint for
meta job-search operations (posting aggregation, verification,
publication into a public search index, freshness-driven delisting) --
the Indeed-shaped business. Like every prior actor in this fleet, a
blueprint alone is not an implementation: this ADR records the
governed-actor architecture that ships it as real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0→3 rollout pattern established by `cloud-itonami-isic-6511` (life
insurance) and applied across 140 prior siblings, most recently
`cloud-itonami-isic-7810` (community employment agency).

Placement (see the superproject's ADR-2607121700 for the full
survey): no job-search/aggregator venture existed anywhere in the
fleet. `kotoba.industry/by-id` keys the registry by `:id` -- one entry
per ISIC class -- so the classes closest in spirit were unavailable or
wrong: `6312` (web portals) is the generic content-curation portal
actor, `7810` is the employment placement agency (whose own README
rules posting aggregation out of scope). ISIC `6399` ("other
information service activities n.e.c.", whose official explanatory
notes include *information search services*) sat as a `:spec`
placeholder and is NARROWED here to job-posting meta-search -- the
same narrowing move as `marketdata`/6311 (generic data processing →
market-data aggregation) and 4610 (generic wholesale → commission
broker).

A `kotoba-lang` org search for job/search/board/posting/recruiting-
named repos returned zero hits. `kotoba-lang/occupation` was already
investigated and ruled out by `employmentops`/7810's own build: it is
the generic ISCO-08 occupation-classification registry, not a bespoke
domain capability library. This build returns to self-contained domain
logic, the same pattern the majority of this fleet's actors use.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:job-search-portal-governor`, is grep-verified UNIQUE fleet-wide --
no naming-collision precedent question, a fresh independent build.

## Decision

### Decision 1: fresh governor identity, no reuse precedent needed

`:job-search-portal-governor` is grep-verified unique across every
blueprint.edn in this fleet. This build follows the SAME governed-
actor architecture as every prior actor, but with its own distinct
governor identity.

### Decision 2: dual-actuation shape, SEQUENTIAL on the SAME `posting` entity

This blueprint's own operating states ("ingest : assess : publish :
refresh : delist : audit") name two real-world acts: publishing a
posting into the public index and delisting it. These apply
SEQUENTIALLY to the SAME `posting` entity -- publish first, delist
later -- matching `employmentops`/7810's, `practiceops`/7110's,
`hospitalityops`/5510's, `freightops`/4920's, `quarryops`/0810's and
`agronomyops`/0162's own sequential shape rather than
`retailops`/4711's own alternative-kind shape. `high-stakes` is
`#{:actuation/publish-posting :actuation/delist-posting}`. Note that
delisting is genuinely high-stakes in BOTH directions: failing to
delist violates the currency duty (的確表示義務), while a wrongful
delisting removes a real source employer's live advertisement.

### Decision 3: `stale-vacancy` -- the FLAGSHIP domain-unique check

Grep-verified: zero fleet hits for `vacancy` as a governor check
concept. Publishing a posting whose own record says the source vacancy
has already been closed/filled is a HARD hold, evaluated
UNCONDITIONALLY on every `:posting/publish`. Grounded in real
job-advertising accuracy law: Japan's own 職業安定法5条の4
(的確表示義務 -- the 令和4年 (2022) amendment provision written for
募集情報等提供事業者, i.e. exactly this business, enforced by MHLW),
the US's FTC Act §5 (15 U.S.C. §45, deceptive practices), the UK's
Conduct of Employment Agencies and Employment Businesses Regulations
2003 (SI 2003/3319) reg. 27 (advertisements must relate to genuine
vacancies, enforced by EAS), and Germany's UWG §5 (misleading
commercial practices). HONESTY NOTE: the staleness DISCIPLINE itself
is not new fleet-wide -- `marketdata`/6311's own stale-print check and
the transport tier's own integrity-assessment-stale check already
apply it -- what is new is the genuine-vacancy statutory grounding and
the posting-currency application; documented as such, not overclaimed.

### Decision 4: `ad-content-discriminatory?` -- an honest reapplication of 7810's discipline to advertisement content

`employmentops`/7810 established the anti-discrimination governor
discipline for *matching criteria*. This vertical reapplies it to
*advertisement content* at publication time, under the AD-SPECIFIC
statutory provisions: Japan's own 男女雇用機会均等法5条 and
労働施策総合推進法9条 (age limits in recruiting), the US's Title VII
§704(b) (42 U.S.C. §2000e-3(b)) and ADEA §4(e) (29 U.S.C. §623(e)) --
both specifically about printed/published notices and advertisements
-- the UK's Equality Act 2010, and Germany's AGG §11 (Ausschreibung).
Documented as a reapplication, not claimed as new.

### Decision 5: `displayed-compensation-matches-claim?` -- an honest reapplication of the ground-truth-recompute discipline

`jobsearchops.registry/displayed-compensation-matches-claim?`
(posting's own displayed pay vs. source-hourly-wage x
source-monthly-hours) applies the SAME discipline `employmentops.
registry`'s own `placement-fee-matches-claim?`, `practiceops.
registry`'s own `fee-total-matches-claim?` and `hospitalityops.
registry`'s own `folio-total-matches-claim?` establish -- verify a
claimed monetary total against the entity's own recorded fields,
independent of proposal inspection. No literal code is shared
(different domain), but the discipline is the same, documented as such
rather than claimed as a novel invention.

### Decision 6: `source-consent-unverified?` -- a new MEMBER of the consent-check family, the conditional variant

Before writing this check, the fleet registry's own check index was
searched: consent checks already exist as a FAMILY
(customer-data-consent, prior-informed-consent (Basel/RCRA bilateral),
guardian-consent). This vertical adds a new member -- SOURCE-
REPUBLICATION consent -- CONDITIONAL on the posting's own
`:requires-source-consent?` ground truth: a posting submitted directly
by the employer carries no republication-consent requirement at all;
a posting aggregated from a third-party source whose terms/law require
consent does. Grounded in real database-right/copyright law: Japan's
own 著作権法12条の2 (database works) plus the 職業安定法43条の2
特定募集情報等提供事業者 regime, the US's 17 U.S.C. §103/§106, the
UK's Copyright and Rights in Databases Regulations 1997 (SI 1997/3032,
sui generis database right), and Germany's UrhG §§87a–87e
(Datenbankherstellerrecht). ALL FOUR seeded jurisdictions actually
have a real regime here, reported honestly -- a full-coverage
sub-citation, matching `employmentops`/7810's own work-authorization
full coverage rather than `hospitalityops`/5510's own honest
single-jurisdiction gap. Documented as family kinship, not absolute
novelty.

### Decision 7: entity and op shape

The primary entity is a `posting`. Four ops: `:posting/ingest`
(directory upsert, no public-facing risk, the ONLY auto-eligible op),
`:jurisdiction/assess` (per-jurisdiction job-advertising evidence
checklist, never auto), `:posting/publish` (POSITIVE, high-stakes),
and `:posting/delist` (POSITIVE, high-stakes).

### Decision 8: dedicated double-actuation-guard booleans

`:published?`/`:delisted?` are dedicated booleans on the `posting`
record, never a single `:status` value -- the same discipline every
prior governor's guards establish, informed by
`cloud-itonami-isic-6492`'s real status-lifecycle bug
(ADR-2607071320).

### Decision 9: Store protocol, MemStore + DatomicStore parity

`jobsearchops.store/Store` is implemented by both `MemStore` (atom-
backed, default for dev/tests/demo) and `DatomicStore` (`langchain.
db`-backed), proven to satisfy the same contract in
`test/jobsearchops/store_contract_test.clj`.

### Decision 10: no robotics

This vertical's entire actuation surface is a public search index --
no physical domain work exists to robotize. Same exemption class as
`cloud-itonami-6310`/`-isic-6311`/`-isic-6312`/`-isic-7820`;
`:required-technologies` is `[:identity :forms :dmn :bpmn
:audit-ledger]`, `blueprint.edn` declares
`:itonami.blueprint/robotics false`.

### Decision 11: mock + LLM advisor pair

`jobsearchops.jobsearchopsllm` provides `mock-advisor` (deterministic,
default everywhere -- the actor graph and governor contract run
offline) and `llm-advisor` (backed by `langchain.model/ChatModel`,
with a defensive EDN-proposal parser so a malformed LLM response
degrades to a safe low-confidence noop rather than ever
auto-publishing or auto-delisting a posting).

## Alternatives considered

- **Hosting this inside `cloud-itonami-isic-7810`** (the employment
  agency) as an additive op. Rejected: 7810's own scope statement
  rules sourcing/aggregation out, and the two businesses have disjoint
  regulatory surfaces (placement licensure vs. 的確表示/aggregator
  registration) -- see the superproject's ADR-2607121700.
- **Registering under `6312` (web portals).** Rejected: the registry
  is keyed one-entry-per-class and `6312` is already the generic
  content-curation portal actor.
- **An unconditional source-consent check** (applying to every
  publication regardless of source). Rejected: a posting submitted
  directly by the employer has no republication-consent requirement at
  all -- forcing the check onto every publication would fabricate a
  requirement.
- **Claiming `stale-vacancy` as an absolutely new concept.** Rejected:
  the staleness discipline already exists fleet-wide (stale-print,
  assessment-stale); this build claims only the genuine-vacancy
  statutory grounding and the posting-currency application as new.

## Consequences

- 141st actor in this fleet (140 implemented before this build).
- The Indeed-shaped business exists in the fleet with the exact
  regulatory surface real job-posting aggregators are regulated under
  (的確表示義務 etc.), while candidate matching/placement stays in
  7810 and generic portal curation stays in 6312.
- `MemStore` ‖ `DatomicStore` parity is proven by
  `test/jobsearchops/store_contract_test.clj`.
- 40 tests / 184 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks two clean publish(+delist) lifecycles
  (no source consent required, source consent required-and-verified),
  plus five HARD-hold scenarios and both double-actuation guards,
  end-to-end.

## References

- superproject ADR-2607121700 (venture placement, fleet survey,
  registry constraints)
- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of
  the general governed-actor architecture pattern)
- `cloud-itonami-isic-7810/docs/adr/0001-architecture.md` (most recent
  prior sibling, template for this ADR's structure; scope boundary)
- 職業安定法5条の4・43条の2 (令和4年改正); 男女雇用機会均等法5条;
  労働施策総合推進法9条; 著作権法12条の2 (Japan)
- Title VII §704(b), 42 U.S.C. §2000e-3(b); ADEA §4(e), 29 U.S.C.
  §623(e); FTC Act §5, 15 U.S.C. §45; 17 U.S.C. §103/§106 (US)
- Equality Act 2010; Conduct of Employment Agencies and Employment
  Businesses Regulations 2003 (SI 2003/3319) reg. 27; Copyright and
  Rights in Databases Regulations 1997 (SI 1997/3032) (UK)
- Allgemeines Gleichbehandlungsgesetz (AGG) §11; UWG §5; UrhG §§87a–87e (Germany)
