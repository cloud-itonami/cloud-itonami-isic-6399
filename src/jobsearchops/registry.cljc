(ns jobsearchops.registry
  "Pure-function posting-publication + posting-delisting record
  construction -- an append-only job-search-portal book-of-record
  draft.

  Like every sibling actor's registry, there is no single international
  reference-number standard for a publication or delisting record --
  every portal/jurisdiction assigns its own reference format. This
  namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the
  same honest, non-fabricating discipline `jobsearchops.facts` uses.

  `displayed-compensation-matches-claim?` is an HONEST reapplication
  of the SAME ground-truth-recompute DISCIPLINE `employmentops.
  registry`'s own `placement-fee-matches-claim?`, `practiceops.
  registry`'s own `fee-total-matches-claim?` and `hospitalityops.
  registry`'s own `folio-total-matches-claim?` establish (verify a
  claimed monetary total against the entity's own recorded quantity x
  unit fields), reapplied to a posting's displayed-compensation line
  rather than a placement-fee, professional-fee or folio line -- not
  claimed as new code, though no literal code is shared (different
  domain).

  Two GROUND-TRUTH SHAPES, dispatched on which fields a posting
  carries (`range-shaped?`):
    - EXACT (hand-authored/demo data): `:source-hourly-wage` x
      `:source-monthly-hours` must equal `:displayed-compensation` --
      the original check, unchanged.
    - RANGE (real job-board data -- `jobsearchops.ingest`): real
      postings almost always disclose a pay RANGE, never a committed
      hourly-rate x monthly-hours pair (see that ns's own docstring
      for why forcing the EXACT shape here would mean this namespace
      itself fabricating the missing half). `:displayed-compensation-
      min`/`:displayed-compensation-max` must fall within (bounds-
      inclusive) the posting's own `:source-compensation-min`/
      `:source-compensation-max` -- still a ground-truth-recompute
      check, still nothing invented, just checking containment in a
      disclosed range instead of equality with a disclosed product.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real source feed or search index. It builds the RECORD
  an operator would keep, not the act of publishing or delisting a
  posting itself (that is `jobsearchops.operation`'s `:posting/
  publish`/`:posting/delist`, always human-gated -- see README
  `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the portal operator's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn compute-displayed-compensation
  "The ground-truth monthly compensation for `posting`'s own
  `:source-hourly-wage` and `:source-monthly-hours` -- a single flat
  wage x hours calculation, not a full payroll engine. EXACT shape
  only -- see `displayed-compensation-within-source-range?` for the
  RANGE shape (ns docstring)."
  [{:keys [source-hourly-wage source-monthly-hours]}]
  (* (double source-hourly-wage) (double source-monthly-hours)))

(defn- range-shaped?
  "A posting is RANGE-shaped (real job-board data) when it carries
  its own disclosed `:source-compensation-min`/`:source-compensation-
  max`, as opposed to EXACT-shaped (`:source-hourly-wage` x
  `:source-monthly-hours`, hand-authored/demo data)."
  [{:keys [source-compensation-min source-compensation-max]}]
  (and (some? source-compensation-min) (some? source-compensation-max)))

(defn displayed-compensation-within-source-range?
  "For a RANGE-shaped posting: do the posting's own `:displayed-
  compensation-min`/`:displayed-compensation-max` fall within
  (bounds-inclusive) the SAME posting's own `:source-compensation-
  min`/`:source-compensation-max`? A pure ground-truth check against
  the posting's own source-recorded range -- same discipline as
  `compute-displayed-compensation`'s exact-product recompute, just
  containment instead of equality, because a real source only ever
  discloses a range (see ns docstring)."
  [{:keys [source-compensation-min source-compensation-max
           displayed-compensation-min displayed-compensation-max]}]
  (and (some? displayed-compensation-min) (some? displayed-compensation-max)
       (<= (double source-compensation-min) (double displayed-compensation-min))
       (<= (double displayed-compensation-min) (double displayed-compensation-max))
       (<= (double displayed-compensation-max) (double source-compensation-max))))

(defn displayed-compensation-matches-claim?
  "Does `posting`'s own displayed compensation match its own source
  record's ground truth? Dispatches on `range-shaped?` -- RANGE-shaped
  postings (real job-board data) via
  `displayed-compensation-within-source-range?`, EXACT-shaped postings
  (hand-authored/demo data) via the original wage x hours recompute.
  See ns docstring for why this is an honest reapplication of the SAME
  discipline every sibling actor's own cost/total-matching check
  establishes, not a new concept."
  [posting]
  (if (range-shaped? posting)
    (displayed-compensation-within-source-range? posting)
    (== (double (:displayed-compensation posting)) (compute-displayed-compensation posting))))

(defn compensation-summary
  "Human-readable ground-truth trace for `posting`'s own displayed
  compensation vs its own source record, correct for either shape --
  used by the LLM advisor's proposal rationale and the governor's
  violation detail so neither has to know which shape it's looking at
  (and neither crashes calling the other shape's arithmetic)."
  [posting]
  (if (range-shaped? posting)
    (str "displayed=[" (:displayed-compensation-min posting) "," (:displayed-compensation-max posting)
         "] source-range=[" (:source-compensation-min posting) "," (:source-compensation-max posting) "]")
    (str "displayed=" (:displayed-compensation posting)
         " independent-recompute=" (compute-displayed-compensation posting))))

(defn register-publication
  "Validate + construct the POSTING-PUBLICATION registration DRAFT --
  the portal operator's own act of publishing a real posting into the
  public search index. Pure function -- does not touch any real search
  index; it builds the RECORD an operator would keep. `jobsearchops.
  governor` independently re-verifies the posting's own staleness/
  compensation/consent/ad-content ground truth, and blocks a
  double-publication of the same record, before this is ever allowed
  to commit."
  [posting-id jurisdiction sequence]
  (when-not (and posting-id (not= posting-id ""))
    (throw (ex-info "publication: posting_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "publication: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "publication: sequence must be >= 0" {})))
  (let [publication-number (str (str/upper-case jurisdiction) "-PUB-" (zero-pad sequence 6))
        record {"record_id" publication-number
                "kind" "publication-draft"
                "posting_id" posting-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "publication_number" publication-number
     "certificate" (unsigned-certificate "PostingPublication" publication-number publication-number)}))

(defn register-delisting
  "Validate + construct the POSTING-DELISTING registration DRAFT --
  the portal operator's own act of removing a real posting from the
  public search index (the 的確表示義務 currency duty's other half:
  keeping the index free of postings whose vacancy has closed). Pure
  function -- does not touch any real search index; it builds the
  RECORD an operator would keep. `jobsearchops.governor` independently
  blocks a double-delisting of the same record before this is ever
  allowed to commit."
  [posting-id jurisdiction sequence]
  (when-not (and posting-id (not= posting-id ""))
    (throw (ex-info "delisting: posting_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "delisting: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "delisting: sequence must be >= 0" {})))
  (let [delisting-number (str (str/upper-case jurisdiction) "-DLS-" (zero-pad sequence 6))
        record {"record_id" delisting-number
                "kind" "delisting-draft"
                "posting_id" posting-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "delisting_number" delisting-number
     "certificate" (unsigned-certificate "PostingDelisting" delisting-number delisting-number)}))

(defn register-correction
  "Validate + construct the POSTING-CORRECTION registration DRAFT --
  the portal operator's own act of updating a LIVE posting's public
  content (職業安定法5条の4: the operator corrects posted information
  on the 求人者's request / when it is no longer accurate). Unlike
  publication/delisting there is no double-actuation guard: a posting
  may legitimately be corrected more than once; what gates it is
  `jobsearchops.governor`'s posting-not-live guard plus the SAME
  content checks a fresh publication passes (a correction may not
  introduce a stale vacancy, a pay mismatch, unconsented content or a
  discriminatory ad). Pure function -- builds the RECORD an operator
  would keep."
  [posting-id jurisdiction sequence]
  (when-not (and posting-id (not= posting-id ""))
    (throw (ex-info "correction: posting_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "correction: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "correction: sequence must be >= 0" {})))
  (let [correction-number (str (str/upper-case jurisdiction) "-COR-" (zero-pad sequence 6))
        record {"record_id" correction-number
                "kind" "correction-draft"
                "posting_id" posting-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "correction_number" correction-number
     "certificate" (unsigned-certificate "PostingCorrection" correction-number correction-number)}))

(defn register-referral
  "Validate + construct the APPLICATION-REFERRAL DRAFT (superproject
  ADR-2607131000): the record a human agency operator carries into
  `cloud-itonami-isic-7810`'s candidacy intake. Carries the posting
  reference and an APPLICANT REFERENCE (an operator-held pointer --
  never PII payload in this public actor's store). Not an actuation:
  committing it changes nothing public; the carry is the human's act.
  `jobsearchops.governor` refuses a referral without the applicant's
  own consent flag or against a posting that is not live."
  [posting-id jurisdiction applicant-ref sequence]
  (when-not (and posting-id (not= posting-id ""))
    (throw (ex-info "referral: posting_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "referral: jurisdiction required" {})))
  (when-not (and applicant-ref (not= applicant-ref ""))
    (throw (ex-info "referral: applicant_ref required" {})))
  (when (< sequence 0)
    (throw (ex-info "referral: sequence must be >= 0" {})))
  (let [referral-number (str (str/upper-case jurisdiction) "-REF-" (zero-pad sequence 6))
        record {"record_id" referral-number
                "kind" "referral-draft"
                "posting_id" posting-id
                "jurisdiction" jurisdiction
                "applicant_ref" applicant-ref
                "target" "cloud-itonami-isic-7810"
                "immutable" true}]
    {"record" record "referral_number" referral-number
     "certificate" (unsigned-certificate "ApplicationReferral" referral-number referral-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
