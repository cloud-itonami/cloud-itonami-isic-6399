;; Generates docs/index.html (the GitHub Pages demo UI) from EDN/Hiccup via
;; kotoba-lang/html + kotoba-lang/jp-go-digital-design-system -- markup/styling as data, not
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
;;
;; UI は デジタル庁デザインシステム(DADS)を kotoba-lang/jp-go-digital-design-system
;; 経由で使う(superproject ADR-2607261600)。この actor は職業安定法5条の4
;; (的確表示義務)をソフトウェアとして実装しており、日本の公的サービスの視覚言語に
;; 揃える方が利用者の信頼判断に効く。DADS は light mode 固定(上流に dark palette
;; が無い)なので、移行前の prefers-color-scheme による dark 対応は意図的に
;; 落としている。
;;
;; Run (from this web/ directory, inside the monorepo checkout):
;;   ../../../../node_modules/.bin/nbb \
;;     --classpath "../src:../../../kotoba-lang/html/src:../../../kotoba-lang/jp-go-digital-design-system/src:../../../kotoba-lang/langchain/src:../../../kotoba-lang/langchain-store/src:../../../kotoba-lang/langgraph/src" \
;;     generate.cljs [postings.edn]
;;
;; dds.css の読み込みパスは環境変数 JP_GO_DDS_CSS で上書きできる
;; (CI / worktree など monorepo 以外のレイアウト用)。
(require '[clojure.edn :as edn]
         '[clojure.string :as cstr]
         '[css.core :as css]
         '[jp-go-dds.core :as dds]
         '[jp-go-dds.page :as page]
         '[langgraph.graph :as g]
         '[jobsearchops.store :as store]
         '[jobsearchops.registry :as registry]
         '[jobsearchops.ingest :as ingest]
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

;; OPERATOR MODE, per posting: assess through the real actor always;
;; publish only when the posting's own record claims verified
;; compensation ground truth. A posting ingested by web/collect.cljs
;; (jobsearchops.ingest) carries :compensation-verified? false --
;; real-world postings almost never state a committed monthly-hours
;; figure alongside an hourly rate, and inventing the missing half
;; here to force a publish attempt would be exactly the fabricated-
;; ground-truth problem this actor's governor exists to catch, just
;; moved upstream of where it could catch it (see jobsearchops.ingest's
;; ns docstring). Records without the key at all (hand-authored
;; postings.example.edn-style files) default to verified=true, the
;; original behavior, so existing operator forks are unaffected.
(defn- compensation-verified? [p]
  (not (false? (:compensation-verified? p))))

;; Each posting resolves to exactly one of :held (assess or publish
;; HARD-hold, real governor verdict), :pending (assessed clean through
;; the real actor, but not yet offered for publication -- see
;; compensation-verified? above) or nil (published; ends up in
;; live-index via the post-run store state below).
(def operator-results
  (when operator-postings
    (vec
     (keep (fn [{:keys [id] :as p}]
             (let [a (exec! (str id "-assess") {:op :jurisdiction/assess :subject id})]
               (cond
                 (= :hold (get-in a [:state :disposition]))
                 {:kind :held :posting (store/posting db id) :violations (violations-of a)
                  :note "法域アセスメント時点で拒否"}

                 (not (compensation-verified? p))
                 {:kind :pending :posting (store/posting db id)}

                 :else
                 (let [r (exec! (str id "-publish") {:op :posting/publish :subject id})]
                   (when (= :hold (get-in r [:state :disposition]))
                     {:kind :held :posting (store/posting db id) :violations (violations-of r)})))))
           (sort-by :id operator-postings)))))

(def held
  (if operator-postings
    (vec (filter #(= :held (:kind %)) operator-results))

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

      ;; the correction lifecycle (訂正 -- ADR-0002): posting-6's source
      ;; wage changed; ingest normalizes the new ground truth, then the
      ;; governed correct act updates the public surface and stamps the
      ;; correction record the card displays.
      (exec! "c6-ingest" {:op :posting/ingest :subject "posting-6"
                          :patch {:id "posting-6" :source-hourly-wage 1550
                                  :displayed-compensation 248000.0}})
      (exec! "c6-correct" {:op :posting/correct :subject "posting-6"})

      ;; the referral handoff (ADR-2607131000): an application on the board
      ;; becomes a human-carried referral draft toward the placement desk.
      (exec! "r6" {:op :application/refer :subject "posting-6"
                   :applicant-ref "applicant-ref-001" :applicant-consent? true})

      ;; the HARD-hold attempts, one per governor check (posting-2's
      ;; spec-basis hold happens at assess; the rest assess cleanly, then
      ;; fail publish), plus the double-actuation guard on posting-1.
      (let [no-spec (exec! "h2-assess" {:op :jurisdiction/assess :subject "posting-2" :no-spec? true})
            holds (vec (for [[tid pid] [["h3" "posting-3"] ["h4" "posting-4"]
                                        ["h5" "posting-5"] ["h7" "posting-7"]]]
                         (do (exec! (str tid "-assess") {:op :jurisdiction/assess :subject pid})
                             [pid (exec! (str tid "-publish") {:op :posting/publish :subject pid})])))
            double-publish (exec! "g1" {:op :posting/publish :subject "posting-1"})
            no-consent (exec! "r6b" {:op :application/refer :subject "posting-6"
                                     :applicant-ref "applicant-ref-002"})]
        (into [{:posting (store/posting db "posting-2") :violations (violations-of no-spec)}]
              (concat (for [[pid run] holds]
                        {:posting (store/posting db pid) :violations (violations-of run)})
                      [{:posting (store/posting db "posting-1") :violations (violations-of double-publish)
                        :note "二重掲載の試行"}
                       {:posting (store/posting db "posting-6") :violations (violations-of no-consent)
                        :note "本人同意なし referral の試行"}]))))))

;; Postings assessed clean through the real actor that did not attempt
;; to publish (compensation ground truth unverified -- see
;; compensation-verified? above). Demo mode has none of these; the
;; demo's own postings all carry full ground truth.
;;
;; Split by provenance (`jobsearchops.ingest/connector-managed?`, the
;; same predicate `close-vanished` carries postings forward by): a
;; collected posting is pending because its SOURCE disclosed an hourly
;; range without committing monthly hours, while an operator's own
;; hand-authored posting is pending because the OPERATOR has not fixed
;; its rate yet. Describing one with the other's reason would be a
;; false provenance claim on this page -- the same class of inaccuracy
;; the actor refuses to publish.
(def pending-all
  (if operator-postings
    (vec (filter #(= :pending (:kind %)) operator-results))
    []))
(def collected-pending (vec (filter #(ingest/connector-managed? (:posting %)) pending-all)))
(def own-pending (vec (remove #(ingest/connector-managed? (:posting %)) pending-all)))

;; -- post-run state -----------------------------------------------------------

(def all-postings (store/all-postings db))
(def live-index (vec (filter #(and (:published? %) (not (:delisted? %))) all-postings)))
(def delisted (vec (filter :delisted? all-postings)))
(def referrals (store/referral-history db))
(def ledger (store/ledger db))

(defn ledger-line [{:keys [t op subject disposition basis]}]
  (cstr/join " · " [(name t) (str "op=" op) (str "subject=" subject)
                    (str "disposition=" (name disposition))
                    (str "basis=" (pr-str basis))]))

(def yen (js/Intl.NumberFormat. "ja-JP"))

(defn- amount
  "`n` labelled in the currency `p` itself disclosed
  (`jobsearchops.registry/compensation-unit`): the symbol when this
  catalog has one, else the bare ISO 4217 code, else an explicit
  未記載 marker -- never a symbol we merely guessed. No rate is ever
  applied; the figure is the source's own. Rendering a JPY figure with
  a `$` (what this page did while every RANGE posting happened to come
  from a US board) is itself a false compensation claim on a real job
  ad -- the 的確表示義務 class of error this actor exists to catch."
  [p n]
  (let [{:keys [currency symbol]} (registry/compensation-unit p)
        s (.format yen n)]
    (cond symbol   (str symbol s)
          currency (str s " " currency)
          :else    (str s " (通貨未記載)"))))

(def dds-css-path
  (or (some-> js/process.env.JP_GO_DDS_CSS not-empty)
      "../../../kotoba-lang/jp-go-digital-design-system/resources/jp_go_dds/dds.css"))
(def dds-css (fs/readFileSync dds-css-path "utf8"))

;; ページ固有の微調整のみ。色は DADS token 参照で raw hex は書かない
;; (kotoba-uiux 規約)。レイアウトの土台は dds-ext-*(jp-go-dds.core/ext-css)。
;; select は上流 DADS の vendored subset に無い(dds.css に .dads-select が
;; 無い)ので、.dads-input-text__input と寸法・境界・focus を揃える。
(def app-rules
  [[".mjs-header" {:padding-block "2.5rem 0"}]
   [".mjs-header .dads-heading" {:margin "0 0 .5rem"}]
   [".mjs-lead" {:color "var(--color-neutral-solid-gray-700)" :line-height 1.7
                 :margin ".75rem 0 0"}]
   [".mjs-pitch" {:margin-block "2rem"}]
   [".mjs-pitch .dads-heading" {:margin "0 0 .75rem"}]
   [".mjs-pitch p" {:margin "0 0 .75rem" :line-height 1.8}]
   [".mjs-pitch .dads-table" {:margin-block "1rem"}]
   [".mjs-ctarow" {:display "flex" :gap ".75rem" :flex-wrap "wrap" :margin-top "1.25rem"}]
   [".mjs-fine" {:color "var(--color-neutral-solid-gray-600)" :font-size ".8125rem"
                 :line-height 1.8 :margin-top "1rem"}]
   [".mjs-search" {:display "flex" :gap ".75rem" :flex-wrap "wrap"
                   :align-items "flex-end" :margin-bottom "1.5rem"}]
   [".mjs-search .dads-form-control-label" {:flex 1 :min-width "12rem"}]
   [".dads-input-text__input" {:width "100%"}]
   ;; 検索結果カードは search.cljs が実行時に注入する(dds-ext-card + mjs-card)
   ["#results" {:display "grid"
                :grid-template-columns "repeat(auto-fill,minmax(18rem,1fr))"
                :gap "1rem" :margin-top "1rem"}]
   ["#results>*" {:min-width 0}]
   [".mjs-card h3" {:margin "0 0 .35rem" :font-size "1rem" :display "flex"
                    :gap ".5rem" :align-items "baseline" :flex-wrap "wrap"}]
   [".mjs-card .meta" {:color "var(--color-neutral-solid-gray-600)"
                       :font-size ".8125rem" :line-height 1.7}]
   [".mjs-card .pay" {:margin-top ".35rem" :font-size ".875rem" :line-height 1.7}]
   [".mjs-card .chip" {:display "inline-block" :font-size ".75rem"
                       :padding ".05rem .5rem" :border-radius "1rem"
                       :border "1px solid var(--color-neutral-solid-gray-300)"
                       :color "var(--color-neutral-solid-gray-700)"}]
   [".mjs-empty" {:color "var(--color-neutral-solid-gray-600)" :margin-top "1rem"}]
   [".mjs-delisted" {:color "var(--color-neutral-solid-gray-600)"
                     :font-size ".875rem" :line-height 1.8 :margin-top "1rem"}]
   [".mjs-delisted>span" {:display "block"}]
   [".mjs-hold-rules>span" {:display "block" :margin-block ".15rem"}]
   ;; チップのラベルを途中で折り返さない。.dads-table は overflow-x:auto。
   [".dads-table .dads-chip-label" {:white-space "nowrap"}]
   [".mjs-referrals" {:line-height 1.9 :padding-left "1.25rem" :margin-top "1rem"}]
   ;; 台帳は等幅。横に長いので自身の中でだけ横スクロールさせる
   ["pre" {:font-family "var(--font-family-mono)" :font-size ".8125rem"
           :line-height 1.7 :background "var(--color-neutral-solid-gray-50)"
           :border "1px solid var(--color-neutral-solid-gray-200)"
           :border-radius 8 :padding "1rem" :overflow-x "auto" :margin-top "1rem"}]
   [".mjs-guarantees" {:line-height 1.9 :padding-left "1.25rem" :margin 0}]
   [".mjs-footer" {:border-top "1px solid var(--color-neutral-solid-gray-200)"
                   :margin-top "3rem" :padding-block "1.5rem 3rem"
                   :color "var(--color-neutral-solid-gray-600)"
                   :font-size ".875rem" :line-height 1.8}]
   [".mjs-footer p" {:margin "0 0 .75rem"}]
   [".mjs-footer .cta" {:font-size ".9375rem" :font-weight 700
                        :color "var(--color-neutral-solid-gray-900)"}]
   ["code" {:font-family "var(--font-family-mono)"
            :background "var(--color-neutral-solid-gray-50)"
            :border "1px solid var(--color-neutral-solid-gray-200)"
            :border-radius 4 :padding "1px 5px" :font-size ".9em"}]])

(def app-css (css/css {:rules app-rules}))

;; 判定バッジは DADS chip-label(filled-1)。
(defn- chip [label color] (dds/chip-label label {:color color :style "filled-1"}))

;; Two ground-truth shapes reach here (jobsearchops.registry's own ns
;; docstring): EXACT (hand-authored/demo, hourly x monthly-hours, a
;; monthly total) and RANGE (a source's own disclosed hourly range --
;; jobsearchops.ingest for collected data, and hand-authored spot/gig
;; postings that genuinely have no committed monthly hours to multiply
;; by). Formatting a RANGE posting's nil :displayed-compensation as a
;; monthly total would silently render "0/月" -- wrong, not just ugly --
;; so both fields dispatch on shape here too, and both carry the
;; posting's OWN currency (`amount`) rather than the USD that every
;; collected posting happened to use.
(defn- range-shaped? [p] (some? (:source-compensation-min p)))

(defn posting->json-entry [p]
  {:id (:id p) :title (:title p) :employer (:employer p)
   :jurisdiction (:jurisdiction p) :source (:source p)
   :publication (:publication-number p)
   :correction (:correction-number p)
   :pay (if (range-shaped? p)
          (str (amount p (:displayed-compensation-min p)) "–"
               (amount p (:displayed-compensation-max p)) "/時")
          (str (amount p (:displayed-compensation p)) "/月"))
   :wage (if (range-shaped? p)
           (str "求人元開示レンジ " (amount p (:source-compensation-min p)) "–"
                (amount p (:source-compensation-max p)) "/時")
           (str "時給 " (amount p (:source-hourly-wage p))
                " × " (:source-monthly-hours p) "h"))})

(def body
  (dds/container
   [:header {:class "mjs-header"}
    (dds/heading 1 [:span "Meta Job Search " (chip "governed" "green")])
    [:p {:class "mjs-lead"}
     "求人メタサーチ — 独立ガバナーの検査を通過した求人だけが載る検索インデックス。 "
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399"} "cloud-itonami-isic-6399"]
     " のライブデモ(合成データ)。このページの内容はすべて、生成時に実 actor"
     "(StateGraph + Governor)を実行した結果です。"]]

   [:div {:class "mjs-pitch"}
    (dds/card
     (dds/heading 2 "見積もりのために営業電話、していませんか?" {:size "24"})
     [:p "Madgexは価格非公開・sales-gated(問い合わせ必須)。JobBoard.io・JBoard は公開価格が"
      "あるものの月額$249〜849。このボードは"
      [:strong " 即決フラット ¥80,000/月"] "、営業プロセス不要で今すぐ始められます。"]
     (dds/table
      {:headers ["求人ボードSaaS" "価格の出し方" "実勢価格"]
       :rows [["Madgex" "非公開(要問い合わせ)" "$500+/月〜(要見積)"]
              ["JobBoard.io" "公開・段階制" "$449〜649/月"]
              ["JBoard" "公開・段階制" "$249〜849/月"]
              ["engage (日本)" "無料+従量課金" "掲載無料 + ¥7,000/日 配信"]
              [[:strong "このボード"] [:strong "即決・公開・フラット"] [:strong "¥80,000/月"]]]})
     [:p "さらに、令和4年職業安定法改正の"
      [:strong "的確表示義務(5条の4)"] "を独立ガバナーが構造的に検査 — "
      "賃金表示・転載許諾・差別的広告のいずれかで不合格の求人は、人間の承認があっても"
      "検索インデックスに載りません。"]
     [:div {:class "mjs-ctarow"}
      (dds/button "🡒 Managed Job Board を購読(¥80,000/月)"
                  {:type :solid-fill :size "lg"
                   :href "https://buy.stripe.com/bJe9AS74n1dmcOQcEvbMQ0b"})
      (dds/button "自前運用(セルフホスト)に興味がある"
                  {:type :outline :size "lg"
                   :href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/issues/new"})]
     [:p {:class "mjs-fine"} "価格根拠: "
      [:a {:href "https://github.com/com-junkawasaki/root/blob/main/90-docs/pricing-intelligence/pricing-intelligence-ledger.edn"}
       "6社の実競合調査(2026-07-16)"]
      " — 下の技術デモは合成データによる実 actor 実行結果、この価格比較表とは独立して生成されています。"])]

   (dds/section
    {:title "検索インデックス"}
    [:div {:class "mjs-search"}
     (dds/form-field
      {:label "検索" :for "q"}
      (dds/input-text {:id "q" :type "search" :autocomplete "off"
                       :placeholder "職種・雇用主・キーワードで検索…"}))
     (dds/form-field
      {:label "法域" :for "jur"}
      (dds/select {:id "jur"}
                  (into [["" "全法域"]]
                        (for [j (sort (distinct (map :jurisdiction live-index)))] [j j]))))
     (dds/form-field
      {:label "ソース" :for "src"}
      (dds/select {:id "src"}
                  [["" "全ソース"]
                   ["employer-direct" "雇用主直接"]
                   ["partner-feed" "提携フィード"]
                   ["board-crawl" "許諾クロール"]]))]
    [:div {:id "results"}]
    [:p {:id "empty" :class "mjs-empty" :hidden true} "該当する求人はありません。"]
    (when (seq delisted)
      (into [:p {:class "mjs-delisted"} [:span "取下げ済み(インデックス外): "]]
            (for [p delisted]
              [:span (:title p) " (" (:employer p) ") — 掲載後に充足し取下げ("
               [:code (:delisting-number p)] ")。的確表示義務はこの「消える」側も含む。"]))))

   (dds/section
    {:title "Governor transparency — 掲載を拒否した求人票"}
    [:p {:class "mjs-lead"}
     "Indeed 型アグリゲーターとの違いはここです: 掲載判断は LLM でも運営者の裁量でもなく、"
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/blob/main/src/jobsearchops/governor.cljc"}
      "独立ガバナー"]
     " の HARD check が下します(人間の承認でも覆せません)。この表はハードコードではなく、"
     "ページ生成時に実際の OperationActor へ掲載を試行させ、ガバナーが拒否した実判定です。"]
    (dds/table
     {:headers ["求人票" "HARD check" "理由"]
      :rows (for [{:keys [posting violations note]} held]
              [[:span [:strong (:title posting)] [:br]
                [:span {:class "meta"} (:employer posting) " · " (:jurisdiction posting) " · " (:id posting)
                 (when note (str " · " note))]]
               (into [:span {:class "mjs-hold-rules"}]
                     (for [v violations] [:span (chip (name (:rule v)) "red")]))
               (cstr/join " / " (map :detail violations))])}))

   (when (seq collected-pending)
     (dds/section
      {:title (str "実データ収集 — " (count collected-pending) " 件、賃金審査待ち(未掲載)")}
      [:p {:class "mjs-lead"}
       [:code "web/collect.cljs"] " が実在企業の公開求人API(Greenhouse Job Board API、"
       "認証不要・スクレイピングなし)から取得し、実 actor の "
       [:code ":jurisdiction/assess"] " を通過した実求人です。"
       [:strong "掲載(publish)は試行していません"] " — 求人元は時給レンジは開示しても"
       "月間労働時間まではコミットしないため、このactorの「時給×月間時間=表示賃金」"
       "という厳密な整合性チェックを満たす根拠がまだありません。ここで欠けている数値を"
       "こちら側で推定して埋めることは、まさにこのactorが防ごうとしている不正確な"
       "賃金表示そのものになるため、賃金の裏付けが取れるまで意図的に掲載を保留しています。"]
      (dds/table
       {:headers ["求人票(サンプル)" "法域" "求人元"]
        :rows (for [{:keys [posting]} (take 30 collected-pending)]
                [[:a {:href (:source-url posting)} (:title posting)]
                 (:jurisdiction posting)
                 (:employer posting)])})
      (when (> (count collected-pending) 30)
        [:p {:class "mjs-fine"} (count collected-pending) " 件中 30 件を表示(残り "
         (- (count collected-pending) 30) " 件は省略)。"])))

   (when (seq own-pending)
     (dds/section
      {:title (str "自社求人 — " (count own-pending) " 件、報酬額の確定待ち(未掲載)")}
      [:p {:class "mjs-lead"}
       "このボードの運営者自身が出した求人のうち、"
       [:strong "報酬額がまだ確定していないもの"] "です。実 actor の "
       [:code ":jurisdiction/assess"] " は通過していますが、"
       [:strong "掲載(publish)は試行していません"]
       " — 報酬を伏せたまま、あるいは仮の数字を置いて掲載することは"
       "的確表示義務(職業安定法5条の4)の趣旨に反するため、金額が確定するまで"
       "意図的に保留しています。確定した時点で通常の "
       [:code ":posting/publish"] " を通って上の掲載一覧に入ります。"]
      (dds/table
       {:headers ["求人票" "法域" "募集主体"]
        :rows (for [{:keys [posting]} own-pending]
                [[:a {:href (:source-url posting)} (:title posting)]
                 (:jurisdiction posting)
                 (:employer posting)])})))

   (when (seq referrals)
     (dds/section
      {:title "紹介デスクへのハンドオフ — 人間が運ぶ referral draft (ADR-2607131000)"}
      [:p {:class "mjs-lead"}
       "ボード上の求人への応募は、actor 間の直接呼び出しではなく人間が "
       [:a {:href "/cloud-itonami-isic-7810/"} "Placement Desk (isic-7810)"]
       " へ運ぶ referral 記録になります。応募者本人の同意なしには作成できず、"
       "記録が持つのは応募者への参照のみ(PII 本体はこの公開 actor の store に入りません)。"]
      (into [:ul {:class "mjs-referrals"}]
            (for [r referrals]
              [:li [:code (get r "record_id")] " → " (get r "posting_id")
               " (applicant: " (get r "applicant_ref") ") → 搬送先 " (get r "target")]))))

   (dds/section
    {:title "監査台帳 — 上の全実行が実際に書いた追記専用レコード"}
    [:p {:class "mjs-lead"}
     "掲載・取下げ・拒否のすべてが不変の台帳に残ります(的確表示義務コンプライアンスの証跡)。"
     "以下はページ生成時の実 actor 実行が書いた事実そのものです。"]
    [:pre (cstr/join "\n" (map ledger-line ledger))])

   (dds/section
    {:title "この検索インデックスが保証すること"}
    [:ul {:class "mjs-guarantees"}
     [:li "求人元が募集終了した求人は載らない(" [:strong "的確表示義務"] " — 職業安定法5条の4、令和4年改正)"]
     [:li "表示賃金は求人元記録からの独立再計算と常に一致する"]
     [:li "転載許諾が必要なソースの求人は、許諾確認なしに載らない"]
     [:li "保護属性に基づく差別的広告は載らない(均等法5条 / Title VII §704(b) / Equality Act 2010 / AGG §11)"]
     [:li "すべての掲載・取下げ・拒否が追記専用の監査台帳に残る"]])

   [:footer {:class "mjs-footer"}
    [:p {:class "cta"}
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/issues/new?template=operator-interest.yml"}
      "🡒 自分の地域・業界でこのボードを運営したい方はこちら(operator-interest)"]]
    [:p "OSS (AGPL-3.0-or-later)。fork して自分の求人ポータルとして運営できます — "
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/blob/main/docs/business-model.md"} "business model"]
     " · "
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/blob/main/docs/operator-guide.md"} "operator guide"]
     " · "
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/blob/main/docs/adr/0001-architecture.md"} "architecture ADR"]
     " · 姉妹デモ: "
     [:a {:href "https://cloud-itonami.github.io/cloud-itonami-isic-6310/"} "Talent Board (isic-6310)"]
     "。このページは " [:code "web/generate.cljs"] " (nbb) が実 actor を実行して生成し、検索は "
     [:code "search.cljs"] " (scittle = ブラウザ内 ClojureScript) が実行しています。"]]))

;; live-index postings as data for the in-browser search (search.cljs).
;; script は html.core の raw-text tag なので子は素の文字列で渡す
;; (ブラウザは script 内の実体参照を復号しないため、エスケープすると JSON が壊れる)。
(def scripts
  [[:script {:type "application/json" :id "postings-data"}
    (js/JSON.stringify (clj->js (mapv posting->json-entry live-index)))]
   [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.6.22/dist/scittle.js"}]
   ;; search.cljs は hiccup を html.core で文字列化する(生 HTML を書かない)ので、
   ;; そのライブラリもブラウザへ同梱する。読み込み順は依存順。
   [:script {:type "application/x-scittle" :src "html_core.cljs"}]
   [:script {:type "application/x-scittle" :src "search.cljs"}]])

(fs/mkdirSync "../docs" #js {:recursive true})
(fs/writeFileSync
 "../docs/index.html"
 (str (page/->page
       {:title "求人メタサーチ自社運営 — 営業電話なしで即¥80,000/月 | Meta Job Search (cloud-itonami-isic-6399)"
        :description "自治体・業界団体・求人媒体向け求人メタサーチ。Madgexは営業電話必須の非公開価格、このボードは即決フラット¥80,000/月。職業安定法5条の4(的確表示義務)を独立ガバナーが人間の承認でも覆せずHOLDする。"
        :lang "ja"
        :css dds-css
        :app-css app-css}
       body
       scripts)
      "\n"))
(fs/copyFileSync "search.cljs" "../docs/search.cljs")
;; ブラウザ側 .cljs はコピーするだけ(ビルド無し)。
(def html-root
  (or (some-> js/process.env.KOTOBA_HTML_ROOT not-empty) "../../../kotoba-lang/html"))
(fs/copyFileSync (str html-root "/src/html/core.cljc") "../docs/html_core.cljs")
(println (str "wrote docs/index.html (live-index " (count live-index)
              ", delisted " (count delisted)
              ", held " (count held)
              ", collected-pending " (count collected-pending)
              ", own-pending " (count own-pending)
              ", ledger " (count ledger) " facts)"))
