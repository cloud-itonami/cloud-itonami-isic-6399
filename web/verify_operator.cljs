;; Headless verification of OPERATOR MODE: regenerates the page from
;; postings.example.edn through the real actor, asserts the governed
;; outcome (clean postings in the index, stale/mismatch postings held
;; with their real verdicts), then regenerates demo mode so the working
;; tree is left in the published state. nbb script (Node-harnesses-in-
;; nbb rule).
;;
;; Run (from this web/ directory):
;;   ../../../../node_modules/.bin/nbb verify_operator.cljs
(require '["fs" :as fs]
         '["child_process" :as cp])

;; Both default to the monorepo layout and are overridable for a
;; checkout that is not in it (a git worktree, CI): MJS_NBB /
;; MJS_CLASSPATH, the same escape hatch generate.cljs gives for
;; JP_GO_DDS_CSS and KOTOBA_HTML_ROOT.
(def nbb
  (or (some-> js/process.env.MJS_NBB not-empty) "../../../../node_modules/.bin/nbb"))
(def classpath
  (or (some-> js/process.env.MJS_CLASSPATH not-empty)
      ;; kotoba-lang/css is required by generate.cljs (css.core) since
      ;; the EDN/hiccup CSS refactor -- omitting it fails the harness
      ;; before a single assertion runs.
      (str "../src:../../../kotoba-lang/html/src:../../../kotoba-lang/css/src"
           ":../../../kotoba-lang/jp-go-digital-design-system/src"
           ":../../../kotoba-lang/langchain/src:../../../kotoba-lang/langchain-store/src"
           ":../../../kotoba-lang/langgraph/src")))

(defn- generate! [& args]
  (cp/execFileSync nbb (clj->js (concat ["--classpath" classpath "generate.cljs"] args))
                   #js {:stdio "inherit"}))

(defn- assert! [ok? msg]
  (if ok?
    (println "ok  " msg)
    (do (println "FAIL" msg) (js/process.exit 1))))

;; ---- operator mode ---------------------------------------------------------

(generate! "postings.example.edn")
(let [html (fs/readFileSync "../docs/index.html" "utf8")
      json (second (re-find #"<script type=\"application/json\" id=\"postings-data\">(\[.*?\])</script>" html))]
  (assert! (.includes json "own-1") "clean own-1 in the operator index")
  (assert! (.includes json "own-2") "consent-verified own-2 in the operator index")
  (assert! (not (.includes json "own-3")) "stale own-3 NOT in the operator index")
  (assert! (not (.includes json "own-4")) "pay-mismatch own-4 NOT in the operator index")
  (assert! (.includes html "stale-vacancy") "own-3's real stale-vacancy verdict on the page")
  (assert! (.includes html "displayed-compensation-mismatch") "own-4's real mismatch verdict on the page")
  (assert! (.includes html "うみかぜ物流") "held posting identified by employer")
  (assert! (.includes html "op=:posting/publish") "operator ledger has publish facts"))

;; ---- restore demo mode -----------------------------------------------------

(generate!)
(let [html (fs/readFileSync "../docs/index.html" "utf8")]
  (assert! (.includes html "Forklift Operator") "demo mode restored (posting-8 present)")
  (assert! (not (.includes html "own-1")) "operator data absent after restore"))

(println "verify_operator: all assertions passed")
