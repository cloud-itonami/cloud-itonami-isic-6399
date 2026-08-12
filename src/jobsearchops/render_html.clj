(ns jobsearchops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo. This namespace drives
  the REAL actor stack -- `jobsearchops.operation` (a compiled langgraph
  StateGraph) -> `jobsearchops.governor` -> `jobsearchops.store` --
  through a scenario extended from this repo's own `jobsearchops.sim`
  demo driver (`clojure -M:dev:run`, run BEFORE this file was written to
  confirm it produces a sensible ledger against the real seeded posting
  ids `posting-1`..`posting-7`), then renders the resulting store, audit
  ledger and coordination-artifact registers.

  NOTHING on the page is hand-typed. Every posting id, title, employer,
  jurisdiction, wage figure, sequence number, disposition, hold rule and
  hold detail string is read back out of the real store/ledger the run
  produced. The action gate, the phase ladder, the job-advertising
  spec-basis table and the source-republication consent table are derived
  from the live `jobsearchops.governor` / `.phase` / `.facts` /
  `.registry` vars rather than described in prose, so they cannot drift
  away from the code. The scenario INPUTS (which op against which posting
  id) are of course authored -- that is what a scenario is -- but no
  OUTPUT is.

  Where the page cannot honestly show something, it says so instead of
  inventing it: `approver-attribution` re-checks, at render time and
  PER REGISTER, whether the human approver's id actually reached the
  SSoT, and prints the answer register by register (see below -- the
  answer here is genuinely MIXED, not a flat no).

  ## Personal data

  This actor is a 募集情報等提供事業者 (job-posting aggregator), so its
  most interesting holds are the personal-data ones, and they are
  surfaced first on the page:

    - `:applicant-consent-missing` -- no referral without the applicant's
      own consent to be referred (HARD, un-overridable).
    - `:ad-content-discriminatory` -- an advertisement whose content
      relies on a protected characteristic is never published
      (男女雇用機会均等法5条 / 労働施策総合推進法9条 / Title VII §704(b) /
      ADEA §4(e) / Equality Act 2010 / AGG §11).

  The actor deliberately holds NO applicant PII: a referral carries an
  opaque operator-held `applicant-ref` pointer and nothing else (see
  `jobsearchops.jobsearchopsllm/propose-referral`). The two refs used
  below, `applicant-ref-001` / `applicant-ref-002`, are this repo's own
  `sim` driver's refs, used verbatim -- no candidate name is invented
  anywhere, because the design has nowhere to put one.

  ## Why this scenario

  It walks a clean posting through its whole public lifecycle (ingest ->
  a publication attempt REFUSED for missing evidence -> jurisdiction
  assessment -> publication -> delisting), then a source-consent-
  requiring posting through ingest -> assessment -> publication -> a
  source wage change -> correction -> an application referral, and then
  exercises ALL TEN of the Job Search Portal Governor's HARD rules.

  Nine of the ten holds are ISOLATED -- the run is arranged so that
  exactly ONE rule fires, which is what makes each row evidence about
  that rule rather than about the scenario:

     1. `:evidence-incomplete`             -- publish `posting-1` BEFORE its
                                              jurisdiction has been assessed
     2. `:no-spec-basis`                   -- assess `posting-2` (ATL, absent
                                              from `facts/catalog`)
     3. `:displayed-compensation-mismatch` -- `posting-3`, displayed 300000.0
                                              vs 1600 x 165 = 264000.0
     4. `:ad-content-discriminatory`       -- `posting-4`
     5. `:source-consent-unverified`       -- `posting-5` (board-crawl)
     6. `:stale-vacancy`                   -- `posting-7`, source already closed
     7. `:already-published`               -- `posting-1` a second time
     8. `:already-delisted`                -- `posting-1` a second time
     9. `:posting-not-live`                -- correct `posting-1` AFTER it was
                                              delisted (nothing public to correct)
    10. `:applicant-consent-missing`       -- refer against `posting-6` with no
                                              applicant consent flag

  ## Determinism

  Every collaborator in the path is pure or deterministic: the mock
  advisor is a `case` over the request, the registry's reference numbers
  are jurisdiction-scoped zero-padded sequences, and no code in `src/`
  reads a clock or a RNG. Every set or map iterated for the page
  (`facts/catalog`, `phase/phases`, `phase/write-ops`,
  `registry/jurisdiction-currency`) is explicitly sorted here rather than
  iterated in hash order. The page therefore contains NO timestamp and NO
  generated id, and two consecutive runs are byte-identical (verified
  with `cmp`).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [jobsearchops.facts :as facts]
            [jobsearchops.governor :as governor]
            [jobsearchops.operation :as op]
            [jobsearchops.phase :as phase]
            [jobsearchops.registry :as registry]
            [jobsearchops.store :as store]))

(def ^:private operator
  "The same operator context this repo's own `sim` driver uses."
  {:actor-id "op-1" :actor-role :portal-operator :phase 3})

;; ----------------------------- driving the REAL actor -----------------------------

(defn- record!
  "Append one finished graph run to the ordered run log. `result` is the
  raw `langgraph.graph/run*` return value -- everything rendered from it
  is real actor output."
  [runs tid request result]
  (swap! runs conj {:tid tid
                    :request request
                    :proposal (get-in result [:state :proposal])
                    :verdict (get-in result [:state :verdict])
                    :audit (vec (get-in result [:state :audit]))
                    :disposition (get-in result [:state :disposition])})
  result)

(defn- exec!
  "One operation, no human in the loop (auto-commit or HARD hold)."
  [runs actor tid request]
  (record! runs tid request
           (g/run* actor {:request request :context operator} {:thread-id tid})))

(defn- approve!
  "One operation the phase gate / governor escalates, then resumed by a
  human approval. The resumed result carries the FULL accumulated audit
  (`:audit`'s reducer is `into`, restored from the checkpointer), so only
  the resumed result is recorded."
  [runs actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid})
  (record! runs tid request
           (g/run* actor {:approval {:status :approved :by "op-1"}}
                   {:thread-id tid :resume? true})))

(defn run-demo!
  "Runs a fresh seeded store through the scenario described in the ns
  docstring. Returns `{:db :runs}` -- `:runs` is the ordered log of real
  graph results, `:db` the real store the actor wrote."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        runs  (atom [])]

    ;; --- posting-1 (JPN, employer-direct, clean): the whole public lifecycle ---
    (exec! runs actor "t01"
           {:op :posting/ingest :subject "posting-1"
            :patch {:id "posting-1" :title "Warehouse Associate"}})
    ;; The evidence gate comes FIRST: publishing before the jurisdiction has
    ;; been assessed is refused even though the posting itself is clean.
    (exec! runs actor "t02" {:op :posting/publish :subject "posting-1"})
    (approve! runs actor "t03" {:op :jurisdiction/assess :subject "posting-1"})
    (approve! runs actor "t04" {:op :posting/publish :subject "posting-1"})
    (approve! runs actor "t05" {:op :posting/delist :subject "posting-1"})

    ;; --- posting-6 (JPN, partner-feed, source consent required AND verified) ---
    (exec! runs actor "t06"
           {:op :posting/ingest :subject "posting-6"
            :patch {:id "posting-6" :title "Line Cook"}})
    (approve! runs actor "t07" {:op :jurisdiction/assess :subject "posting-6"})
    (approve! runs actor "t08" {:op :posting/publish :subject "posting-6"})
    ;; the source's own wage record changes; plain ingest normalizes it ...
    (exec! runs actor "t09"
           {:op :posting/ingest :subject "posting-6"
            :patch {:id "posting-6" :source-hourly-wage 1550
                    :displayed-compensation 248000.0}})
    ;; ... and the 訂正 (職業安定法5条の4) is the governed act that changes
    ;; what the PUBLIC sees.
    (approve! runs actor "t10" {:op :posting/correct :subject "posting-6"})
    ;; an application referral carried by a human into isic-7810 (ADR-2607131000)
    (approve! runs actor "t11"
              {:op :application/refer :subject "posting-6"
               :applicant-ref "applicant-ref-001"
               :applicant-consent? true})

    ;; --- the ten HARD rules, nine of them isolated to a single rule ---
    (exec! runs actor "t12" {:op :jurisdiction/assess :subject "posting-2" :no-spec? true})

    (approve! runs actor "t13" {:op :jurisdiction/assess :subject "posting-3"})
    (exec! runs actor "t14" {:op :posting/publish :subject "posting-3"})

    (approve! runs actor "t15" {:op :jurisdiction/assess :subject "posting-4"})
    (exec! runs actor "t16" {:op :posting/publish :subject "posting-4"})

    (approve! runs actor "t17" {:op :jurisdiction/assess :subject "posting-5"})
    (exec! runs actor "t18" {:op :posting/publish :subject "posting-5"})

    (approve! runs actor "t19" {:op :jurisdiction/assess :subject "posting-7"})
    (exec! runs actor "t20" {:op :posting/publish :subject "posting-7"})

    (exec! runs actor "t21" {:op :posting/publish :subject "posting-1"})
    (exec! runs actor "t22" {:op :posting/delist :subject "posting-1"})
    ;; posting-1 is published AND delisted -- there is nothing public to correct
    (exec! runs actor "t23" {:op :posting/correct :subject "posting-1"})
    ;; a referral with no applicant consent on file
    (exec! runs actor "t24"
           {:op :application/refer :subject "posting-6"
            :applicant-ref "applicant-ref-002"})

    {:db db :runs @runs}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str
  "A keyword rendered with its namespace intact. This domain's ops are
  namespaced (`:posting/correct` vs `:application/refer`), so dropping the
  namespace would collapse distinct ops into the same bare verb."
  [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- fixed2
  "Two decimal places, locale-independent. Plain `format` would follow the
  default locale and emit `0,90` on a comma-decimal machine, which would
  make the page's bytes depend on where it was built."
  [v]
  (String/format java.util.Locale/ROOT "%.2f" (into-array Object [(double v)])))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- n-cell [v] (str "<span class=\"num\">" (esc v) "</span>"))

(defn- dash [] "<span class=\"muted\">&mdash;</span>")

(defn- bool-cell [v]
  (if (true? v)
    "<span class=\"ok\">yes</span>"
    "<span class=\"muted\">no</span>"))

(defn- risk-cell
  "A boolean whose TRUE value is the dangerous one (stale vacancy,
  discriminatory ad, ...)."
  [v yes no]
  (if (true? v)
    (str "<span class=\"critical\">" yes "</span>")
    (str "<span class=\"ok\">" no "</span>")))

(defn- fact-of [audit t] (first (filter #(= t (:t %)) audit)))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- op-codes
  "A deterministic, sorted `<code>` list of an op set."
  [ops]
  (if (seq ops)
    (str/join " " (map #(code (kw-str %)) (sort-by kw-str ops)))
    "<span class=\"muted\">none</span>"))

(defn- section [title lead headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (rows body-rows) "\n      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; ----------------------------- derived classification -----------------------------

(defn- outcome
  "Classify one real run from its own audit trail. Never from a literal."
  [{:keys [audit disposition]}]
  (let [hold (fact-of audit :governor-hold)]
    (cond
      hold {:kind :hard-hold :violations (:violations hold)}

      (fact-of audit :approval-granted)
      {:kind :approved
       :reason (:reason (fact-of audit :approval-requested))
       :by (:by (fact-of audit :approval-granted))}

      (fact-of audit :approval-requested)
      {:kind :awaiting :reason (:reason (fact-of audit :approval-requested))}

      (= :commit disposition) {:kind :auto-commit}
      :else {:kind :other})))

(defn- outcome-cell [o]
  (case (:kind o)
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; "
                    (esc (str/join ", " (map (comp kw-str :rule) (:violations o))))
                    "</span>")
    :approved (str "<span class=\"ok\">escalated (" (esc (kw-str (:reason o)))
                   ") &rarr; approved by " (esc (:by o)) "</span>")
    :awaiting (str "<span class=\"warn\">awaiting human approval &middot; "
                   (esc (kw-str (:reason o))) "</span>")
    :auto-commit "<span class=\"ok\">auto-commit (governor-clean)</span>"
    "<span class=\"muted\">in progress</span>"))

(defn- detail-cell [o]
  (case (:kind o)
    :hard-hold (esc (str/join " / " (map :detail (:violations o))))
    :approved "<span class=\"muted\">human in the loop before commit</span>"
    :awaiting "<span class=\"muted\">paused at :request-approval</span>"
    (dash)))

(defn- holds
  "The HARD `:governor-hold` facts the run actually wrote to the ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

;; ----------------------------- approver attribution (MEASURED) -----------------------------

(defn- deep-key-names
  "Every key name appearing anywhere in a nested structure, as strings.
  Used to ask the SSoT what it actually holds instead of assuming."
  [x]
  (cond
    (map? x) (into (into #{} (map kw-str) (keys x))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

(def ^:private approver-key?
  #(contains? #{"approved-by" "approved_by" "approver" "approved_by_id"}
              (str/lower-case (str %))))

(defn- retains-approver?
  "Does any record in this register carry an approver key anywhere in its
  (possibly nested) structure?"
  [register]
  (boolean (some approver-key? (deep-key-names register))))

(defn- approver-attribution
  "MEASURED, per-register disclosure about where the human approver's id
  actually lives after a `:request-approval` handoff. Nothing here is
  asserted from reading the code -- every answer is a fresh scan of the
  real store this run produced, so the page self-corrects if the store is
  later changed.

  `operation`'s `:request-approval` node attaches the approver at
  `[:payload :approved-by]`. Whether that survives depends entirely on
  which branch of `store/commit-record!` handles the effect, and in THIS
  repo the answer differs by register -- which is exactly why it is
  measured rather than claimed."
  [db runs]
  (let [assessments (into {} (for [p (store/all-postings db)
                                   :let [a (store/assessment-of db (:id p))]
                                   :when a]
                               [(:id p) a]))
        registers [{:label "jurisdiction assessments"
                    :effect :assessment/set
                    :accessor "store/assessment-of"
                    :data (vec (vals (sort-by key assessments)))}
                   {:label "posting directory (SSoT)"
                    :effect :posting/upsert
                    :accessor "store/all-postings"
                    :data (vec (store/all-postings db))}
                   {:label "publication records"
                    :effect :posting/mark-published
                    :accessor "store/publication-history"
                    :data (vec (store/publication-history db))}
                   {:label "delisting records"
                    :effect :posting/mark-delisted
                    :accessor "store/delisting-history"
                    :data (vec (store/delisting-history db))}
                   {:label "correction records"
                    :effect :posting/mark-corrected
                    :accessor "store/correction-history"
                    :data (vec (store/correction-history db))}
                   {:label "application referral records"
                    :effect :referral/record
                    :accessor "store/referral-history"
                    :data (vec (store/referral-history db))}
                   {:label "audit ledger"
                    :effect nil
                    :accessor "store/ledger"
                    :data (vec (store/ledger db))}]]
    {:approvers (vec (sort (into #{} (keep #(:by (fact-of (:audit %) :approval-granted))) runs)))
     :assessments assessments
     :registers (mapv #(assoc % :retains? (retains-approver? (:data %))
                                :n (count (:data %)))
                      registers)
     :on-ledger? (boolean (some #(= :approval-granted (:t %)) (store/ledger db)))}))

(defn- attribution-section
  "Renders the approver-attribution disclosure from the MEASURED facts.
  The demo never prints an approver as though a register held one when it
  does not."
  [{:keys [approvers registers on-ledger?]}]
  (let [kept (filterv :retains? registers)
        lost (filterv (complement :retains?) registers)]
    (str "  <section class=\"card\">\n"
         "    <h2>Approver attribution &mdash; measured, register by register</h2>\n"
         "    <p class=\"muted\">Every row below is a fresh scan of the real store this run "
         "produced (each register is walked to arbitrary depth looking for an approver key), "
         "not a claim read off the source. In this repo the answer is genuinely MIXED, which is "
         "why it is measured: <code>operation</code>&rsquo;s <code>:request-approval</code> node "
         "attaches the approver at <code>[:payload :approved-by]</code>, and whether it survives "
         "depends on which branch of <code>store/commit-record!</code> handles the effect &mdash; "
         "only the <code>:assessment/set</code> branch persists <code>payload</code> itself.</p>\n"
         "    <table>\n"
         "      <thead><tr><th>Register</th><th>Accessor</th><th>Commit effect</th><th>Records</th>"
         "<th>Approver retained?</th></tr></thead>\n"
         "      <tbody>\n"
         (rows (for [{:keys [label accessor effect n retains?]} registers]
                 (row (esc label)
                      (code accessor)
                      (if effect (code (kw-str effect)) (dash))
                      (n-cell n)
                      (if retains?
                        "<span class=\"ok\">yes &middot; on record</span>"
                        "<span class=\"critical\">no &middot; audit only</span>"))))
         "\n      </tbody>\n    </table>\n"
         "    <p>"
         (if (empty? approvers)
           "This run produced no human approval, so there is no approver to attribute."
           (str "This run&rsquo;s approver(s): "
                (str/join " " (map code approvers))
                ". "
                (if (seq kept)
                  (str "<strong>Retained</strong> on: "
                       (esc (str/join ", " (map :label kept)))
                       " &mdash; there the approver is real stored data. ")
                  "")
                (if (seq lost)
                  (str "<strong>Not retained</strong> on: "
                       (esc (str/join ", " (map :label lost)))
                       ". For those registers <code>store/commit-record!</code> uses only "
                       "<code>path</code> (and, for <code>:posting/upsert</code>, "
                       "<code>value</code>) and never reads <code>payload</code> back out, so "
                       "the approver never reaches them. Where the <em>Operation dispositions</em> "
                       "table above reads &ldquo;approved by&rdquo; for one of those ops, that "
                       "name is joined from the run&rsquo;s own <code>:approval-granted</code> "
                       "audit fact &mdash; <strong>audit only &mdash; not retained in the "
                       "record</strong>. This page states that plainly rather than printing an "
                       "approver as though the register held one.")
                  "")))
         "</p>\n"
         "    <p class=\"muted\">The store&rsquo;s own append-only ledger carries an "
         "<code>:approval-granted</code> fact: "
         (if on-ledger?
           "<span class=\"ok\">yes</span>"
           (str "<span class=\"critical\">no</span> &mdash; <code>operation</code>&rsquo;s "
                "<code>:commit</code> node appends only its <code>:committed</code> fact, so the "
                "<code>:approval-granted</code> fact carrying <code>:by</code> lives in the run&rsquo;s "
                "in-memory audit channel and in the checkpointer, not in the durable ledger"))
         ".</p>\n"
         "  </section>\n")))

;; ----------------------------- sections (all derived) -----------------------------

(defn- posting-rows [db]
  (for [{:keys [id title employer source jurisdiction status
                published? delisted? source-vacancy-closed?
                ad-content-discriminatory? requires-source-consent?
                source-consent-verified? publication-number delisting-number
                correction-number]}
        (store/all-postings db)]
    (row (code id)
         (esc title)
         (esc employer)
         (code source)
         (esc jurisdiction)
         (esc (kw-str status))
         (risk-cell source-vacancy-closed? "source closed" "open at source")
         (risk-cell ad-content-discriminatory? "flagged" "clean")
         (if (true? requires-source-consent?)
           (if (true? source-consent-verified?)
             "<span class=\"ok\">required &middot; verified</span>"
             "<span class=\"critical\">required &middot; UNVERIFIED</span>")
           "<span class=\"muted\">not required</span>")
         (cond delisted? "<span class=\"warn\">delisted</span>"
               published? "<span class=\"ok\">live</span>"
               :else "<span class=\"muted\">not published</span>")
         (if publication-number (code publication-number) (dash))
         (if delisting-number (code delisting-number) (dash))
         (if correction-number (code correction-number) (dash)))))

(defn- compensation-rows
  "The governor's rule-5 ground truth, recomputed here through the SAME
  pure `jobsearchops.registry` fns the governor itself calls -- the page
  shows the arithmetic, not a verdict copied from somewhere."
  [db]
  (for [p (store/all-postings db)
        :let [{:keys [currency symbol]} (registry/compensation-unit p)
              money (fn [v] (if symbol (str symbol (esc v)) (str (esc v) " " (esc (or currency "?")))))]]
    (row (code (:id p))
         (if currency (code currency) "<span class=\"critical\">unknown</span>")
         (n-cell (money (:source-hourly-wage p)))
         (n-cell (:source-monthly-hours p))
         (n-cell (money (registry/compute-displayed-compensation p)))
         (n-cell (money (:displayed-compensation p)))
         (if (registry/displayed-compensation-matches-claim? p)
           "<span class=\"ok\">matches source record</span>"
           "<span class=\"critical\">MISMATCH &middot; publication held</span>"))))

(defn- run-rows [db runs]
  (for [{:keys [tid request] :as r} runs
        :let [o (outcome r)
              p (store/posting db (:subject request))]]
    (row (code tid)
         (code (kw-str (:op request)))
         (code (:subject request))
         (if p (esc (:jurisdiction p)) (dash))
         (if-let [s (:stake (:proposal r))] (code (kw-str s)) (dash))
         (n-cell (fixed2 (:confidence (:verdict r) 0.0)))
         (outcome-cell o)
         (detail-cell o))))

(defn- hold-rows
  "One row per HARD hold fact actually on the ledger, with the governor's
  own detail string verbatim."
  [db]
  (for [{:keys [op subject basis violations]} (holds db)]
    (row (esc (str/join ", " (map kw-str basis)))
         (code (kw-str op))
         (code subject)
         (n-cell (count violations))
         (esc (str/join " / " (map :detail violations))))))

(defn- gate-rows
  "The action gate, DERIVED from the live governor/phase vars plus the
  stake each op's REAL proposal carried in this run -- not a prose
  description that could drift away from the code."
  [runs]
  (let [auto3   (get-in phase/phases [3 :auto])
        content @#'governor/content-gated-ops
        ;; op -> the stake this run's own advisor proposals actually carried
        stakes  (reduce (fn [m {:keys [request proposal]}]
                          (cond-> m
                            (:stake proposal) (assoc (:op request) (:stake proposal))))
                        {} runs)]
    (for [o (sort-by kw-str phase/write-ops)
          :let [stake (get stakes o)
                first-write-phase (first (for [p (sort (keys phase/phases))
                                               :when (contains? (:writes (get phase/phases p)) o)]
                                           p))]]
      (row (code (kw-str o))
           (if first-write-phase (n-cell first-write-phase) "<span class=\"muted\">never</span>")
           (if (contains? auto3 o)
             "<span class=\"ok\">may auto-commit when governor-clean</span>"
             "<span class=\"warn\">human approval, every phase</span>")
           (if stake (code (kw-str stake)) (dash))
           (if (and stake (contains? governor/high-stakes stake))
             "<span class=\"warn\">always high-stakes &rarr; escalates</span>"
             "<span class=\"muted\">no</span>")
           (if (contains? content o)
             "<span class=\"warn\">yes &middot; stale / discriminatory / pay / consent re-checked</span>"
             "<span class=\"muted\">no</span>")))))

(defn- phase-rows []
  (for [p (sort (keys phase/phases))
        :let [{:keys [label writes auto]} (get phase/phases p)]]
    (row (n-cell p) (esc label) (op-codes writes) (op-codes auto))))

(defn- spec-basis-rows
  "The job-advertising regulatory catalog, read straight out of
  `jobsearchops.facts/catalog` (keys sorted for determinism)."
  []
  (for [iso3 (sort (keys facts/catalog))
        :let [{:keys [name owner-authority legal-basis provenance required-evidence]}
              (facts/spec-basis iso3)]]
    (row (code iso3)
         (esc name)
         (esc owner-authority)
         (esc legal-basis)
         (str "<a href=\"" (esc provenance) "\">" (esc provenance) "</a>")
         (n-cell (count required-evidence)))))

(defn- consent-basis-rows
  "The SEPARATE source-republication / database-right catalog, read
  straight out of `jobsearchops.facts/consent-spec-basis`."
  []
  (for [iso3 (sort (keys facts/catalog))
        :let [{:keys [consent-owner-authority consent-legal-basis consent-provenance]}
              (facts/consent-spec-basis iso3)]]
    (row (code iso3)
         (if consent-owner-authority (esc consent-owner-authority) (dash))
         (if consent-legal-basis (esc consent-legal-basis) (dash))
         (if consent-provenance
           (str "<a href=\"" (esc consent-provenance) "\">" (esc consent-provenance) "</a>")
           (dash)))))

(defn- evidence-rows
  "The per-jurisdiction evidence checklist the governor's
  `:evidence-incomplete` rule requires, read out of `facts`."
  []
  (for [iso3 (sort (keys facts/catalog))
        item (facts/evidence-checklist iso3)]
    (row (code iso3) (esc item))))

(defn- assessment-rows
  "The COMMITTED jurisdiction assessments, read back out of the store.
  This is the one register that retains the approver, and the page shows
  it because it measured it (see the attribution section)."
  [assessments]
  (for [[posting-id a] (sort-by key assessments)]
    (row (code posting-id)
         (code (:jurisdiction a))
         (n-cell (count (:checklist a)))
         (if (:spec-basis a)
           (str "<a href=\"" (esc (:spec-basis a)) "\">" (esc (:spec-basis a)) "</a>")
           "<span class=\"critical\">none</span>")
         (if (:approved-by a)
           (str "<span class=\"ok\">" (code (:approved-by a)) "</span>")
           (dash)))))

(defn- ledger-rows [db]
  (for [{:keys [t op subject disposition basis violations summary]} (store/ledger db)]
    (row (case t
           :committed "<span class=\"ok\">committed</span>"
           :governor-hold "<span class=\"critical\">governor-hold</span>"
           :approval-rejected "<span class=\"critical\">approval-rejected</span>"
           (esc (kw-str t)))
         (code (kw-str op))
         (code subject)
         (esc (kw-str disposition))
         (if (seq violations)
           (esc (str/join ", " (map (comp kw-str :rule) violations)))
           (esc (str/join " ; " (map kw-str basis))))
         (if summary (esc summary) (dash)))))

(defn- artifact-rows [history]
  (for [r history]
    (row (code (get r "record_id"))
         (esc (get r "kind"))
         (code (get r "posting_id"))
         (esc (get r "jurisdiction"))
         (bool-cell (get r "immutable")))))

(defn- referral-rows [history]
  (for [r history]
    (row (code (get r "record_id"))
         (code (get r "posting_id"))
         (esc (get r "jurisdiction"))
         (code (get r "applicant_ref"))
         (code (get r "target"))
         (bool-cell (get r "immutable")))))

;; ----------------------------- the document -----------------------------

(defn render
  "Renders the whole operator console from a `run-demo!` result. Takes no
  clock and no seed: identical input -> identical bytes."
  [{:keys [db runs]}]
  (let [ledger    (vec (store/ledger db))
        outcomes  (mapv outcome runs)
        hs        (holds db)
        committed (filterv #(= :committed (:t %)) ledger)
        approved  (filterv #(= :approved (:kind %)) outcomes)
        auto      (filterv #(= :auto-commit (:kind %)) outcomes)
        rule-kinds (into (sorted-set) (mapcat (fn [h] (map :rule (:violations h))) hs))
        isolated  (count (filterv #(= 1 (count (:violations %))) hs))
        cov       (facts/coverage)
        att       (approver-attribution db runs)]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-6399 &middot; job-search portal (募集情報等提供事業者) "
     "&mdash; operator console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"

     "<header class=\"bar\">\n"
     "  <h1>Job-search portal / 募集情報等提供事業者 (ISIC 6399) &mdash; Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">publication &amp; delisting are always a human&rsquo;s call</span> "
     "<span class=\"badge\">no applicant PII in this store</span></p>\n"
     "<p class=\"subtitle\">Generated at build time by <code>jobsearchops.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by actually running the compiled "
     "<code>jobsearchops.operation</code> StateGraph over a freshly seeded store. "
     "Every value below was read back out of that run &mdash; there is no mock markup on this page, "
     "and no timestamp, so successive regenerations are byte-identical.</p>\n"

     "<main>\n"

     (section "Run summary"
              "Counted from the real audit ledger and the real graph results, not asserted."
              ["Measure" "Count"]
              [(row "postings in the SSoT" (n-cell (count (store/all-postings db))))
               (row "graph runs in this scenario" (n-cell (count runs)))
               (row "<span class=\"ok\">auto-commits (governor-clean, phase 3)</span>" (n-cell (count auto)))
               (row "<span class=\"ok\">escalated &rarr; human-approved commits</span>" (n-cell (count approved)))
               (row "<span class=\"critical\">HARD governor holds (never reach a human)</span>" (n-cell (count hs)))
               (row "distinct HARD rules exercised" (n-cell (count rule-kinds)))
               (row "holds isolated to exactly ONE rule"
                    (n-cell (str isolated " / " (count hs))))
               (row "committed facts in the audit ledger" (n-cell (count committed)))
               (row "audit-ledger facts total" (n-cell (count ledger)))
               (row "confidence floor (<code>governor/confidence-floor</code>)" (n-cell governor/confidence-floor))
               (row "jurisdictions with an official job-advertising spec-basis"
                    (n-cell (str (:covered cov) " / " (:requested cov))))])

     "  <section class=\"card\">\n"
     "    <h2>Personal-data holds &mdash; the ones this domain exists to make</h2>\n"
     "    <p>A 募集情報等提供事業者 touches two kinds of personal data: the "
     "<em>applicant&rsquo;s</em> (who applied to what) and the <em>protected characteristics</em> "
     "an advertisement may not select on. Both are HARD rules here &mdash; a human approver "
     "cannot override either.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>What it refuses</th><th>Statutory grounding</th>"
     "<th>Exercised in this run</th></tr></thead>\n"
     "      <tbody>\n"
     (rows [(row (code ":applicant-consent-missing")
                 "An <code>:application/refer</code> without the applicant&rsquo;s own consent-to-refer flag. The referral record carries an OPAQUE operator-held <code>applicant_ref</code> pointer and no PII payload &mdash; the applicant is deliberately not an entity in this public actor&rsquo;s store, so only a reference travels to <code>cloud-itonami-isic-7810</code>."
                 "ADR-2607131000 (this workspace); consent is operator-attested REQUEST input, checked in <code>governor/applicant-consent-missing-violations</code>"
                 (if (contains? rule-kinds :applicant-consent-missing)
                   "<span class=\"critical\">yes &middot; HARD hold on the ledger</span>"
                   "<span class=\"muted\">no</span>"))
            (row (code ":ad-content-discriminatory")
                 "Publishing or correcting an advertisement whose content relies on a protected characteristic."
                 "男女雇用機会均等法5条 &middot; 労働施策総合推進法9条 &middot; Title VII &sect;704(b) &middot; ADEA &sect;4(e) &middot; Equality Act 2010 &middot; AGG &sect;11 (Ausschreibung)"
                 (if (contains? rule-kinds :ad-content-discriminatory)
                   "<span class=\"critical\">yes &middot; HARD hold on the ledger</span>"
                   "<span class=\"muted\">no</span>"))
            (row (code ":source-consent-unverified")
                 "Republishing a third-party source&rsquo;s posting whose republication consent has not been verified."
                 "著作権法12条の2 &middot; 職業安定法43条の2 &middot; 17 U.S.C. &sect;103/&sect;106 &middot; SI 1997/3032 &middot; UrhG &sect;&sect;87a&ndash;87e &middot; CPI L341-1 &middot; 저작권법 91&ndash;98조"
                 (if (contains? rule-kinds :source-consent-unverified)
                   "<span class=\"critical\">yes &middot; HARD hold on the ledger</span>"
                   "<span class=\"muted\">no</span>"))])
     "\n      </tbody>\n    </table>\n"
     "  </section>\n"

     (section "Posting directory"
              "The SSoT after the run. The vacancy-currency, ad-content and source-consent columns are
               the ground truth the Job Search Portal Governor re-checks INDEPENDENTLY at every
               publication and every correction &mdash; never the advisor's own confidence."
              ["Id" "Title" "Employer" "Source" "Juris." "Status" "Source vacancy" "Ad content"
               "Source consent" "Public state" "Publication #" "Delisting #" "Correction #"]
              (posting-rows db))

     (section "Displayed compensation &mdash; independent recompute"
              "Rule 5's ground truth, recomputed here through the SAME pure
               <code>jobsearchops.registry</code> functions the governor itself calls. The currency
               label comes from <code>registry/compensation-unit</code> and is ground truth carried
               alongside the figure, never a presentation guess: a JPY figure rendered with a
               <code>$</code> would itself be a false compensation claim (的確表示義務). No rate is
               ever applied to any figure anywhere in this actor."
              ["Posting" "Currency" "Source hourly wage" "Source monthly hours"
               "Independent recompute" "Displayed" "Verdict"]
              (compensation-rows db))

     (section "Operation dispositions (this run)"
              "One row per graph run, in order. The outcome and the hold reason are classified from
               each run's own audit trail; the detail text is the governor's own message, verbatim;
               the stake is the one the real advisor proposal carried. Where a row reads
               &ldquo;approved by&rdquo;, see the approver-attribution section below for whether that
               approver actually survived into the stored record."
              ["Thread" "Op" "Subject" "Juris." "Stake" "Confidence" "Outcome" "Governor detail"]
              (run-rows db runs))

     (section "HARD governor holds (this run)"
              "Every <code>:governor-hold</code> fact the run actually wrote to the append-only
               ledger. A HARD hold never reaches a human at all &mdash; there is no approval node
               downstream of it, so no approver can override one. The scenario is arranged so that
               nearly every hold fires exactly ONE rule, which is what makes each row evidence about
               that rule rather than about the scenario."
              ["Rule(s)" "Op" "Subject" "Rules fired" "Governor detail (verbatim)"]
              (hold-rows db))

     (attribution-section att)

     (section "Committed jurisdiction assessments"
              "The evidence checklists that actually committed, read back out of the store. This is
               the ONE register that retains the approver &mdash; and it is shown here because the
               section above MEASURED that, not because the code was read."
              ["Posting" "Jurisdiction" "Checklist items" "Cited spec-basis" "Approved by (on record)"]
              (assessment-rows (:assessments att)))

     (section "Action gate (Job Search Portal Governor)"
              "Derived from <code>phase/write-ops</code>, <code>phase/phases</code>,
               <code>governor/high-stakes</code> and <code>governor/content-gated-ops</code>, plus
               the stake each op's REAL advisor proposal carried in this run &mdash; if the code
               changes, this table changes. All ten governor rules are HARD: a human approver cannot
               override them. Two independent layers agree that publication and delisting are always
               a human call: the phase ladder never lists them in any <code>:auto</code> set, and the
               governor escalates on their stake regardless."
              ["Op" "Writable from phase" "At phase 3" "Observed stake" "Permanent escalation"
               "Content gates re-run"]
              (gate-rows runs))

     (section "Rollout phase ladder"
              "Read straight out of <code>jobsearchops.phase/phases</code>."
              ["Phase" "Label" "Writes allowed" "May auto-commit"]
              (phase-rows))

     (section "Job-advertising spec basis"
              "Read straight out of <code>jobsearchops.facts/catalog</code>. A jurisdiction absent
               from this table has NO spec-basis, full stop &mdash; the advisor must not fabricate
               one, and the governor holds if it tries (see the <code>:no-spec-basis</code> hold
               against <code>posting-2</code>, whose jurisdiction <code>ATL</code> is deliberately
               absent). This is a starting catalog, not a survey of all ~194 jurisdictions."
              ["Juris." "Name" "Owner authority" "Legal basis" "Official source" "Evidence items"]
              (spec-basis-rows))

     (section "Source-republication / database-right basis"
              "A SEPARATE regime from job-advertising accuracy law: whether a third-party source's
               postings may be republished at all is a database-right / copyright question,
               independent of whether the posting itself is accurate and lawful. Read straight out of
               <code>jobsearchops.facts/consent-spec-basis</code>."
              ["Juris." "Owner authority" "Legal basis" "Official source"]
              (consent-basis-rows))

     (section "Required evidence checklist"
              "What <code>:evidence-incomplete</code> actually requires, per jurisdiction, read out
               of <code>jobsearchops.facts/evidence-checklist</code>. A publication proposal against
               a jurisdiction whose checklist is not fully satisfied is held BEFORE any content gate
               is even reached."
              ["Juris." "Required evidence"]
              (evidence-rows))

     (section "Audit ledger"
              "Append-only decision facts the run actually wrote to the store."
              ["Fact" "Op" "Subject" "Disposition" "Basis / violated rule" "Summary"]
              (ledger-rows db))

     (section "Publication records"
              "Jurisdiction-scoped sequence numbers built by <code>jobsearchops.registry</code>. A
               DRAFT record an operator would keep &mdash; this actor never touches a real search
               index, and every certificate it produces is unsigned."
              ["Record id" "Kind" "Posting" "Jurisdiction" "Immutable"]
              (artifact-rows (store/publication-history db)))

     (section "Delisting records"
              "The 的確表示義務 currency duty's other half: keeping the index free of postings whose
               vacancy has closed. A wrongful delisting also harms a real source employer, so this is
               equally always a human's call."
              ["Record id" "Kind" "Posting" "Jurisdiction" "Immutable"]
              (artifact-rows (store/delisting-history db)))

     (section "Correction records"
              "職業安定法5条の4's correction duty. Unlike publication and delisting there is no
               double-actuation guard &mdash; a posting may legitimately be corrected more than once
               &mdash; but a correction passes the SAME content gates a fresh publication does: it
               may not introduce a stale vacancy, a pay mismatch, unconsented content or a
               discriminatory ad."
              ["Record id" "Kind" "Posting" "Jurisdiction" "Immutable"]
              (artifact-rows (store/correction-history db)))

     (section "Application referral drafts"
              "The paper a human agency operator carries into <code>cloud-itonami-isic-7810</code>'s
               candidacy intake (ADR-2607131000). Committing one changes nothing public; the carry
               IS the human act. Note what the record holds: a posting id and an OPAQUE
               <code>applicant_ref</code> &mdash; no name, no contact detail, no PII payload."
              ["Record id" "Posting" "Jurisdiction" "Applicant ref" "Target actor" "Immutable"]
              (referral-rows (store/referral-history db)))

     "</main>\n"
     "<footer>\n"
     "  <p>This actor NEVER decides who gets hired and NEVER holds an applicant&rsquo;s personal\n"
     "  data. It publishes, corrects and delists job ADVERTISEMENTS, and every one of those acts\n"
     "  is a human portal operator's call at every phase. Committing a proposal means a\n"
     "  coordination artifact was logged, never that a vacancy was verified to exist by anyone\n"
     "  other than the source that supplied it.</p>\n"
     "  <p>Regenerate: <code>clojure -M:dev:render-html</code></p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)
        commits (filterv #(= :committed (:t %)) (store/ledger db))]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    ;; ...and one that shows no commit at all is not evidence of an actor.
    (when (empty? commits)
      (throw (ex-info "no :committed fact on the ledger — refusing to write a console that shows no clean path"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count (into #{} (mapcat (fn [h] (map :rule (:violations h))) hs))) " distinct HARD rules, "
                  (count commits) " commits, "
                  (count runs) " requests, "
                  (count (store/publication-history db)) " publications, "
                  (count (store/delisting-history db)) " delistings, "
                  (count (store/correction-history db)) " corrections, "
                  (count (store/referral-history db)) " referrals)"))))
