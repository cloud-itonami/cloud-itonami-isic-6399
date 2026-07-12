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
  wage x hours calculation, not a full payroll engine."
  [{:keys [source-hourly-wage source-monthly-hours]}]
  (* (double source-hourly-wage) (double source-monthly-hours)))

(defn displayed-compensation-matches-claim?
  "Does `posting`'s own `:displayed-compensation` equal the
  independently recomputed `compute-displayed-compensation`? A pure
  ground-truth check against the posting's own source-recorded fields
  -- see ns docstring for why this is an honest reapplication of the
  SAME discipline every sibling actor's own cost/total-matching check
  establishes, not a new concept."
  [{:keys [displayed-compensation] :as posting}]
  (== (double displayed-compensation) (compute-displayed-compensation posting)))

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

(defn append [history result]
  (conj (vec history) (get result "record")))
