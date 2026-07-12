;; Headless verification harness for docs/search.cljs -- stubs the DOM
;; surface search.cljs touches (getElementById / textContent / value /
;; innerHTML / hidden / addEventListener), feeds it the REAL JSON block
;; extracted from the generated docs/index.html, loads search.cljs, and
;; asserts the rendered results + a simulated search interaction.
;; nbb script (the org's Node-harnesses-in-nbb rule -- no .mjs).
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

;; initial render: both governor-passed postings visible
(assert! (.includes (results-html) "Warehouse Associate") "initial render shows posting-1")
(assert! (.includes (results-html) "Chuo Kitchen") "initial render shows posting-6")
(assert! (.includes (results-html) "governor-passed") "cards carry the governor-passed badge")
(assert! (true? (aget (get elements "empty") "hidden")) "empty notice hidden while there are hits")

;; simulate typing a query that matches only posting-6
(aset (get elements "q") "value" "cook")
((get @listeners ["q" "input"]))
(assert! (.includes (results-html) "Chuo Kitchen") "query 'cook' keeps posting-6")
(assert! (not (.includes (results-html) "Warehouse Associate")) "query 'cook' filters posting-1 out")

;; simulate a query with no hits -> empty notice shown
(aset (get elements "q") "value" "zzz-no-such-job")
((get @listeners ["q" "input"]))
(assert! (= "" (results-html)) "no-hit query renders no cards")
(assert! (false? (boolean (aget (get elements "empty") "hidden"))) "no-hit query reveals the empty notice")

;; held postings must NOT be in the search data at all
(assert! (not (.includes json-block "posting-7")) "stale-vacancy posting-7 absent from search data")
(assert! (not (.includes json-block "posting-4")) "discriminatory posting-4 absent from search data")

(println "verify_search: all assertions passed")
