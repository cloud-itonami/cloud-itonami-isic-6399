;; Generates docs/index.html (the GitHub Pages demo UI) from EDN/Hiccup via
;; kotoba-lang/html + kotoba-lang/css -- markup/styling as data, not
;; hand-quoted HTML strings -- following kototama/web/generate.cljs's own
;; precedent and the org's runtime priority (this is an nbb script; the
;; OUTPUT is a plain static page with no build step for a visiting browser;
;; in-browser interactivity is `search.cljs` run by scittle, i.e.
;; ClojureScript in the browser, not a hand-written .js file).
;;
;; NOTHING on this page is hand-typed: this script runs the FULL
;; OperationActor StateGraph (JobSearch-LLM sealed advisor -> Job Search
;; Portal Governor -> phase gate -> approval interrupt -> commit|hold) at
;; build time -- the same lifecycle `jobsearchops.sim` walks -- against the
;; actor's own seeded Store (plus one page-only clean posting ingested
;; through the real `:posting/ingest` op). The live index is the post-run
;; Store's published-and-not-delisted postings; the transparency table is
;; the real HARD-hold verdicts of the failed publish attempts; the audit
;; ledger section is the append-only record those runs actually wrote.
;; nbb-loadable since kotoba-lang/langchain 9f4453d3 + 0f966d06.
;;
;; Run (from this web/ directory, inside the monorepo checkout):
;;   ../../../../node_modules/.bin/nbb \
;;     --classpath "../src:../../../kotoba-lang/html/src:../../../kotoba-lang/css/src:../../../kotoba-lang/langchain/src:../../../kotoba-lang/langgraph/src" \
;;     generate.cljs
(require '[clojure.edn :as edn]
         '[clojure.string :as cstr]
         '[html.core :as html]
         '[css.core :as css]
         '[langgraph.graph :as g]
         '[jobsearchops.store :as store]
         '[jobsearchops.operation :as op]
         '["fs" :as fs])

;; -- operator mode ------------------------------------------------------------
;; `nbb ... generate.cljs <your-postings.edn>` builds YOUR governed board:
;; every posting in the file is assessed and then submitted for publication
;; through the real actor; whatever the governor holds appears in the
;; transparency table with its real verdict, and only what it passes (and a
;; human approved) reaches the index. See `postings.example.edn` for the
;; record shape and docs/operator-quickstart.md for the fork-to-published
;; walkthrough. With no argument, the actor's own demo set + demo lifecycle
;; (including the delisting story and a double-publish attempt) is used.
(def operator-file (first *command-line-args*))
(def operator-postings
  (when operator-file
    (edn/read-string (fs/readFileSync operator-file "utf8"))))

(def db
  (if operator-postings
    (store/with-postings
      (store/->MemStore (atom {:postings {} :assessments {} :ledger []
                               :publication-sequences {} :publication-records []
                               :delisting-sequences {} :delisting-records []}))
      (into {} (map (juxt :id identity) operator-postings)))
    (store/seed-db)))

(def actor (op/build db))

(def operator {:actor-id "op-1" :actor-role :portal-operator :phase 3})

(defn- exec!
  "One supervised actor run, mirroring jobsearchops.sim: if the graph
  interrupts for human approval, the portal operator approves and the
  run resumes. Returns the final run result."
  [tid request]
  (let [r (g/run* actor {:request request :context operator} {:thread-id tid})]
    (if (= :interrupted (:status r))
      (g/run* actor {:approval {:status :approved :by "op-1"}}
              {:thread-id tid :resume? true})
      r)))

;; -- the build-time lifecycle -------------------------------------------------

(defn- violations-of [run] (get-in run [:state :verdict :violations]))

(def held
  (if operator-postings
    ;; OPERATOR MODE: assess + publish every posting; the governor decides.
    ;; An assess-time hold (e.g. an uncatalogued jurisdiction) is reported
    ;; with its own verdict and the publish attempt is skipped.
    (vec
     (keep (fn [{:keys [id] :as _p}]
             (let [a (exec! (str id "-assess") {:op :jurisdiction/assess :subject id})]
               (if (= :hold (get-in a [:state :disposition]))
                 {:posting (store/posting db id) :violations (violations-of a)
                  :note "法域アセスメント時点で拒否"}
                 (let [r (exec! (str id "-publish") {:op :posting/publish :subject id})]
                   (when (= :hold (get-in r [:state :disposition]))
                     {:posting (store/posting db id) :violations (violations-of r)})))))
           (sort-by :id operator-postings)))

    ;; DEMO MODE: the same lifecycle jobsearchops.sim walks.
    (do
      ;; a page-only clean posting, ingested through the REAL :posting/ingest
      ;; op (auto-commits when governor-clean at phase 3) so the live index
      ;; still has two postings after posting-1's delisting below.
      (exec! "t0" {:op :posting/ingest :subject "posting-8"
                   :patch {:id "posting-8" :title "Forklift Operator" :employer "Yama Warehouse"
                           :source "employer-direct"
                           :source-hourly-wage 1700 :source-monthly-hours 160 :displayed-compensation 272000.0
                           :ad-content-discriminatory? false :source-vacancy-closed? false
                           :requires-source-consent? false :source-consent-verified? false
                           :published? false :delisted? false
                           :jurisdiction "JPN" :status :ingested}})

      ;; clean lifecycles: publish posting-6/posting-8; posting-1 is published
      ;; and then delisted (the 的確表示 currency duty's other half: a filled
      ;; vacancy leaves the index).
      (doseq [[tid pid] [["a1" "posting-1"] ["a6" "posting-6"] ["a8" "posting-8"]]]
        (exec! (str tid "-assess") {:op :jurisdiction/assess :subject pid})
        (exec! (str tid "-publish") {:op :posting/publish :subject pid}))
      (exec! "a1-delist" {:op :posting/delist :subject "posting-1"})

      ;; the HARD-hold attempts, one per governor check (posting-2's
      ;; spec-basis hold happens at assess; the rest assess cleanly, then
      ;; fail publish), plus the double-actuation guard on posting-1.
      (let [no-spec (exec! "h2-assess" {:op :jurisdiction/assess :subject "posting-2" :no-spec? true})
            holds (vec (for [[tid pid] [["h3" "posting-3"] ["h4" "posting-4"]
                                        ["h5" "posting-5"] ["h7" "posting-7"]]]
                         (do (exec! (str tid "-assess") {:op :jurisdiction/assess :subject pid})
                             [pid (exec! (str tid "-publish") {:op :posting/publish :subject pid})])))
            double-publish (exec! "g1" {:op :posting/publish :subject "posting-1"})]
        (into [{:posting (store/posting db "posting-2") :violations (violations-of no-spec)}]
              (concat (for [[pid run] holds]
                        {:posting (store/posting db pid) :violations (violations-of run)})
                      [{:posting (store/posting db "posting-1") :violations (violations-of double-publish)
                        :note "二重掲載の試行"}]))))))

;; -- post-run state -----------------------------------------------------------

(def all-postings (store/all-postings db))
(def live-index (vec (filter #(and (:published? %) (not (:delisted? %))) all-postings)))
(def delisted (vec (filter :delisted? all-postings)))
(def ledger (store/ledger db))

(defn ledger-line [{:keys [t op subject disposition basis]}]
  (cstr/join " · " [(name t) (str "op=" op) (str "subject=" subject)
                    (str "disposition=" (name disposition))
                    (str "basis=" (pr-str basis))]))

(def yen (js/Intl.NumberFormat. "ja-JP"))
(defn fmt-yen [n] (str "¥" (.format yen n) "/月"))

(def stylesheet
  (css/style-node
   {:rules
    {":root" {:--fg "#1b1f24" :--bg "#ffffff" :--muted "#57606a"
              :--card "#f6f8fa" :--line "#d0d7de" :--accent "#0b5cad"
              :--ok-bg "#dafbe1" :--ok-fg "#116329"
              :--hold-bg "#ffebe9" :--hold-fg "#a40e26"}
     "body" {:font-family "system-ui,-apple-system,'Hiragino Sans','Noto Sans JP',sans-serif"
             :margin "0 auto" :max-width 880 :padding "28px 20px 48px"
             :color "var(--fg)" :background "var(--bg)" :line-height 1.55}
     "header p.sub" {:color "var(--muted)" :margin-top 4}
     "h1"   {:font-size 24 :margin "0"}
     "h2"   {:font-size 17 :margin-top 40 :border-top "1px solid var(--line)"
             :padding-top 24}
     ".search" {:display :flex :gap 8 :margin-top 20}
     "input#q" {:flex 1 :font-size 16 :padding "10px 14px"
                :border "1.5px solid var(--line)" :border-radius 8
                :background "var(--bg)" :color "var(--fg)"}
     "select#jur" {:font-size 15 :padding "10px 12px"
                   :border "1.5px solid var(--line)" :border-radius 8
                   :background "var(--bg)" :color "var(--fg)"}
     ".card" {:background "var(--card)" :border "1px solid var(--line)"
              :border-radius 10 :padding "14px 16px" :margin-top 12}
     ".card h3" {:margin "0 0 2px" :font-size 16}
     ".card .meta" {:color "var(--muted)" :font-size 13.5}
     ".card .pay" {:margin-top 6 :font-size 15 :font-weight 600}
     ".badge" {:display :inline-block :font-size 12 :font-weight 600
               :border-radius 20 :padding "2px 10px" :margin-left 8
               :vertical-align "1px"}
     ".badge.ok" {:background "var(--ok-bg)" :color "var(--ok-fg)"}
     ".badge.hold" {:background "var(--hold-bg)" :color "var(--hold-fg)"}
     ".chip" {:display :inline-block :font-size 12 :color "var(--muted)"
              :border "1px solid var(--line)" :border-radius 20
              :padding "1px 9px" :margin-right 6}
     "#empty" {:color "var(--muted)" :margin-top 16}
     "table" {:border-collapse :collapse :width "100%" :margin-top 12
              :font-size 13.5}
     "th" {:text-align :left :color "var(--muted)" :font-weight 600
           :border-bottom "1.5px solid var(--line)" :padding "6px 8px"}
     "td" {:border-bottom "1px solid var(--line)" :padding "7px 8px"
           :vertical-align :top}
     "pre" {:background "var(--card)" :border "1px solid var(--line)"
            :border-radius 8 :padding "10px 12px" :overflow-x :auto
            :font-size 12.5 :line-height 1.7}
     "footer" {:margin-top 48 :padding-top 16 :border-top "1px solid var(--line)"
               :color "var(--muted)" :font-size 13.5}
     "a" {:color "var(--accent)"}
     "code" {:background "var(--card)" :padding "1px 5px" :border-radius 4
             :font-size "0.9em"}}
    :media
    {"(prefers-color-scheme: dark)"
     {":root" {:--fg "#e6edf3" :--bg "#0d1117" :--muted "#8d96a0"
               :--card "#161b22" :--line "#30363d" :--accent "#58a6ff"
               :--ok-bg "#12261e" :--ok-fg "#3fb950"
               :--hold-bg "#2d1215" :--hold-fg "#f85149"}}}}))

(defn posting->json-entry [p]
  {:id (:id p) :title (:title p) :employer (:employer p)
   :jurisdiction (:jurisdiction p) :source (:source p)
   :publication (:publication-number p)
   :pay (fmt-yen (:displayed-compensation p))
   :wage (str "時給 ¥" (.format yen (:source-hourly-wage p))
              " × " (:source-monthly-hours p) "h")})

(def page
  [:html {:lang "ja"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Meta Job Search — governed job-posting aggregation (cloud-itonami-isic-6399)"]
    [:meta {:name "description"
            :content "求人メタサーチのオープンソース実装デモ。独立ガバナーが的確表示義務・賃金表示・転載許諾・差別広告を検査した求人だけが検索インデックスに載る。"}]
    stylesheet]
   [:body
    [:header
     [:h1 "Meta Job Search " [:span.badge.ok "governed"]]
     [:p.sub "求人メタサーチ — 独立ガバナーの検査を通過した求人だけが載る検索インデックス。 "
      [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399"} "cloud-itonami-isic-6399"]
      " のライブデモ(合成データ)。このページの内容はすべて、生成時に実 actor"
      "(StateGraph + Governor)を実行した結果です。"]]

    [:div.search
     [:input {:id "q" :type "search" :placeholder "職種・雇用主・キーワードで検索…"
              :autocomplete "off"}]
     [:select {:id "jur"}
      [:option {:value ""} "全法域"]
      [:option {:value "JPN"} "日本 (JPN)"]]]

    [:div {:id "results"}]
    [:p {:id "empty" :hidden true} "該当する求人はありません。"]
    (if (seq delisted)
      (into [:p [:span.meta "取下げ済み(インデックス外): "]]
            (for [p delisted]
              [:span.meta (:title p) " (" (:employer p) ") — 掲載後に充足し取下げ("
               [:code (:delisting-number p)] ")。的確表示義務はこの「消える」側も含む。"]))
      "")

    [:h2 "Governor transparency — 掲載を拒否した求人票"]
    [:p "Indeed 型アグリゲーターとの違いはここです: 掲載判断は LLM でも運営者の裁量でもなく、"
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/blob/main/src/jobsearchops/governor.cljc"}
      "独立ガバナー"]
     " の HARD check が下します(人間の承認でも覆せません)。この表はハードコードではなく、"
     "ページ生成時に実際の OperationActor へ掲載を試行させ、ガバナーが拒否した実判定です。"]
    [:table
     [:thead [:tr [:th "求人票"] [:th "HARD check"] [:th "理由"]]]
     (into [:tbody]
           (for [{:keys [posting violations note]} held]
             [:tr
              [:td [:strong (:title posting)] [:br]
               [:span.meta (:employer posting) " · " (:jurisdiction posting) " · " (:id posting)
                (when note (str " · " note))]]
              [:td (into [:span] (for [v violations] [:span [:span.badge.hold (name (:rule v))] " "]))]
              [:td (cstr/join " / " (map :detail violations))]]))]

    [:h2 "監査台帳 — 上の全実行が実際に書いた追記専用レコード"]
    [:p "掲載・取下げ・拒否のすべてが不変の台帳に残ります(的確表示義務コンプライアンスの証跡)。"
     "以下はページ生成時の実 actor 実行が書いた事実そのものです。"]
    (into [:pre] [(cstr/join "\n" (map ledger-line ledger))])

    [:h2 "この検索インデックスが保証すること"]
    [:ul
     [:li "求人元が募集終了した求人は載らない(" [:strong "的確表示義務"] " — 職業安定法5条の4、令和4年改正)"]
     [:li "表示賃金は求人元記録からの独立再計算と常に一致する"]
     [:li "転載許諾が必要なソースの求人は、許諾確認なしに載らない"]
     [:li "保護属性に基づく差別的広告は載らない(均等法5条 / Title VII §704(b) / Equality Act 2010 / AGG §11)"]
     [:li "すべての掲載・取下げ・拒否が追記専用の監査台帳に残る"]]

    [:footer
     [:p "OSS (AGPL-3.0-or-later)。fork して自分の求人ポータルとして運営できます — "
      [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/blob/main/docs/business-model.md"} "business model"]
      " · "
      [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/blob/main/docs/operator-guide.md"} "operator guide"]
      " · "
      [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/blob/main/docs/adr/0001-architecture.md"} "architecture ADR"]
      " · 姉妹デモ: "
      [:a {:href "https://cloud-itonami.github.io/cloud-itonami-isic-6310/"} "Talent Board (isic-6310)"]
      "。このページは " [:code "web/generate.cljs"] " (nbb) が実 actor を実行して生成し、検索は "
      [:code "search.cljs"] " (scittle = ブラウザ内 ClojureScript) が実行しています。"]]

    ;; live-index postings as data for the in-browser search (search.cljs).
    ;; [:hiccup/raw ...] because script elements are raw text -- the browser
    ;; never decodes entities inside them, so escaping would corrupt the
    ;; JSON. The JSON contains no "<", so raw embedding is safe.
    [:script {:type "application/json" :id "postings-data"}
     [:hiccup/raw (js/JSON.stringify (clj->js (mapv posting->json-entry live-index)))]]
    [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.6.22/dist/scittle.js"}]
    [:script {:type "application/x-scittle" :src "search.cljs"}]]])

(fs/mkdirSync "../docs" #js {:recursive true})
(fs/writeFileSync "../docs/index.html" (str "<!doctype html>\n" (html/render page) "\n"))
(fs/copyFileSync "search.cljs" "../docs/search.cljs")
(println (str "wrote docs/index.html (live-index " (count live-index)
              ", delisted " (count delisted)
              ", held " (count held)
              ", ledger " (count ledger) " facts)"))
