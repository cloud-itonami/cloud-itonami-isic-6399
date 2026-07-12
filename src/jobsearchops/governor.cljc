(ns jobsearchops.governor
  "Job Search Portal Governor -- the independent compliance layer that
  earns the JobSearch-LLM the right to commit. The LLM has no notion
  of jurisdictional job-advertising or database-right law, whether a
  posting's own displayed pay actually equals the source record's own
  hourly wage times monthly hours, whether the underlying vacancy has
  actually been closed by the source, whether a third-party source's
  own republication consent has actually been verified for a source
  that requires it, whether an advertisement's own content relies on a
  protected characteristic, or when an act stops being a draft and
  becomes a real-world posting publication or delisting, so this MUST
  be a separate system able to *reject* a proposal and fall back to
  HOLD.

  `:itonami.blueprint/governor` is `:job-search-portal-governor`,
  grep-verified UNIQUE fleet-wide -- no naming-collision precedent
  question, a fresh independent build following the SAME governed-
  actor architecture (langgraph StateGraph + independent Governor +
  Phase 0->3 rollout) established by `cloud-itonami-isic-6511`.

  This blueprint's own text (docs/business-model.md's own Trust
  Controls: 'a posting the source has closed is never published;
  advertised pay always equals the source's own record; publication
  outside source consent is blocked; discriminatory advertisements are
  never published') names exactly the checks below.

  Six named checks plus two double-actuation guards, ALL HARD
  violations: a human approver CANNOT override them. The confidence/
  actuation gate is SOFT: it asks a human to look (low confidence /
  actuation), and the human may approve -- but see `jobsearchops.
  phase`: for `:stake :actuation/publish-posting`/`:actuation/
  delist-posting` (a real publication or delisting) NO phase ever
  allows auto-commit either. Two independent layers agree that
  actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source
                                       (`jobsearchops.facts`), or
                                       invent one?
    2. Evidence incomplete         -- for `:posting/publish`/
                                       `:posting/delist`, has the
                                       jurisdiction actually been
                                       assessed with a full evidence
                                       checklist on file?
    3. Stale vacancy               -- for `:posting/publish`,
                                       INDEPENDENTLY verify the
                                       posting's own `:source-vacancy-
                                       closed?` is false -- the
                                       FLAGSHIP domain-unique check
                                       this vertical adds
                                       (grep-verified: zero fleet hits
                                       for 'vacancy' as a governor
                                       check concept; kinship to
                                       `marketdata`/6311's own stale-
                                       print and the fleet's
                                       assessment-stale checks is
                                       acknowledged -- the staleness
                                       DISCIPLINE is not new, but the
                                       genuine-vacancy statutory
                                       grounding is). Grounded in real
                                       job-advertising accuracy law:
                                       Japan's own 職業安定法5条の4
                                       (的確表示義務 -- the 令和4年
                                       amendment provision written for
                                       募集情報等提供事業者, i.e. exactly
                                       this business, enforced by
                                       MHLW), the US's FTC Act §5
                                       (deceptive practices), the UK's
                                       Conduct of Employment Agencies
                                       and Employment Businesses
                                       Regulations 2003 reg. 27
                                       (advertisements must relate to
                                       genuine vacancies, enforced by
                                       EAS), and Germany's UWG §5
                                       (misleading commercial
                                       practices). Evaluated
                                       UNCONDITIONALLY (every
                                       publication needs its own
                                       source-vacancy currency
                                       checked).
    4. Ad content discriminatory   -- for `:posting/publish`,
                                       INDEPENDENTLY verify the
                                       posting's own `:ad-content-
                                       discriminatory?` is false -- an
                                       HONEST reapplication of
                                       `employmentops`/7810's own
                                       matching-basis-discriminatory
                                       DISCIPLINE to advertisement
                                       CONTENT, under the ad-specific
                                       statutory provisions (Japan's
                                       own 男女雇用機会均等法5条 and
                                       労働施策総合推進法9条, the US's
                                       Title VII §704(b) and ADEA
                                       §4(e) -- both specifically
                                       about notices/advertisements --
                                       the UK's Equality Act 2010, and
                                       Germany's AGG §11
                                       (Ausschreibung)) -- documented
                                       as a reapplication, not claimed
                                       as new.
    5. Displayed compensation
       mismatch                      -- for `:posting/publish`,
                                       INDEPENDENTLY recompute whether
                                       the posting's own `:displayed-
                                       compensation` equals
                                       `source-hourly-wage x
                                       source-monthly-hours`
                                       (`jobsearchops.registry/
                                       displayed-compensation-matches-
                                       claim?`) -- an HONEST
                                       reapplication of the SAME
                                       ground-truth-recompute
                                       DISCIPLINE `employmentops.
                                       registry`'s/`practiceops.
                                       registry`'s/`hospitalityops.
                                       registry`'s own checks
                                       establish, reapplied to a
                                       posting's displayed-pay line --
                                       not claimed as new.
    6. Source consent unverified   -- for `:posting/publish`, for a
                                       posting whose own record
                                       declares `:requires-source-
                                       consent? true` (i.e. this
                                       posting was aggregated from a
                                       third-party source whose
                                       republication legally/
                                       contractually requires verified
                                       consent -- direct employer
                                       submissions don't), INDEPENDENTLY
                                       check whether `:source-consent-
                                       verified?` is true. A new
                                       MEMBER of the fleet's existing
                                       consent-check FAMILY
                                       (customer-data-consent /
                                       prior-informed-consent /
                                       guardian-consent), applied to
                                       source-republication consent --
                                       documented as family kinship,
                                       not absolute novelty.
                                       CONDITIONAL on the posting's
                                       own `:requires-source-consent?`
                                       ground truth. Grounded in real
                                       database-right/copyright law:
                                       Japan's own 著作権法12条の2
                                       (database works) plus the
                                       職業安定法43条の2 特定募集情報等
                                       提供事業者 regime, the US's 17
                                       U.S.C. §103/§106, the UK's
                                       Copyright and Rights in
                                       Databases Regulations 1997,
                                       Germany's UrhG §§87a–87e,
                                       France's CPI L341-1 ff. and
                                       Korea's 저작권법91–98조 -- ALL
                                       SIX seeded jurisdictions
                                       actually have a real regime
                                       here, reported honestly.
    7. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:posting/publish`/
                                       `:posting/delist` (REAL acts)
                                       -> escalate.

  Two more guards, double-publication/double-delisting prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-published-violations`/
  `already-delisted-violations` refuse to publish/delist the SAME
  posting twice, off dedicated `:published?`/`:delisted?` facts (never
  a `:status` value) -- the SAME 'check a dedicated boolean, not
  status' discipline every prior governor's guards establish, informed
  by `cloud-itonami-isic-6492`'s status-lifecycle bug
  (ADR-2607071320)."
  (:require [jobsearchops.facts :as facts]
            [jobsearchops.registry :as registry]
            [jobsearchops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Publishing a real posting into the public index, delisting a real
  posting from it, and correcting a LIVE posting's public content
  (職業安定法5条の4's correction duty) are the real-world actuation
  events this actor performs."
  #{:actuation/publish-posting :actuation/delist-posting :actuation/correct-posting})

(def ^:private content-gated-ops
  "Ops whose committed effect changes what the public index shows for a
  posting -- a fresh publication and a correction of a live posting
  both pass the SAME content gates (stale vacancy / discriminatory ad /
  pay recompute / source consent): a correction may not introduce what
  a publication would have been refused for."
  #{:posting/publish :posting/correct})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:posting/publish`/`:posting/delist`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's job-advertising/database-right
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :posting/publish :posting/delist :posting/correct :application/refer} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:posting/publish`/`:posting/delist`, the jurisdiction's
  required source/compensation/publication/consent evidence must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:posting/publish :posting/delist :posting/correct :application/refer} op)
    (let [p (store/posting st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction p) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(求人元記録/労働条件表示記録/掲載記録/転載許諾記録等)が充足していない状態での提案"}]))))

(defn- stale-vacancy-violations
  "For `:posting/publish`, INDEPENDENTLY verify the posting's own
  `:source-vacancy-closed?` is false -- the flagship domain-unique
  check this vertical adds (的確表示義務: a posting whose underlying
  vacancy the source has already closed/filled must not be
  published). Evaluated UNCONDITIONALLY (every publication needs its
  own source-vacancy currency checked)."
  [{:keys [op subject]} st]
  (when (contains? content-gated-ops op)
    (let [p (store/posting st subject)]
      (when (true? (:source-vacancy-closed? p))
        [{:rule :stale-vacancy
          :detail (str subject " の求人元は既に募集を終了している -- 的確表示義務により掲載提案は進められない")}]))))

(defn- ad-content-discriminatory-violations
  "For `:posting/publish`, INDEPENDENTLY verify the posting's own
  `:ad-content-discriminatory?` is false -- an honest reapplication of
  `employmentops`/7810's matching-basis-discriminatory discipline to
  advertisement content. Evaluated UNCONDITIONALLY (every publication
  needs its own ad content checked)."
  [{:keys [op subject]} st]
  (when (contains? content-gated-ops op)
    (let [p (store/posting st subject)]
      (when (true? (:ad-content-discriminatory? p))
        [{:rule :ad-content-discriminatory
          :detail (str subject " の広告内容が保護属性に基づく条件を含む可能性がある")}]))))

(defn- displayed-compensation-mismatch-violations
  "For `:posting/publish`, INDEPENDENTLY recompute whether the
  posting's own displayed compensation equals source-hourly-wage x
  source-monthly-hours via `jobsearchops.registry/
  displayed-compensation-matches-claim?` -- needs no proposal
  inspection or stored-verdict lookup at all, an honest reapplication
  of the same discipline every sibling actor's own cost/total-matching
  check establishes."
  [{:keys [op subject]} st]
  (when (contains? content-gated-ops op)
    (let [p (store/posting st subject)]
      (when-not (registry/displayed-compensation-matches-claim? p)
        [{:rule :displayed-compensation-mismatch
          :detail (str subject " の表示賃金(" (:displayed-compensation p)
                      ")が独立再計算値(" (registry/compute-displayed-compensation p) ")と一致しない")}]))))

(defn- source-consent-unverified-violations
  "For `:posting/publish`, for a posting whose own record declares
  `:requires-source-consent? true`, INDEPENDENTLY check whether
  `:source-consent-verified?` is true -- a new member of the fleet's
  existing consent-check family, CONDITIONAL on the posting's own
  `:requires-source-consent?` ground truth (not every posting is
  aggregated from a consent-requiring third-party source)."
  [{:keys [op subject]} st]
  (when (contains? content-gated-ops op)
    (let [p (store/posting st subject)]
      (when (and (true? (:requires-source-consent? p))
                 (not (true? (:source-consent-verified? p))))
        [{:rule :source-consent-unverified
          :detail (str subject " は求人元の転載許諾確認を要するが未確認 -- 掲載提案は進められない")}]))))

(defn- applicant-consent-missing-violations
  "For `:application/refer` (ADR-2607131000), the applicant's own
  consent-to-refer flag must be true -- no consent, no referral, HARD.
  This is operator-attested REQUEST input rather than store ground
  truth (the applicant is deliberately NOT an entity in this public
  actor's store; only a reference travels), so the check reads the
  request -- the same posture as the spec-basis check reading the
  proposal's citations."
  [{:keys [op applicant-consent?]} _st]
  (when (= op :application/refer)
    (when-not (true? applicant-consent?)
      [{:rule :applicant-consent-missing
        :detail "応募者本人の紹介同意が無い -- referral は作成できない"}])))

(defn- already-published-violations
  "For `:posting/publish`, refuses to publish the SAME posting record
  twice, off a dedicated `:published?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :posting/publish)
    (when (store/posting-already-published? st subject)
      [{:rule :already-published
        :detail (str subject " は既に掲載済み")}])))

(defn- posting-not-live-violations
  "For `:posting/correct`, the posting must be LIVE (published and not
  delisted): 職業安定法5条の4's correction duty is about information
  the public can currently see -- an unpublished draft is corrected by
  plain ingest, and a delisted posting has nothing public to correct.
  Off the dedicated `:published?`/`:delisted?` booleans, same
  discipline as the double-actuation guards."
  [{:keys [op subject]} st]
  (when (contains? #{:posting/correct :application/refer} op)
    (let [p (store/posting st subject)]
      (when-not (and (:published? p) (not (:delisted? p)))
        [{:rule :posting-not-live
          :detail (str subject " は公開中でない(未掲載または取下げ済み) -- 訂正/紹介の対象が存在しない")}]))))

(defn- already-delisted-violations
  "For `:posting/delist`, refuses to delist the SAME posting twice,
  off a dedicated `:delisted?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :posting/delist)
    (when (store/posting-already-delisted? st subject)
      [{:rule :already-delisted
        :detail (str subject " は既に掲載取下げ済み")}])))

(defn check
  "Censors a JobSearch-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (stale-vacancy-violations request st)
                           (ad-content-discriminatory-violations request st)
                           (displayed-compensation-mismatch-violations request st)
                           (source-consent-unverified-violations request st)
                           (posting-not-live-violations request st)
                           (applicant-consent-missing-violations request st)
                           (already-published-violations request st)
                           (already-delisted-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
