(ns jobsearchops.store
  "SSoT for the meta-job-search actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/jobsearchops/store_contract_test.clj), which is the whole
  point: the actor, the Job Search Portal Governor and the audit
  ledger never know which SSoT they run on.

  Like `employmentops`/7810's own `candidacy`, the primary entity here
  is a `posting` -- posting-publication and posting-delisting
  actuation events apply SEQUENTIALLY to the SAME posting record
  (publish first, delist later), matching the employment/freight/
  quarry/agronomy/hospitality/practice cluster's own sequential entity
  shape. Dedicated double-actuation-guard booleans (`:published?`/
  `:delisted?`, never a `:status` value).

  The ledger stays append-only on every backend: 'which posting was
  screened for a closed source vacancy, a pay misstatement, a missing
  source consent or discriminatory ad content, which posting was
  published, which posting was delisted, on what jurisdictional basis,
  approved by whom' is always a query over an immutable log -- the
  audit trail a community job board or workforce program trusting an
  operator needs, and the evidence an operator needs if a publication
  or a delisting is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [jobsearchops.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (posting [s id])
  (all-postings [s])
  (assessment-of [s posting-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (publication-history [s] "the append-only posting-publication history (jobsearchops.registry drafts)")
  (delisting-history [s] "the append-only posting-delisting history (jobsearchops.registry drafts)")
  (correction-history [s] "the append-only posting-correction history (jobsearchops.registry drafts)")
  (referral-history [s] "the append-only application-referral history (ADR-2607131000 drafts)")
  (next-publication-sequence [s jurisdiction] "next publication-number sequence for a jurisdiction")
  (next-delisting-sequence [s jurisdiction] "next delisting-number sequence for a jurisdiction")
  (next-correction-sequence [s jurisdiction] "next correction-number sequence for a jurisdiction")
  (next-referral-sequence [s jurisdiction] "next referral-number sequence for a jurisdiction")
  (posting-already-published? [s posting-id] "has this posting already been published?")
  (posting-already-delisted? [s posting-id] "has this posting already been delisted?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-postings [s postings] "replace/seed the posting directory (map id->posting)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained posting set covering both actuation
  lifecycles (publish, delist) plus the governor's own new checks, so
  the actor + tests run offline."
  []
  {:postings
   {"posting-1" {:id "posting-1" :title "Warehouse Associate" :employer "Kita Logistics"
                 :source "employer-direct"
                 :source-hourly-wage 1500 :source-monthly-hours 160 :displayed-compensation 240000.0
                 :ad-content-discriminatory? false :source-vacancy-closed? false
                 :requires-source-consent? false :source-consent-verified? false
                 :published? false :delisted? false
                 :jurisdiction "JPN" :status :ingested}
    "posting-2" {:id "posting-2" :title "Warehouse Associate" :employer "Atlantis Freight"
                 :source "employer-direct"
                 :source-hourly-wage 1400 :source-monthly-hours 160 :displayed-compensation 224000.0
                 :ad-content-discriminatory? false :source-vacancy-closed? false
                 :requires-source-consent? false :source-consent-verified? false
                 :published? false :delisted? false
                 :jurisdiction "ATL" :status :ingested}
    "posting-3" {:id "posting-3" :title "Machine Operator" :employer "Minami Manufacturing"
                 :source "employer-direct"
                 :source-hourly-wage 1600 :source-monthly-hours 165 :displayed-compensation 300000.0
                 :ad-content-discriminatory? false :source-vacancy-closed? false
                 :requires-source-consent? false :source-consent-verified? false
                 :published? false :delisted? false
                 :jurisdiction "JPN" :status :ingested}
    "posting-4" {:id "posting-4" :title "Delivery Driver" :employer "Higashi Transport"
                 :source "employer-direct"
                 :source-hourly-wage 1550 :source-monthly-hours 160 :displayed-compensation 248000.0
                 :ad-content-discriminatory? true :source-vacancy-closed? false
                 :requires-source-consent? false :source-consent-verified? false
                 :published? false :delisted? false
                 :jurisdiction "JPN" :status :ingested}
    "posting-5" {:id "posting-5" :title "Line Cook" :employer "Nishi Dining"
                 :source "board-crawl"
                 :source-hourly-wage 1450 :source-monthly-hours 160 :displayed-compensation 232000.0
                 :ad-content-discriminatory? false :source-vacancy-closed? false
                 :requires-source-consent? true :source-consent-verified? false
                 :published? false :delisted? false
                 :jurisdiction "JPN" :status :ingested}
    "posting-6" {:id "posting-6" :title "Line Cook" :employer "Chuo Kitchen"
                 :source "partner-feed"
                 :source-hourly-wage 1500 :source-monthly-hours 160 :displayed-compensation 240000.0
                 :ad-content-discriminatory? false :source-vacancy-closed? false
                 :requires-source-consent? true :source-consent-verified? true
                 :published? false :delisted? false
                 :jurisdiction "JPN" :status :ingested}
    "posting-7" {:id "posting-7" :title "Retail Clerk" :employer "Minato Retail"
                 :source "employer-direct"
                 :source-hourly-wage 1300 :source-monthly-hours 150 :displayed-compensation 195000.0
                 :ad-content-discriminatory? false :source-vacancy-closed? true
                 :requires-source-consent? false :source-consent-verified? false
                 :published? false :delisted? false
                 :jurisdiction "JPN" :status :ingested}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- publish-posting!
  "Backend-agnostic `:posting/mark-published` -- looks up the posting
  via the protocol and drafts the publication record, and returns
  {:result .. :posting-patch ..} for the caller to persist."
  [s posting-id]
  (let [p (posting s posting-id)
        seq-n (next-publication-sequence s (:jurisdiction p))
        result (registry/register-publication posting-id (:jurisdiction p) seq-n)]
    {:result result
     :posting-patch {:published? true
                     :publication-number (get result "publication_number")}}))

(defn- delist-posting!
  "Backend-agnostic `:posting/mark-delisted` -- looks up the posting
  via the protocol and drafts the delisting record, and returns
  {:result .. :posting-patch ..} for the caller to persist."
  [s posting-id]
  (let [p (posting s posting-id)
        seq-n (next-delisting-sequence s (:jurisdiction p))
        result (registry/register-delisting posting-id (:jurisdiction p) seq-n)]
    {:result result
     :posting-patch {:delisted? true
                     :delisting-number (get result "delisting_number")}}))

(defn- correct-posting!
  "Backend-agnostic `:posting/mark-corrected` -- looks up the posting
  via the protocol and drafts the correction record, and returns
  {:result .. :posting-patch ..} for the caller to persist. The patch
  stamps the LATEST correction number; the full history stays in
  `correction-history` (a posting may be corrected more than once)."
  [s posting-id]
  (let [p (posting s posting-id)
        seq-n (next-correction-sequence s (:jurisdiction p))
        result (registry/register-correction posting-id (:jurisdiction p) seq-n)]
    {:result result
     :posting-patch {:correction-number (get result "correction_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (posting [_ id] (get-in @a [:postings id]))
  (all-postings [_] (sort-by :id (vals (:postings @a))))
  (assessment-of [_ posting-id] (get-in @a [:assessments posting-id]))
  (ledger [_] (:ledger @a))
  (publication-history [_] (:publication-records @a))
  (delisting-history [_] (:delisting-records @a))
  (correction-history [_] (:correction-records @a))
  (referral-history [_] (:referral-records @a))
  (next-publication-sequence [_ jurisdiction] (get-in @a [:publication-sequences jurisdiction] 0))
  (next-delisting-sequence [_ jurisdiction] (get-in @a [:delisting-sequences jurisdiction] 0))
  (next-correction-sequence [_ jurisdiction] (get-in @a [:correction-sequences jurisdiction] 0))
  (next-referral-sequence [_ jurisdiction] (get-in @a [:referral-sequences jurisdiction] 0))
  (posting-already-published? [_ posting-id] (boolean (get-in @a [:postings posting-id :published?])))
  (posting-already-delisted? [_ posting-id] (boolean (get-in @a [:postings posting-id :delisted?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :posting/upsert
      (swap! a update-in [:postings (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :posting/mark-published
      (let [posting-id (first path)
            {:keys [result posting-patch]} (publish-posting! s posting-id)
            jurisdiction (:jurisdiction (posting s posting-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:publication-sequences jurisdiction] (fnil inc 0))
                       (update-in [:postings posting-id] merge posting-patch)
                       (update :publication-records registry/append result))))
        result)

      :posting/mark-delisted
      (let [posting-id (first path)
            {:keys [result posting-patch]} (delist-posting! s posting-id)
            jurisdiction (:jurisdiction (posting s posting-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:delisting-sequences jurisdiction] (fnil inc 0))
                       (update-in [:postings posting-id] merge posting-patch)
                       (update :delisting-records registry/append result))))
        result)

      :posting/mark-corrected
      (let [posting-id (first path)
            {:keys [result posting-patch]} (correct-posting! s posting-id)
            jurisdiction (:jurisdiction (posting s posting-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:correction-sequences jurisdiction] (fnil inc 0))
                       (update-in [:postings posting-id] merge posting-patch)
                       (update :correction-records registry/append result))))
        result)

      ;; application referral (ADR-2607131000): records only -- nothing on
      ;; the posting changes, and multiple referrals per posting are normal.
      :referral/record
      (let [posting-id (first path)
            jurisdiction (:jurisdiction (posting s posting-id))
            seq-n (next-referral-sequence s jurisdiction)
            result (registry/register-referral posting-id jurisdiction (:applicant-ref value) seq-n)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:referral-sequences jurisdiction] (fnil inc 0))
                       (update :referral-records registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-postings [s postings] (when (seq postings) (swap! a assoc :postings postings)) s))

(defn seed-db
  "A MemStore seeded with the demo posting set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :publication-sequences {} :publication-records []
                           :delisting-sequences {} :delisting-records []
                           :correction-sequences {} :correction-records []
                           :referral-sequences {} :referral-records []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment payloads, ledger facts,
  publication/delisting records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:posting/id                       {:db/unique :db.unique/identity}
   :assessment/posting-id            {:db/unique :db.unique/identity}
   :ledger/seq                       {:db/unique :db.unique/identity}
   :publication-record/seq           {:db/unique :db.unique/identity}
   :delisting-record/seq             {:db/unique :db.unique/identity}
   :correction-record/seq            {:db/unique :db.unique/identity}
   :referral-record/seq              {:db/unique :db.unique/identity}
   :publication-sequence/jurisdiction {:db/unique :db.unique/identity}
   :delisting-sequence/jurisdiction   {:db/unique :db.unique/identity}
   :correction-sequence/jurisdiction  {:db/unique :db.unique/identity}
   :referral-sequence/jurisdiction    {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- posting->tx [{:keys [id title employer source
                            source-hourly-wage source-monthly-hours displayed-compensation
                            ad-content-discriminatory? source-vacancy-closed?
                            requires-source-consent? source-consent-verified?
                            published? delisted?
                            jurisdiction status publication-number delisting-number
                            correction-number]}]
  (cond-> {:posting/id id}
    title                                    (assoc :posting/title title)
    employer                                 (assoc :posting/employer employer)
    source                                   (assoc :posting/source source)
    source-hourly-wage                       (assoc :posting/source-hourly-wage source-hourly-wage)
    source-monthly-hours                     (assoc :posting/source-monthly-hours source-monthly-hours)
    displayed-compensation                   (assoc :posting/displayed-compensation displayed-compensation)
    (some? ad-content-discriminatory?)       (assoc :posting/ad-content-discriminatory? ad-content-discriminatory?)
    (some? source-vacancy-closed?)           (assoc :posting/source-vacancy-closed? source-vacancy-closed?)
    (some? requires-source-consent?)         (assoc :posting/requires-source-consent? requires-source-consent?)
    (some? source-consent-verified?)         (assoc :posting/source-consent-verified? source-consent-verified?)
    (some? published?)                       (assoc :posting/published? published?)
    (some? delisted?)                        (assoc :posting/delisted? delisted?)
    jurisdiction                             (assoc :posting/jurisdiction jurisdiction)
    status                                   (assoc :posting/status status)
    publication-number                       (assoc :posting/publication-number publication-number)
    delisting-number                         (assoc :posting/delisting-number delisting-number)
    correction-number                        (assoc :posting/correction-number correction-number)))

(def ^:private posting-pull
  [:posting/id :posting/title :posting/employer :posting/source
   :posting/source-hourly-wage :posting/source-monthly-hours :posting/displayed-compensation
   :posting/ad-content-discriminatory? :posting/source-vacancy-closed?
   :posting/requires-source-consent? :posting/source-consent-verified?
   :posting/published? :posting/delisted?
   :posting/jurisdiction :posting/status :posting/publication-number :posting/delisting-number
   :posting/correction-number])

(defn- pull->posting [m]
  (when (:posting/id m)
    {:id (:posting/id m) :title (:posting/title m) :employer (:posting/employer m)
     :source (:posting/source m)
     :source-hourly-wage (:posting/source-hourly-wage m)
     :source-monthly-hours (:posting/source-monthly-hours m)
     :displayed-compensation (:posting/displayed-compensation m)
     :ad-content-discriminatory? (boolean (:posting/ad-content-discriminatory? m))
     :source-vacancy-closed? (boolean (:posting/source-vacancy-closed? m))
     :requires-source-consent? (boolean (:posting/requires-source-consent? m))
     :source-consent-verified? (boolean (:posting/source-consent-verified? m))
     :published? (boolean (:posting/published? m)) :delisted? (boolean (:posting/delisted? m))
     :jurisdiction (:posting/jurisdiction m) :status (:posting/status m)
     :publication-number (:posting/publication-number m)
     :delisting-number (:posting/delisting-number m)
     :correction-number (:posting/correction-number m)}))

(defrecord DatomicStore [conn]
  Store
  (posting [_ id]
    (pull->posting (d/pull (d/db conn) posting-pull [:posting/id id])))
  (all-postings [_]
    (->> (d/q '[:find [?id ...] :where [?e :posting/id ?id]] (d/db conn))
         (map #(pull->posting (d/pull (d/db conn) posting-pull [:posting/id %])))
         (sort-by :id)))
  (assessment-of [_ posting-id]
    (dec* (d/q '[:find ?p . :in $ ?pid
                :where [?a :assessment/posting-id ?pid] [?a :assessment/payload ?p]]
              (d/db conn) posting-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (publication-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :publication-record/seq ?s] [?e :publication-record/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (delisting-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :delisting-record/seq ?s] [?e :delisting-record/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (correction-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :correction-record/seq ?s] [?e :correction-record/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (referral-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :referral-record/seq ?s] [?e :referral-record/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-publication-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :publication-sequence/jurisdiction ?j] [?e :publication-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-delisting-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :delisting-sequence/jurisdiction ?j] [?e :delisting-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-correction-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :correction-sequence/jurisdiction ?j] [?e :correction-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-referral-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :referral-sequence/jurisdiction ?j] [?e :referral-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (posting-already-published? [s posting-id]
    (boolean (:published? (posting s posting-id))))
  (posting-already-delisted? [s posting-id]
    (boolean (:delisted? (posting s posting-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :posting/upsert
      (d/transact! conn [(posting->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/posting-id (first path) :assessment/payload (enc payload)}])

      :posting/mark-published
      (let [posting-id (first path)
            {:keys [result posting-patch]} (publish-posting! s posting-id)
            jurisdiction (:jurisdiction (posting s posting-id))
            next-n (inc (next-publication-sequence s jurisdiction))]
        (d/transact! conn
                     [(posting->tx (assoc posting-patch :id posting-id))
                      {:publication-sequence/jurisdiction jurisdiction :publication-sequence/next next-n}
                      {:publication-record/seq (count (publication-history s)) :publication-record/record (enc (get result "record"))}])
        result)

      :posting/mark-delisted
      (let [posting-id (first path)
            {:keys [result posting-patch]} (delist-posting! s posting-id)
            jurisdiction (:jurisdiction (posting s posting-id))
            next-n (inc (next-delisting-sequence s jurisdiction))]
        (d/transact! conn
                     [(posting->tx (assoc posting-patch :id posting-id))
                      {:delisting-sequence/jurisdiction jurisdiction :delisting-sequence/next next-n}
                      {:delisting-record/seq (count (delisting-history s)) :delisting-record/record (enc (get result "record"))}])
        result)

      :posting/mark-corrected
      (let [posting-id (first path)
            {:keys [result posting-patch]} (correct-posting! s posting-id)
            jurisdiction (:jurisdiction (posting s posting-id))
            next-n (inc (next-correction-sequence s jurisdiction))]
        (d/transact! conn
                     [(posting->tx (assoc posting-patch :id posting-id))
                      {:correction-sequence/jurisdiction jurisdiction :correction-sequence/next next-n}
                      {:correction-record/seq (count (correction-history s)) :correction-record/record (enc (get result "record"))}])
        result)

      :referral/record
      (let [posting-id (first path)
            jurisdiction (:jurisdiction (posting s posting-id))
            seq-n (next-referral-sequence s jurisdiction)
            result (registry/register-referral posting-id jurisdiction (:applicant-ref value) seq-n)
            next-n (inc seq-n)]
        (d/transact! conn
                     [{:referral-sequence/jurisdiction jurisdiction :referral-sequence/next next-n}
                      {:referral-record/seq (count (referral-history s)) :referral-record/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-postings [s postings]
    (when (seq postings) (d/transact! conn (mapv posting->tx (vals postings)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:postings ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [postings]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-postings s postings))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo posting set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
