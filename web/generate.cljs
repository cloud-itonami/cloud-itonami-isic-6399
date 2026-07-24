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
;;     --classpath "../src:../../../kotoba-lang/html/src:../../../kotoba-lang/css/src:../../../kotoba-lang/langchain/src:../../../kotoba-lang/langchain-store/src:../../../kotoba-lang/langgraph/src" \
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

;; Real postings web/collect.cljs ingested and assessed clean through
;; the real actor, but did not attempt to publish (compensation ground
;; truth unverified -- see compensation-verified? above). Demo mode has
;; none of these; the demo's own postings all carry full ground truth.
(def collected-pending
  (if operator-postings
    (vec (filter #(= :pending (:kind %)) operator-results))
    []))

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
     "select#jur, select#src" {:font-size 15 :padding "10px 12px"
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
     "footer p.cta" {:font-size 15 :font-weight 600 :color "var(--fg)" :margin-bottom 14}
     "a" {:color "var(--accent)"}
     "code" {:background "var(--card)" :padding "1px 5px" :border-radius 4
             :font-size "0.9em"}
     ".pitch" {:background "var(--card)" :border "1px solid var(--line)"
               :border-radius 12 :padding "20px 22px" :margin-top 20}
     ".pitch h2" {:margin-top 0 :border-top "none" :padding-top 0 :font-size 18}
     ".pitch table" {:margin-top 14}
     ".pitch .ctarow" {:display :flex :gap 10 :flex-wrap :wrap :margin-top 18}
     ".btn" {:display :inline-block :font-size 14 :font-weight 700
             :padding "10px 18px" :border-radius 8 :text-decoration :none}
     ".btn.primary" {:background "var(--accent)" :color "#ffffff"}
     ".btn.secondary" {:background "transparent" :color "var(--fg)"
                       :border "1.5px solid var(--line)"}
     ".pitch .fine" {:color "var(--muted)" :font-size 12.5 :margin-top 10}}
    :media
    {"(prefers-color-scheme: dark)"
     {":root" {:--fg "#e6edf3" :--bg "#0d1117" :--muted "#8d96a0"
               :--card "#161b22" :--line "#30363d" :--accent "#58a6ff"
               :--ok-bg "#12261e" :--ok-fg "#3fb950"
               :--hold-bg "#2d1215" :--hold-fg "#f85149"}}}}))

;; Two ground-truth shapes reach here (jobsearchops.registry's own ns
;; docstring): EXACT (hand-authored/demo, JPY hourly x monthly-hours)
;; and RANGE (real job-board data -- jobsearchops.ingest, USD hourly
;; range verbatim from the source). `fmt-yen` on a RANGE posting's nil
;; :displayed-compensation would silently render "¥0/月" -- wrong, not
;; just ugly -- so both fields dispatch on shape here too.
(defn- range-shaped? [p] (some? (:source-compensation-min p)))

(defn posting->json-entry [p]
  {:id (:id p) :title (:title p) :employer (:employer p)
   :jurisdiction (:jurisdiction p) :source (:source p)
   :publication (:publication-number p)
   :correction (:correction-number p)
   :pay (if (range-shaped? p)
          (str "$" (:displayed-compensation-min p) "–$" (:displayed-compensation-max p) "/時")
          (fmt-yen (:displayed-compensation p)))
   :wage (if (range-shaped? p)
           (str "求人元開示レンジ $" (:source-compensation-min p) "–$" (:source-compensation-max p) "/時")
           (str "時給 ¥" (.format yen (:source-hourly-wage p))
                " × " (:source-monthly-hours p) "h"))})

(def page
  [:html {:lang "ja"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "求人メタサーチ自社運営 — 営業電話なしで即¥80,000/月 | Meta Job Search (cloud-itonami-isic-6399)"]
    [:meta {:name "description"
            :content "自治体・業界団体・求人媒体向け求人メタサーチ。Madgexは営業電話必須の非公開価格、このボードは即決フラット¥80,000/月。職業安定法5条の4(的確表示義務)を独立ガバナーが人間の承認でも覆せずHOLDする。"}]
    stylesheet]
   [:body
    [:header
     [:h1 "Meta Job Search " [:span.badge.ok "governed"]]
     [:p.sub "求人メタサーチ — 独立ガバナーの検査を通過した求人だけが載る検索インデックス。 "
      [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399"} "cloud-itonami-isic-6399"]
      " のライブデモ(合成データ)。このページの内容はすべて、生成時に実 actor"
      "(StateGraph + Governor)を実行した結果です。"]]

    [:div.pitch
     [:h2 "見積もりのために営業電話、していませんか?"]
     [:p "Madgexは価格非公開・sales-gated(問い合わせ必須)。JobBoard.io・JBoard は公開価格が"
      "あるものの月額$249〜849。このボードは"
      [:strong " 即決フラット ¥80,000/月"] "、営業プロセス不要で今すぐ始められます。"]
     [:table
      [:thead [:tr [:th "求人ボードSaaS"] [:th "価格の出し方"] [:th "実勢価格"]]]
      [:tbody
       [:tr [:td "Madgex"] [:td "非公開(要問い合わせ)"] [:td "$500+/月〜(要見積)"]]
       [:tr [:td "JobBoard.io"] [:td "公開・段階制"] [:td "$449〜649/月"]]
       [:tr [:td "JBoard"] [:td "公開・段階制"] [:td "$249〜849/月"]]
       [:tr [:td "engage (日本)"] [:td "無料+従量課金"] [:td "掲載無料 + ¥7,000/日 配信"]]
       [:tr [:td [:strong "このボード"]] [:td [:strong "即決・公開・フラット"]] [:td [:strong "¥80,000/月"]]]]]
     [:p "さらに、令和4年職業安定法改正の"
      [:strong "的確表示義務(5条の4)"] "を独立ガバナーが構造的に検査 — "
      "賃金表示・転載許諾・差別的広告のいずれかで不合格の求人は、人間の承認があっても"
      "検索インデックスに載りません。"]
     [:div.ctarow
      [:a.btn.primary {:href "https://buy.stripe.com/bJe9AS74n1dmcOQcEvbMQ0b"}
       "🡒 Managed Job Board を購読(¥80,000/月)"]
      [:a.btn.secondary {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/issues/new"}
       "自前運用(セルフホスト)に興味がある"]]
     [:p.fine "価格根拠: "
      [:a {:href "https://github.com/com-junkawasaki/root/blob/main/90-docs/pricing-intelligence/pricing-intelligence-ledger.edn"}
       "6社の実競合調査(2026-07-16)"]
      " — 下の技術デモは合成データによる実 actor 実行結果、この価格比較表とは独立して生成されています。"]]

    [:div.search
     [:input {:id "q" :type "search" :placeholder "職種・雇用主・キーワードで検索…"
              :autocomplete "off"}]
     (into [:select {:id "jur"} [:option {:value ""} "全法域"]]
           (for [j (sort (distinct (map :jurisdiction live-index)))]
             [:option {:value j} j]))
     [:select {:id "src"}
      [:option {:value ""} "全ソース"]
      [:option {:value "employer-direct"} "雇用主直接"]
      [:option {:value "partner-feed"} "提携フィード"]
      [:option {:value "board-crawl"} "許諾クロール"]]]

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

    (when (seq collected-pending)
      [:div
       [:h2 "実データ収集 — " (count collected-pending) " 件、賃金審査待ち(未掲載)"]
       [:p [:code "web/collect.cljs"] " が実在企業の公開求人API(Greenhouse Job Board API、"
        "認証不要・スクレイピングなし)から取得し、実 actor の "
        [:code ":jurisdiction/assess"] " を通過した実求人です。"
        [:strong "掲載(publish)は試行していません"] " — 求人元は時給レンジは開示しても"
        "月間労働時間まではコミットしないため、このactorの「時給×月間時間=表示賃金」"
        "という厳密な整合性チェックを満たす根拠がまだありません。ここで欠けている数値を"
        "こちら側で推定して埋めることは、まさにこのactorが防ごうとしている不正確な"
        "賃金表示そのものになるため、賃金の裏付けが取れるまで意図的に掲載を保留しています。"]
       [:table
        [:thead [:tr [:th "求人票(サンプル)"] [:th "法域"] [:th "求人元"]]]
        (into [:tbody]
              (for [{:keys [posting]} (take 30 collected-pending)]
                [:tr
                 [:td [:a {:href (:source-url posting)} (:title posting)]]
                 [:td (:jurisdiction posting)]
                 [:td (:employer posting)]]))]
       (when (> (count collected-pending) 30)
         [:p.meta (count collected-pending) " 件中 30 件を表示(残り "
          (- (count collected-pending) 30) " 件は省略)。"])])

    (when (seq referrals)
      [:div
       [:h2 "紹介デスクへのハンドオフ — 人間が運ぶ referral draft (ADR-2607131000)"]
       [:p "ボード上の求人への応募は、actor 間の直接呼び出しではなく人間が "
        [:a {:href "/cloud-itonami-isic-7810/"} "Placement Desk (isic-7810)"]
        " へ運ぶ referral 記録になります。応募者本人の同意なしには作成できず、"
        "記録が持つのは応募者への参照のみ(PII 本体はこの公開 actor の store に入りません)。"]
       (into [:ul]
             (for [r referrals]
               [:li [:code (get r "record_id")] " → " (get r "posting_id")
                " (applicant: " (get r "applicant_ref") ") → 搬送先 " (get r "target")]))])

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
     [:p.cta [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/issues/new?template=operator-interest.yml"}
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
              ", collected-pending " (count collected-pending)
              ", ledger " (count ledger) " facts)"))
