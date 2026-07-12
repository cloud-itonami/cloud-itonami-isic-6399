(ns jobsearchops.phase
  "Phase 0->3 staged rollout for the meta-job-search actor.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-ingest  -- posting ingest allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment writes,
                                 still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:posting/ingest` (no public-facing
                                 risk yet) may auto-commit.
                                 `:posting/publish`/`:posting/delist`
                                 NEVER auto-commit, at any phase.

  `:posting/publish`/`:posting/delist` are deliberately ABSENT from
  every phase's `:auto` set, including phase 3 -- a permanent
  structural fact, not a rollout milestone still to come. Publishing a
  real posting into the public search index and delisting a real
  posting from it are the two real-world acts this actor performs;
  both are always a human portal operator's call. `jobsearchops.
  governor`'s `:actuation/publish-posting`/`:actuation/delist-posting`
  high-stakes gate enforces the same invariant independently -- two
  layers, not one, agree on this. Like every prior sibling's phase 3
  `:auto` set, this domain has only ONE member (`:posting/ingest`) --
  no separate no-public-facing-risk 'file' lifecycle distinct from the
  posting itself.")

(def read-ops  #{})
(def write-ops #{:posting/ingest :jurisdiction/assess :posting/publish :posting/delist})

;; NOTE the invariant: `:posting/publish`/`:posting/delist` are
;; members of `write-ops` (governor-gated like any write) but are
;; NEVER members of any phase's `:auto` set below. Do not add them
;; there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                          :auto #{}}
   1 {:label "assisted-ingest" :writes #{:posting/ingest}                                            :auto #{}}
   2 {:label "assisted-assess" :writes #{:posting/ingest :jurisdiction/assess}                        :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:posting/ingest}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:posting/publish`/`:posting/delist` are never auto-eligible at
    any phase, so they always escalate once the governor clears them
    (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Job Search Portal Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
