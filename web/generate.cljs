;; Generates docs/index.html (the GitHub Pages demo UI) from EDN/Hiccup via
;; kotoba-lang/html + kotoba-lang/css -- markup/styling as data, not
;; hand-quoted HTML strings -- following kototama/web/generate.cljs's own
;; precedent and the org's runtime priority (this is an nbb script; the
;; OUTPUT is a plain static page with no build step for a visiting browser;
;; in-browser interactivity is `search.cljs` run by scittle, i.e.
;; ClojureScript in the browser, not a hand-written .js file).
;;
;; The governor verdicts shown on the page are NOT hand-typed: this script
;; requires the actor's own `jobsearchops.registry` / `jobsearchops.facts`
;; (pure .cljc) and recomputes each demo posting's would-be HARD-hold the
;; same way `jobsearchops.governor` does, so the published page can never
;; drift from the actual compliance logic.
;;
;; Run (from this web/ directory, inside the monorepo checkout):
;;   ../../../../node_modules/.bin/nbb \
;;     --classpath "../src:../../../kotoba-lang/html/src:../../../kotoba-lang/css/src" \
;;     generate.cljs
(require '[html.core :as html]
         '[css.core :as css]
         '[jobsearchops.registry :as registry]
         '[jobsearchops.facts :as facts]
         '["fs" :as fs])

;; Demo posting set -- keep in sync with jobsearchops.store/demo-data
;; (store.cljc requires langchain.db, which is not on this script's
;; classpath, so the data is mirrored here rather than required).
(def postings
  [{:id "posting-1" :title "Warehouse Associate" :employer "Kita Logistics"
    :source "employer-direct"
    :source-hourly-wage 1500 :source-monthly-hours 160 :displayed-compensation 240000.0
    :ad-content-discriminatory? false :source-vacancy-closed? false
    :requires-source-consent? false :source-consent-verified? false
    :jurisdiction "JPN"}
   {:id "posting-2" :title "Warehouse Associate" :employer "Atlantis Freight"
    :source "employer-direct"
    :source-hourly-wage 1400 :source-monthly-hours 160 :displayed-compensation 224000.0
    :ad-content-discriminatory? false :source-vacancy-closed? false
    :requires-source-consent? false :source-consent-verified? false
    :jurisdiction "ATL"}
   {:id "posting-3" :title "Machine Operator" :employer "Minami Manufacturing"
    :source "employer-direct"
    :source-hourly-wage 1600 :source-monthly-hours 165 :displayed-compensation 300000.0
    :ad-content-discriminatory? false :source-vacancy-closed? false
    :requires-source-consent? false :source-consent-verified? false
    :jurisdiction "JPN"}
   {:id "posting-4" :title "Delivery Driver" :employer "Higashi Transport"
    :source "employer-direct"
    :source-hourly-wage 1550 :source-monthly-hours 160 :displayed-compensation 248000.0
    :ad-content-discriminatory? true :source-vacancy-closed? false
    :requires-source-consent? false :source-consent-verified? false
    :jurisdiction "JPN"}
   {:id "posting-5" :title "Line Cook" :employer "Nishi Dining"
    :source "board-crawl"
    :source-hourly-wage 1450 :source-monthly-hours 160 :displayed-compensation 232000.0
    :ad-content-discriminatory? false :source-vacancy-closed? false
    :requires-source-consent? true :source-consent-verified? false
    :jurisdiction "JPN"}
   {:id "posting-6" :title "Line Cook" :employer "Chuo Kitchen"
    :source "partner-feed"
    :source-hourly-wage 1500 :source-monthly-hours 160 :displayed-compensation 240000.0
    :ad-content-discriminatory? false :source-vacancy-closed? false
    :requires-source-consent? true :source-consent-verified? true
    :jurisdiction "JPN"}
   {:id "posting-7" :title "Retail Clerk" :employer "Minato Retail"
    :source "employer-direct"
    :source-hourly-wage 1300 :source-monthly-hours 150 :displayed-compensation 195000.0
    :ad-content-discriminatory? false :source-vacancy-closed? true
    :requires-source-consent? false :source-consent-verified? false
    :jurisdiction "JPN"}])

(defn hold
  "Recompute the posting's would-be HARD hold the same way
  `jobsearchops.governor` does (same predicates, same order), using the
  actor's own registry/facts .cljc -- nil means the governor would let
  a human publish it."
  [p]
  (cond
    (nil? (facts/spec-basis (:jurisdiction p)))
    {:rule "no-spec-basis"
     :ja (str (:jurisdiction p) " は facts 未登録の法域 -- 要件を創作しない")}

    (:source-vacancy-closed? p)
    {:rule "stale-vacancy"
     :ja "求人元が既に募集終了 -- 的確表示義務(職業安定法5条の4)により掲載不可"}

    (:ad-content-discriminatory? p)
    {:rule "ad-content-discriminatory"
     :ja "広告内容が保護属性に基づく条件を含む(均等法5条/労働施策総合推進法9条)"}

    (not (registry/displayed-compensation-matches-claim? p))
    {:rule "displayed-compensation-mismatch"
     :ja (str "表示賃金 " (:displayed-compensation p) " が独立再計算値 "
              (registry/compute-displayed-compensation p) " と不一致")}

    (and (:requires-source-consent? p) (not (:source-consent-verified? p)))
    {:rule "source-consent-unverified"
     :ja "求人元の転載許諾が未確認(データベース権/転載許諾)"}

    :else nil))

(def published (vec (remove hold postings)))
(def held (vec (keep #(when-let [h (hold %)] (assoc % :hold h)) postings)))

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
      " のライブデモ(合成データ)。"]]

    [:div.search
     [:input {:id "q" :type "search" :placeholder "職種・雇用主・キーワードで検索…"
              :autocomplete "off"}]
     [:select {:id "jur"}
      [:option {:value ""} "全法域"]
      [:option {:value "JPN"} "日本 (JPN)"]]]

    [:div {:id "results"}]
    [:p {:id "empty" :hidden true} "該当する求人はありません。"]

    [:h2 "Governor transparency — 掲載を拒否した求人票"]
    [:p "Indeed 型アグリゲーターとの違いはここです: 掲載판단は LLM でも運営者の裁量でもなく、"
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-isic-6399/blob/main/src/jobsearchops/governor.cljc"}
      "独立ガバナー"]
     " の HARD check が下します(人間の承認でも覆せません)。この表の判定はこのページの生成時に"
     "実際の " [:code "jobsearchops.registry"] "/" [:code "jobsearchops.facts"]
     " (.cljc) を実行して再計算したものです。"]
    [:table
     [:thead [:tr [:th "求人票"] [:th "HARD check"] [:th "理由"]]]
     (into [:tbody]
           (for [p held]
             [:tr
              [:td [:strong (:title p)] [:br]
               [:span.meta (:employer p) " · " (:jurisdiction p) " · " (:id p)]]
              [:td [:span.badge.hold (get-in p [:hold :rule])]]
              [:td (get-in p [:hold :ja])]]))]

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
      "。このページは " [:code "web/generate.cljs"] " (nbb) が生成し、検索は "
      [:code "search.cljs"] " (scittle = ブラウザ内 ClojureScript) が実行しています。"]]

    ;; published postings as data for the in-browser search (search.cljs).
    ;; [:hiccup/raw ...] because script elements are raw text -- the browser
    ;; never decodes entities inside them, so escaping would corrupt the
    ;; JSON (html.core's trusted escape hatch, same as kototama/web's own
    ;; script block). The JSON contains no "<", so raw embedding is safe.
    [:script {:type "application/json" :id "postings-data"}
     [:hiccup/raw (js/JSON.stringify (clj->js (mapv posting->json-entry published)))]]
    [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.6.22/dist/scittle.js"}]
    [:script {:type "application/x-scittle" :src "search.cljs"}]]])

(fs/mkdirSync "../docs" #js {:recursive true})
(fs/writeFileSync "../docs/index.html" (str "<!doctype html>\n" (html/render page) "\n"))
(fs/copyFileSync "search.cljs" "../docs/search.cljs")
(println (str "wrote docs/index.html (" (count published) " published, "
              (count held) " held)"))
