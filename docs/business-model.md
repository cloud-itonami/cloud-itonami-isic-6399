# Business Model: Meta Job Search

## Classification
- Repository: `cloud-itonami-isic-6399`
- ISIC: `6399` — other information service activities n.e.c., narrowed to meta job-search — posting aggregation, verification, publication and freshness-driven delisting
- Social impact: labor-market-transparency fair-recruitment-advertising job-seeker-protection

## Customer

Primary customers (an operator's customers, and the operator itself):

- regional/municipal workforce programs and 地方自治体の就労支援 that want a
  job board they control instead of paying an aggregator per click
- industry associations and cooperatives (建設・介護・飲食など) running a
  sector-specific board for member companies
- staffing/HR groups that need a compliant posting surface for their own
  clients' vacancies
- job-media operators leaving per-click aggregator economics who want to own
  their index, their data and their compliance story

## Problem

Ad-driven aggregators optimize for clicks, not accuracy: stale postings stay
up, pay figures drift from the source record, and republication rights are
murky. In Japan this is no longer just a quality problem — the 令和4年 (2022)
職業安定法 amendment regulates 募集情報等提供事業者 (job-information
aggregators) by name: 的確表示義務 (5条の4, keep postings accurate and
current) and the 特定募集情報等提供事業者届出制 (43条の2). Operators need the
compliance layer as software, not as a manual review queue.

## Offer

- posting ingest and normalization from source feeds (employer-direct,
  partner feeds, crawls the operator has rights to)
- per-jurisdiction job-advertising regulatory assessment with official
  citations (JPN/USA/GBR/DEU seeded; extensible)
- governed publication: the Job Search Portal Governor HARD-blocks stale
  vacancies, pay misstatements, unconsented republication and discriminatory
  ad content — a human approves every publication/delisting; nothing is
  autonomous
- freshness monitoring and governed delisting
- immutable audit ledger (the evidence for 的確表示義務 compliance)
- the public search UI (this repo's `web/`, live demo below)

The core promise: **nothing reaches the public index that the governor would
reject, and the operator can prove it** — that is the differentiation against
both closed job-board SaaS and click-optimized aggregators.

## Funnel (demo → fork → certified operator)

1. **Demo** — the live GitHub Pages demo
   (<https://cloud-itonami.github.io/cloud-itonami-isic-6399/>) shows the
   governed index AND the refused postings with their HARD-check reasons; the
   verdicts are recomputed from the actual `.cljc` compliance code at page
   build, which is the sales pitch in one screen.
2. **Fork / self-host** — AGPL; run the actor + UI for one sector or region.
3. **届出 / registration** — the operator files its own jurisdiction
   registration (e.g. 特定募集情報等提供事業者届出); the actor's evidence
   checklists and ledger are the supporting record.
4. **itonami.cloud certification** — listed operators get leads and may run
   managed tenants (same trust ladder as every cloud-itonami venture).
5. **Placement handoff (optional expansion)** — an application on your
   board becomes a placement-desk candidacy via a HUMAN-CARRIED
   referral draft toward `cloud-itonami-isic-7810`, never a
   cross-actor call (superproject ADR-2607131000: separate governance
   domains, no PII in the public actor's store).

## Revenue

Operators can sell:

- self-host setup: one-time implementation fee (feed integration, jurisdiction
  catalog, sign-off paths)
- managed hosting: monthly platform fee per board (tenant)
- employer-side services: sponsored/featured listings, compliance-checked
  posting drafting
- source-feed onboarding: per-source integration + consent-register setup
- compliance package: audit export, 的確表示 evidence bundle, retention
- migration: import from an existing board/spreadsheet, with governor
  screening as the cleanup step

Example pricing shapes (adapt per country and support burden):

| Package | Customer | Price shape (example) |
|---|---|---|
| Self-host starter | association/municipality IT team | setup ¥300k–800k + optional support |
| Managed community board | one sector/region, ≤2k postings | ¥50k–150k/月 |
| Managed regulated | 届出事業者 running multiple boards | ¥200k+/月 + audit package |
| Sponsored listings | employers on a hosted board | ¥5k–30k/枠/月 (operator keeps margin) |
| Operator enablement | SI/consultant | training + certification |

**Market-anchored (2026-07-16)**: the ¥50k–150k/月 managed-board band above was checked against 6
real competitor SaaS products (`90-docs/pricing-intelligence/pricing-intelligence-ledger.edn`,
`run-id "pricing-intel-20260716-02"`) — Madgex ($500+/mo, sales-gated), JobBoard.io ($449–649/mo),
JBoard ($249–849/mo), WP Job Manager (free core + $16–39 add-ons), Adicio/CareerCast (opaque
turnkey), and Japan's engage (free board creation + ¥7,000/day ticketed distribution). Converting
the US comparables at ~¥150/$ lands at ¥37k–127k+/月, which the band already covers — no revision
needed. The finding also confirms free-then-monetize (AGPL self-host, in our case) is a proven
funnel shape in both the US and JP markets, not a pattern unique to this repo. See
`90-docs/pricing-intelligence/README.md` for the full methodology and the other 28 verticals it
covers.

**Subscribe (2026-07-16, ADR-2607161745)**: a live Stripe Payment Link for the Managed Starter tier
(¥80,000/月 flat) is available now — [**subscribe to Managed Job Board — Starter**](https://buy.stripe.com/bJe9AS74n1dmcOQcEvbMQ0b).
This is a no-code Stripe-hosted checkout; nothing in this repo's code changed. After subscribing,
contact gftdcojp via an [operator-interest issue](https://github.com/cloud-itonami/cloud-itonami-isic-6399/issues/new?template=operator-interest.yml)
to arrange managed-tenant setup (there is no automated onboarding yet — this is a manual
fulfillment step today).

## Unit Economics (worked example, illustrative)

One managed community board (≤2k postings, JPN only):

- infrastructure: static UI + actor runtime ≈ ¥5k–15k/月 (the index is a
  static artifact; the actor runs at publish/delist time, not per search)
- LLM cost: proposals only at ingest/assess/publish — postings/month × a few
  yen; bounded because search itself never calls a model
- human approval labor: the real cost driver — every publication is a human
  sign-off; at ~1 min/posting, 500 new postings/月 ≈ 8–9 h/月 of operator time
- support + incident: budget 5 h/月 until feeds stabilize

At ¥100k/月 managed fee, gross margin stays >70% once feed onboarding is done;
the business scales with number of boards, not postings, because approval
labor is the bottleneck — which is why sponsored-listing revenue (no marginal
approval cost beyond the posting itself) is the expansion lever.

Track per operator: setup hours per board, approvals/hour, % postings
HARD-held (feed quality signal), sponsored-listing attach rate, churn.

## Open Participation

Anyone may fork, run the demo, self-host, submit patches, publish
jurisdiction catalog entries (with official citations — never fabricated),
and build a local operator business. itonami.cloud certification is required
before an operator is listed, receives leads, or runs managed tenants under
the platform brand.

## Operator Trust Levels

| Level | Capability |
|---|---|
| Contributor | patches, docs, jurisdiction entries, examples |
| Self-host operator | runs their own board, no platform endorsement |
| Certified operator | listed on itonami.cloud after review |
| Managed operator | may receive leads and operate customer boards |
| Core maintainer | can approve changes to governor, security and governance |

## Trust Controls
- a posting the source has closed is never published; advertised pay always equals the source's own record; publication outside source consent is blocked; discriminatory advertisements are never published
- a publication or delisting the governor refuses is never pushed to the public index
- every publication, delisting, hold and approval path is auditable
- sensitive operating and personal data stays outside Git

## Non-Negotiables

- Do not commit real employer/source/job-seeker data.
- Do not bypass the Job Search Portal Governor for production publications or
  delistings.
- Do not crawl sources whose terms/law forbid republication and mark them
  consent-verified.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator.
- Jurisdiction registration (届出 etc.) is the operator's own legal duty; the
  software is the evidence scaffold, not the licence.
