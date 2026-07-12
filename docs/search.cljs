;; In-browser search over the published postings -- ClojureScript run by
;; scittle (no build step, no hand-written .js), per the org's runtime
;; rules. Data is the JSON the generator embedded in #postings-data;
;; only governor-passed (published) postings are ever in it.
(ns jobsearch.search)

(def postings
  (js->clj (js/JSON.parse (.-textContent (js/document.getElementById "postings-data")))
           :keywordize-keys true))

(defn- esc [s]
  (-> (str s)
      (.replaceAll "&" "&amp;")
      (.replaceAll "<" "&lt;")
      (.replaceAll ">" "&gt;")))

(defn- card-html [p]
  (str "<div class=\"card\">"
       "<h3>" (esc (:title p))
       "<span class=\"badge ok\">governor-passed</span></h3>"
       "<div class=\"meta\">" (esc (:employer p)) " · " (esc (:jurisdiction p))
       " · <span class=\"chip\">" (esc (:source p)) "</span></div>"
       "<div class=\"pay\">" (esc (:pay p))
       " <span class=\"meta\">(" (esc (:wage p)) " — 求人元記録と独立再計算が一致)</span></div>"
       "</div>"))

(defn- matches? [p q jur]
  (and (or (= jur "") (= jur (:jurisdiction p)))
       (or (= q "")
           (.includes (.toLowerCase (str (:title p) " " (:employer p) " " (:source p)))
                      q))))

(defn- render! []
  (let [q (.toLowerCase (.-value (js/document.getElementById "q")))
        jur (.-value (js/document.getElementById "jur"))
        hits (filter #(matches? % q jur) postings)]
    (set! (.-innerHTML (js/document.getElementById "results"))
          (apply str (map card-html hits)))
    (set! (.-hidden (js/document.getElementById "empty")) (boolean (seq hits)))))

(.addEventListener (js/document.getElementById "q") "input" render!)
(.addEventListener (js/document.getElementById "jur") "change" render!)
(render!)
