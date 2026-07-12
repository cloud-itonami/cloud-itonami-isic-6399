# cloud-itonami-isic-6399

Open Business Blueprint for **ISIC 6399** (other information service
activities n.e.c.), narrowed to **meta job-search**: job-posting
aggregation, verification, publication into a public search index, and
freshness-driven delisting.

This repository publishes a meta-job-search actor -- posting ingest
from source feeds, per-jurisdiction job-advertising regulatory
assessment, posting publication and posting delisting -- as an OSS
business that any qualified operator can fork, deploy, run, improve
and sell, so a community job board or workforce program never
surrenders posting and search data to a closed job-board SaaS or an
ad-driven aggregator.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet (140 prior actors) -- here it is
**JobSearch-LLM ⊣ Job Search Portal Governor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:job-search-portal-governor`,
is a UNIQUE keyword fleet-wide (grep-verified: no other blueprint
declares it) -- a fresh, independent build. The ISIC class itself is
NARROWED from the generic "other information services" to job-posting
meta-search, the same narrowing move as `marketdata`/6311 (generic
data processing → market-data aggregation) and 4610 (generic wholesale
→ commission broker).

> **Why an actor layer at all?** An LLM is great at normalizing a
> posting record, drafting an evidence checklist, and checking whether
> a displayed pay figure actually equals the source's own recorded
> wage times hours -- but it has **no notion of which jurisdiction's
> job-advertising law is official, no licence to publish a real
> advertisement to real job seekers or take one down from a real
> index, and no way to know on its own whether a posting's own source
> vacancy has actually been closed, whether the displayed pay actually
> matches the source record, whether a third-party source's
> republication consent has actually been verified, or whether an
> advertisement's own content relies on a protected characteristic**.
> Letting it publish or delist directly invites fabricated regulatory
> citations, a stale (already-filled) vacancy staying live, a pay
> misstatement reaching job seekers, an unconsented republication, and
> a discriminatory advertisement being distributed -- exposing the
> portal to real regulatory liability (in Japan the 令和4年 職業安定法
> amendment regulates 募集情報等提供事業者 -- job-posting aggregators,
> exactly this business -- by name). This project seals the
> JobSearch-LLM into a single node and wraps it with an independent
> **Job Search Portal Governor**, a human **approval workflow**, and
> an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers posting ingest through job-advertising regulatory
assessment, posting publication and posting delisting. It does
**not**, by itself, hold any registration required to operate a
job-information provider in a given jurisdiction (e.g. Japan's
特定募集情報等提供事業者届出), and it does not claim to. It also does
not perform the actual source crawling/feed integration itself, does
not match or place any candidate (that is `cloud-itonami-isic-7810`'s
business, deliberately out of scope here), and does not judge job
quality -- `jobsearchops.registry/displayed-compensation-matches-claim?`
is a pure ground-truth recompute against the posting's own recorded
source fields, not a fairness judgment. Whoever deploys and operates a
live instance (a qualified portal operator) supplies any
jurisdiction-specific registration, the real source-feed/crawler and
search-index integrations, and bears that jurisdiction's liability --
the software supplies the governed, spec-cited, audited execution
scaffold so that operator does not have to build the compliance layer
from scratch.

### Actuation

**Publishing a real posting and delisting a real posting are never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`jobsearchops.governor`'s `:actuation/publish-posting`/
`:actuation/delist-posting` high-stakes gate and `jobsearchops.phase`'s
phase table, which never puts either op in any phase's `:auto` set) --
see `jobsearchops.phase`'s docstring and `test/jobsearchops/
phase_test.clj`'s `posting-publish-never-auto-at-any-phase`/
`posting-delist-never-auto-at-any-phase`. The actor may draft, check
and recommend; a human portal operator is always the one who actually
publishes or delists a posting. Grounded directly in this blueprint's
own `docs/business-model.md` Trust Controls text ("a posting the
source has closed is never published; publication outside source
consent is blocked") -- a genuine DUAL-actuation shape, applied
SEQUENTIALLY to the SAME posting record (publish first, delist later),
matching `employmentops`/7810's, `practiceops`/7110's,
`hospitalityops`/5510's, `freightops`/4920's, `quarryops`/0810's and
`agronomyops`/0162's own sequential shape rather than
`retailops`/4711's own alternative-kind shape.

## The core contract

```
posting ingest + jurisdiction facts (jobsearchops.facts, spec-cited)
        |
        v
   ┌───────────────────────┐   proposal      ┌───────────────────────────────┐
   │ JobSearch-LLM         │ ─────────────▶ │ Job Search Portal Governor     │  (independent system)
   │ (sealed)              │  + citations    │ spec-basis · evidence-         │
   └───────────────────────┘                 │ incomplete · stale-vacancy     │
          │                 commit ◀┼ (FLAGSHIP NEW) · ad-content-           │
          │                         │ discriminatory (reapplied) ·           │
    record + ledger        escalate ┼ displayed-compensation-mismatch        │
          │              (ALWAYS for│ (ground-truth) · source-consent-       │
          │      :actuation/publish-│ unverified (conditional, NEW member    │
          │      posting/           │ of the consent family) ·               │
          │      :actuation/delist- │ already-published · already-delisted   │
          │      posting}           │                                        │
          ▼                          └───────────────────────────────┘
      human approval
```

**The JobSearch-LLM never publishes or delists a posting the Job
Search Portal Governor would reject, and never does so without a human
sign-off.** Hard violations (fabricated regulatory requirements;
unsupported evidence; a source vacancy already closed; discriminatory
ad content; a displayed-pay mismatch; an unverified source consent on
a consent-requiring posting; a double publication/delisting) force
**hold** and *cannot* be approved past; a clean publication/delisting
proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean publish(+delist) lifecycles (no source consent required, source consent required-and-verified), plus five HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Live demo (GitHub Pages)

**<https://cloud-itonami.github.io/cloud-itonami-isic-6399/>** -- a
static, zero-build search UI (synthetic data). NOTHING on it is
hand-typed: `web/generate.cljs` (nbb) runs the FULL OperationActor
StateGraph at build time (ingest -> assess -> publish -> delist with
approval interrupts, plus every HARD-hold attempt and a double-publish
attempt), then renders the post-run Store as the live index, the real
refusal verdicts as the transparency table, and the append-only audit
ledger those runs actually wrote. In-browser search is
`web/search.cljs` run by scittle (ClojureScript in the browser -- no
hand-written JS, no build step). `web/verify_search.cljs` is the
headless nbb harness that exercises the real client logic against the
real generated page.

```bash
cd web && ../../../../node_modules/.bin/nbb \
  --classpath "../src:../../../kotoba-lang/html/src:../../../kotoba-lang/css/src:../../../kotoba-lang/langchain/src:../../../kotoba-lang/langgraph/src" \
  generate.cljs          # regenerate docs/index.html + docs/search.cljs
../../../../node_modules/.bin/nbb verify_search.cljs   # headless UI logic check
```

## No robotics

Most cloud-itonami verticals are designed on the premise that a robot
performs the physical domain work. This vertical has **no physical
actuation surface at all**: the actor's entire real-world effect is a
public search index (publish/delist). It therefore carries no
`:robotics` requirement -- the same exemption class as
`cloud-itonami-6310`/`-isic-6311`/`-isic-6312`/`-isic-7820`. The
high-stakes acts (publication, delisting) still require human sign-off
via the same governor + phase machinery every robotics sibling uses.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Job Search Portal Governor, publication/delisting draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/operator-quickstart.md`](docs/operator-quickstart.md) to go
from fork to your own published, governor-checked board (the generator
takes your `postings.edn` and runs it through the real actor),
[`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6399`). This vertical's posting/publication records are
portal-specific rather than a shared cross-operator data contract, so
`jobsearchops.*` runs on the generic identity/forms/dmn/bpmn/
audit-ledger stack only -- no bespoke domain capability lib to
reference at all (`kotoba-lang/occupation` is the generic ISCO-08
occupation-classification registry -- the occupation-classification
analog of `kotoba.industry` -- not a bespoke domain capability
library, matching `employmentops`/7810's own investigated-and-ruled-out
precedent).

## Layout

| File | Role |
|---|---|
| `src/jobsearchops/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + publication AND delisting history (dual history). The double-actuation guard checks dedicated `:published?`/`:delisted?` booleans rather than a `:status` value |
| `src/jobsearchops/registry.cljc` | Publication/delisting draft records, plus `displayed-compensation-matches-claim?` -- an honest reapplication of the SAME ground-truth-recompute discipline every sibling actor's own cost/total-matching check establishes |
| `src/jobsearchops/facts.cljc` | Per-jurisdiction job-advertising AND source-republication/database-right catalog with an official spec-basis citation per entry, honest coverage reporting -- ALL SIX seeded jurisdictions have a consent sub-citation here |
| `src/jobsearchops/jobsearchopsllm.cljc` | **JobSearch-LLM** -- `mock-advisor` ‖ `llm-advisor`; ingest/jurisdiction-assessment/publication/delisting proposals |
| `src/jobsearchops/governor.cljc` | **Job Search Portal Governor** -- 6 named HARD checks (spec-basis · evidence-incomplete · stale-vacancy, FLAGSHIP domain-unique · ad-content-discriminatory, reapplied · displayed-compensation-mismatch, ground-truth · source-consent-unverified, CONDITIONAL) + 2 double-actuation guards + 1 soft (confidence/actuation gate) |
| `src/jobsearchops/phase.cljc` | **Phase 0→3** -- read-only → assisted ingest → assisted assess → supervised (publish/delist always human; posting ingest is the ONLY auto-eligible op, no direct public-facing risk) |
| `src/jobsearchops/operation.cljc` | **OperationActor** -- langgraph StateGraph |
| `src/jobsearchops/sim.cljc` | demo driver |
| `test/jobsearchops/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers posting ingest through job-advertising regulatory
assessment, posting publication and posting delisting -- the core
governed lifecycle this blueprint's own `docs/business-model.md` names
in its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Posting ingest + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:posting/ingest`/`:jurisdiction/assess`) | Real source crawling/feed integration, real search-index/ranking engine, candidate matching or placement (see `jobsearchops.facts`'s docstring and `cloud-itonami-isic-7810`) |
| Posting publication, HARD-gated on full evidence, a current (non-closed) source vacancy, non-discriminatory ad content, a matching displayed pay and (when applicable) a verified source consent, plus a double-publication guard (`:posting/publish`) | |
| Posting delisting, HARD-gated on full evidence, plus a double-delisting guard (`:posting/delist`) | |
| Posting correction (訂正 — 職業安定法5条の4's other half; ADR-0002), HARD-gated on the SAME content gates as a publication plus a posting-not-live guard; multiple corrections per posting, each with its own record (`:posting/correct`) | |
| Immutable audit ledger for every ingest/assessment/publication/delisting decision | |

Extending coverage is additive — `:posting/correct` (ADR-0002) is the
first such extension, added as its own governed op with its own HARD
checks and tests, following the SAME "an independent governor
re-verifies against the actor's own records before any real-world act"
pattern this repo's flagship ops establish.

## Jurisdiction coverage (honest)

`jobsearchops.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `jobsearchops.facts/catalog`
-- currently 6 seeded (JPN, USA, GBR, DEU, FRA, KOR) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `jobsearchops.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger. Note that the source-consent sub-citation
is FULL coverage rather than a gap: ALL SIX seeded jurisdictions
(JPN, USA, GBR, DEU, FRA, KOR) actually have a real source-
republication/database-right regime, reported honestly.

## Maturity

`:implemented` -- `JobSearch-LLM` + `Job Search Portal Governor` run
as real, tested code (see `Run` above), published directly at
`:implemented` tier following the SAME governed-actor architecture as
the 140 other prior actors across this fleet, with its own distinct,
independently-named governor. See `docs/adr/0001-architecture.md` for
the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
