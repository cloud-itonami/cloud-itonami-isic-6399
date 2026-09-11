;; In-browser search over the published postings -- ClojureScript run by
;; scittle (no build step, no hand-written .js), per the org's runtime
;; rules. Data is the JSON the generator embedded in #postings-data;
;; only governor-passed (published) postings are ever in it.
(ns jobsearch.search)
;;
;; カードは **hiccup で組み html.core が文字列化する** — 生 HTML の文字列連結は
;; しない(生成側 generate.cljs と同じ規約)。html.core が属性値もテキストも
;; エスケープするので自前の esc は不要になった。html_core.cljs は
;; generate.cljs がページと一緒に同梱する。
;;
;; html.core は ns の :require ではなく **完全修飾**で呼ぶ — headless ハーネス
;; (verify_search.cljs)がこのファイルを load-string で評価するが、nbb の
;; load-string は ns の :require を解決できないため(実測 "Doesn't support name:")。
;;
;; 子の並びは必ず [:<> ...] フラグメントで包む — 素のベクタだとヒット0件のとき
;; `[]` になり html.core がタグ付きノードと解釈して落ちる。


(def postings
  (js->clj (js/JSON.parse (.-textContent (js/document.getElementById "postings-data")))
           :keywordize-keys true))


;; dds-ext-card は jp-go-dds の layout 拡張(生成側の静的カードと同じ見た目)、
;; mjs-card は本ページ固有の中身の字送り。governor-passed バッジは DADS の
;; chip-label(filled-1 / green)。どれも generate.cljs 側で定義済み。
(defn- card [p]
  [:div {:class "dds-ext-card mjs-card"}
   [:h3 (:title p)
    [:span {:class "dads-chip-label" :data-style "filled-1" :data-color "green"}
     "governor-passed"]]
   [:div {:class "meta"} (:employer p) " · " (:jurisdiction p) " · "
    [:span {:class "chip"} (:source p)]]
   [:div {:class "pay"} (:pay p) " "
    [:span {:class "meta"} "(" (:wage p) " — 求人元記録と独立再計算が一致)"]]
   (when (:correction p)
     [:div {:class "meta"} "訂正済 " [:span {:class "chip"} (:correction p)]
      " — 求人元の変更を governed correction で反映(職安法5条の4)"])])

(defn- matches? [p q jur src]
  (and (or (= jur "") (= jur (:jurisdiction p)))
       (or (= src "") (= src (:source p)))
       (or (= q "")
           (.includes (.toLowerCase (str (:title p) " " (:employer p) " " (:source p)))
                      q))))

(defn- render! []
  (let [q (.toLowerCase (.-value (js/document.getElementById "q")))
        jur (.-value (js/document.getElementById "jur"))
        src (.-value (js/document.getElementById "src"))
        hits (filter #(matches? % q jur src) postings)]
    (set! (.-innerHTML (js/document.getElementById "results"))
          (html.core/->html (into [:<>] (map card hits))))
    (set! (.-hidden (js/document.getElementById "empty")) (boolean (seq hits)))))

(.addEventListener (js/document.getElementById "q") "input" render!)
(.addEventListener (js/document.getElementById "jur") "change" render!)
(.addEventListener (js/document.getElementById "src") "change" render!)
(render!)
