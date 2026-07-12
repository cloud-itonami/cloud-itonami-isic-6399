;; Headless verification harness for docs/search.cljs -- stubs the DOM
;; surface search.cljs touches (getElementById / textContent / value /
;; innerHTML / hidden / addEventListener), feeds it the REAL JSON block
;; extracted from the generated docs/index.html, loads search.cljs, and
;; asserts the rendered results + a simulated search interaction + the
;; page's build-time actor-run content (transparency table, audit
;; ledger, delisting story). nbb script (the org's Node-harnesses-in-nbb
;; rule -- no .mjs).
;;
;; Run (from this web/ directory):
;;   ../../../../node_modules/.bin/nbb verify_search.cljs
(require '["fs" :as fs])

(def html (fs/readFileSync "../docs/index.html" "utf8"))

(def json-block
  (let [m (re-find #"<script type=\"application/json\" id=\"postings-data\">(\[.*?\])</script>" html)]
    (or (second m) (throw (js/Error. "postings-data JSON block not found in docs/index.html")))))

;; ---- DOM stub --------------------------------------------------------------

(def listeners (atom {}))

(defn- el [id init]
  (let [o (js-obj)]
    (doseq [[k v] init] (aset o k v))
    (aset o "addEventListener"
          (fn [ev f] (swap! listeners assoc [id ev] f)))
    o))

(def elements
  {"postings-data" (el "postings-data" {"textContent" json-block})
   "q"             (el "q" {"value" ""})
   "jur"           (el "jur" {"value" ""})
   "src"           (el "src" {"value" ""})
   "results"       (el "results" {"innerHTML" ""})
   "empty"         (el "empty" {"hidden" true})})

(aset js/globalThis "document"
      (js-obj "getElementById" (fn [id] (get elements id))))

;; ---- load the real client code --------------------------------------------

(load-string (fs/readFileSync "../docs/search.cljs" "utf8"))

(defn- results-html [] (aget (get elements "results") "innerHTML"))
(defn- assert! [ok? msg]
  (if ok?
    (println "ok  " msg)
    (do (println "FAIL" msg) (js/process.exit 1))))

;; initial render: the two live-index postings (posting-6, posting-8)
(assert! (.includes (results-html) "Chuo Kitchen") "initial render shows posting-6")
(assert! (.includes (results-html) "Forklift Operator") "initial render shows posting-8 (ingested via the real actor)")
(assert! (.includes (results-html) "governor-passed") "cards carry the governor-passed badge")
(assert! (true? (aget (get elements "empty") "hidden")) "empty notice hidden while there are hits")

;; posting-1 was published AND then delisted -- it must NOT be in the index
(assert! (not (.includes json-block "posting-1")) "delisted posting-1 absent from search data")
(assert! (.includes html "JPN-DLS-000000") "delisting record number on the page (的確表示 story)")

;; simulate typing a query that matches only posting-8
(aset (get elements "q") "value" "forklift")
((get @listeners ["q" "input"]))
(assert! (.includes (results-html) "Forklift Operator") "query 'forklift' keeps posting-8")
(assert! (not (.includes (results-html) "Chuo Kitchen")) "query 'forklift' filters posting-6 out")

;; source facet: partner-feed keeps posting-6 only
(aset (get elements "q") "value" "")
(aset (get elements "src") "value" "partner-feed")
((get @listeners ["src" "change"]))
(assert! (.includes (results-html) "Chuo Kitchen") "source facet keeps partner-feed posting-6")
(assert! (not (.includes (results-html) "Forklift Operator")) "source facet filters employer-direct posting-8 out")
(aset (get elements "src") "value" "")

;; simulate a query with no hits -> empty notice shown
(aset (get elements "q") "value" "zzz-no-such-job")
((get @listeners ["q" "input"]))
(assert! (= "" (results-html)) "no-hit query renders no cards")
(assert! (false? (boolean (aget (get elements "empty") "hidden"))) "no-hit query reveals the empty notice")

;; held postings must NOT be in the search data at all
(assert! (not (.includes json-block "posting-7")) "stale-vacancy posting-7 absent from search data")
(assert! (not (.includes json-block "posting-4")) "discriminatory posting-4 absent from search data")

;; the transparency table carries the REAL run verdicts (all six holds)
(doseq [rule ["no-spec-basis" "stale-vacancy" "ad-content-discriminatory"
              "displayed-compensation-mismatch" "source-consent-unverified"
              "already-published"]]
  (assert! (.includes html rule) (str "hold rule '" rule "' present in transparency table")))

;; the audit ledger section is the REAL append-only record of the build-time runs
(assert! (.includes html "監査台帳") "audit ledger section present")
(assert! (.includes html "op=:posting/delist") "ledger has the delist fact")
(assert! (.includes html "basis=[:stale-vacancy]") "ledger has the stale-vacancy hold fact")

;; the correction lifecycle (ADR-0002): posting-6 was corrected after a
;; source wage change -- record on the card, fact in the ledger
(assert! (.includes json-block "JPN-COR-000000") "corrected posting-6 carries its correction number")
(assert! (.includes json-block "248,000") "posting-6's pay reflects the corrected source truth")
(assert! (.includes html "op=:posting/correct") "ledger has the correct fact")

(println "verify_search: all assertions passed")
