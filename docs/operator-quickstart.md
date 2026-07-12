# Operator Quickstart — your own governed job board, fork to published

This walks the shortest path from forking this repo to a published,
governor-checked job board on your own GitHub Pages. It is the concrete
version of `docs/business-model.md`'s funnel step 2 (fork / self-host).

## 0. Layout

The build scripts resolve the `kotoba-lang` libraries as sibling
checkouts. Standalone (outside the monorepo), clone them next to your
fork once:

```bash
mkdir -p board/kotoba-lang && cd board
git clone https://github.com/<you>/cloud-itonami-isic-6399 cloud-itonami/cloud-itonami-isic-6399
for r in html css langchain langgraph; do
  git clone --depth 1 "https://github.com/kotoba-lang/$r" "kotoba-lang/$r"
done
npm install nbb        # anywhere on your PATH / a package.json near the repo
```

(Inside the com-junkawasaki/root monorepo the siblings already exist and
`node_modules/.bin/nbb` is at the superproject root — the commands below
work as-is.)

## 1. Write your postings

```bash
cd cloud-itonami/cloud-itonami-isic-6399/web
cp postings.example.edn postings.edn
$EDITOR postings.edn        # your own postings; field notes in the example file
```

Record shape and semantics are documented in `postings.example.edn`.
Jurisdictions must exist in `jobsearchops.facts/catalog`
(JPN/USA/GBR/DEU/FRA/KOR seeded) — extending the catalog is one map
entry **citing a real official source** (see the README's jurisdiction-coverage section; never fabricate
one).

## 2. Generate the board through the real actor

```bash
nbb --classpath "../src:../../../kotoba-lang/html/src:../../../kotoba-lang/css/src:../../../kotoba-lang/langchain/src:../../../kotoba-lang/langgraph/src" \
  generate.cljs postings.edn
```

Every posting is assessed and submitted for publication through the
actual OperationActor: what the Job Search Portal Governor holds
(stale vacancy, pay mismatch, missing source consent, discriminatory ad
content, uncatalogued jurisdiction) lands in the page's transparency
table with its real verdict; only governor-passed postings reach the
index; the audit ledger on the page is the real append-only record of
the build.

Check it:

```bash
nbb verify_operator.cljs     # exercises the example file end-to-end
open ../docs/index.html      # or serve docs/ locally
```

## 3. Publish

Commit `docs/` and enable GitHub Pages (Settings → Pages → deploy from
branch, `main` `/docs`) — or serve `docs/` from any static host. The
page is fully static: no server, no database, no build step for
visitors.

## 4. Your legal obligations (not the software's)

- Japan: operating a 募集情報等提供事業 may require the
  特定募集情報等提供事業者届出 (職業安定法43条の2), and the 的確表示義務
  (5条の4) applies to you as operator. The actor's evidence checklists
  and ledger are your supporting record, not your registration.
- Only aggregate sources you have the right to republish
  (`:requires-source-consent?` / `:source-consent-verified?` model this;
  the consent register behind them is yours to keep).
- Re-run the generator whenever your source data changes — a stale board
  is exactly what the 的確表示義務 forbids. In practice:
  `.github/workflows/regenerate.yml` already does this nightly (rebuild
  through the actor from `web/postings.edn`, verify, commit only on
  change — a posting whose source closed is HARD-held on the next build
  and thereby leaves the index). If your org disables Actions, run the
  same generate/verify/commit trio from any scheduler.

## 5. Running the full Indeed stack (this board + the placement desk)

Pair this board with
[`cloud-itonami-isic-7810`](https://github.com/cloud-itonami/cloud-itonami-isic-7810)
(its own quickstart covers the private placement side). The seam is
superproject ADR-2607131000: an application on your board becomes an
`:application/refer` record (applicant's own consent required, live
posting required, applicant REFERENCE only — no PII here), which a
human carries into the desk's `:candidacy/intake` with the referral's
record id in the patch. No store, governor or API is shared; the
end-to-end story is the join of the two ledgers. Both public demos
show the same record id (`JPN-REF-000000`) leaving one ledger and
arriving in the other.

## 6. Where this goes next

- managed hosting, sponsored listings, compliance packages: see
  `docs/business-model.md` (pricing shapes, unit economics).
- itonami.cloud certification (leads + managed tenants): see the
  Operator Trust Levels there.
- production-grade store (Datomic / kotoba-server) and real feed
  integrations: the Store/Advisor seams in `docs/adr/0001-architecture.md`.
