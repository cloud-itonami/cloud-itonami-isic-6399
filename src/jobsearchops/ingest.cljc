(ns jobsearchops.ingest
  "Pure normalization: one real Greenhouse Job Board API job record ->
  this actor's posting-record shape (see `postings.example.edn`). No
  network I/O lives here (that's `web/collect.cljs`) so this is
  unit-testable in isolation, same split as `jobsearchops.registry`
  keeping ground-truth math separate from the graph that calls it.

  Greenhouse's Job Board API (https://developers.greenhouse.io/job-
  board.html) is public, unauthenticated, read-only, and documented
  BY Greenhouse for exactly this kind of external aggregation/embedding
  -- every employer using it has already chosen to publish these jobs
  for programmatic consumption. No scraping, no bot-detection evasion,
  no ToS violation.

  COMPENSATION FIELDS ARE DELIBERATELY NOT POPULATED HERE. This
  actor's `:source-hourly-wage`/`:source-monthly-hours` fields are
  documented (postings.example.edn) as 'the source record's own
  ground truth' -- but real job postings almost never state a
  committed monthly-hours figure alongside an hourly rate (they state
  a pay RANGE and shift times, not a fixed rate x guaranteed hours).
  Inventing the missing half to satisfy `jobsearchops.registry`'s
  wage x hours recompute would be exactly the fabricated-ground-truth
  problem this actor's governor exists to catch -- just moved one
  layer upstream of where it could catch it. So every posting this ns
  produces carries `:compensation-verified? false`; `web/generate.cljs`
  reads that flag and assesses-but-does-not-publish such postings
  (see its operator-mode lifecycle). Filling that gap responsibly is
  an explicitly deferred follow-up, not solved here."
  (:require [clojure.string :as str]))

(def jurisdiction-patterns
  "iso3 -> regexes tried in order against a Greenhouse job's
  `location.name`. Only jurisdictions actually in
  `jobsearchops.facts/catalog` are usable -- a posting whose location
  matches none of these is skipped by `job->posting`, never guessed."
  [["JPN" [#"(?i)\bjapan\b" #"(?i)\btokyo\b" #"(?i)\bosaka\b" #"(?i)\byokohama\b"]]
   ["GBR" [#"(?i)united kingdom" #"(?i)\bUK\b" #"(?i)\blondon\b"]]
   ["DEU" [#"(?i)\bgermany\b" #"(?i)\bberlin\b" #"(?i)\bmunich\b" #"(?i)\bhamburg\b"]]
   ["FRA" [#"(?i)\bfrance\b" #"(?i)\bparis\b"]]
   ["KOR" [#"(?i)south korea" #"(?i)\bkorea\b" #"(?i)\bseoul\b"]]])

;; USPS 2-letter state/territory codes -- checked as a WHOLE-CODE match
;; (word-boundary, comma-delimited) so e.g. "DE" (Delaware) never
;; collides with a bare substring match; "US"/"USA"/"United States" are
;; matched directly too. Checked only after the jurisdiction-specific
;; patterns above have had a chance to match a location string.
(def us-state-codes
  #{"AL" "AK" "AZ" "AR" "CA" "CO" "CT" "DE" "FL" "GA" "HI" "ID" "IL" "IN" "IA"
    "KS" "KY" "LA" "ME" "MD" "MA" "MI" "MN" "MS" "MO" "MT" "NE" "NV" "NH" "NJ"
    "NM" "NY" "NC" "ND" "OH" "OK" "OR" "PA" "RI" "SC" "SD" "TN" "TX" "UT" "VT"
    "VA" "WA" "WV" "WI" "WY" "DC"})

(defn- usa-location? [s]
  (or (re-find #"(?i)united states" s)
      (re-find #"(?i)\bUSA\b" s)
      (re-find #"(?i)\bU\.S\.?\b" s)
      (boolean (some (fn [[_ code]] (us-state-codes (str/upper-case code)))
                     (re-seq #",\s*([A-Za-z]{2})\b" s)))))

(defn detect-jurisdiction
  "First iso3 in `jobsearchops.facts/catalog` whose patterns match
  `location-name`, or nil (never guessed) if none match."
  [location-name]
  (let [s (or location-name "")]
    (or (some (fn [[iso3 patterns]]
                (when (some #(re-find % s) patterns) iso3))
              jurisdiction-patterns)
        (when (usa-location? s) "USA"))))

(def discriminatory-pattern
  "Coarse, DOCUMENTED-as-heuristic pre-screen for ad content relying
  on a protected characteristic (age/gender-restrictive phrasing) in
  a posting's own title/plain-text content -- NOT a legal
  determination. A real deployment must still route every posting
  (flagged or not) through actual legal review; this only keeps the
  most obvious violations from being auto-ingested unflagged."
  #"(?i)\b(women only|men only|no women|no men|young (?:workers?|staff) only)\b")

(defn discriminatory-heuristic? [text]
  (boolean (re-find discriminatory-pattern (or text ""))))

(defn strip-html
  "Greenhouse's `content` field is a raw HTML fragment; strips tags for
  the plain-text the discriminatory-content heuristic scans. Not a
  full HTML parser -- good enough for a keyword pre-screen, not used
  for anything actuation-bearing."
  [html]
  (when html
    (-> html
        (str/replace #"<[^>]*>" " ")
        (str/replace #"&nbsp;" " ")
        (str/replace #"&amp;" "&")
        (str/replace #"\s+" " ")
        str/trim)))

(defn job->posting
  "Normalizes one Greenhouse Job Board API job (as parsed EDN/JSON,
  keyword keys) into this actor's posting-record shape, or nil if the
  job's location doesn't match a jurisdiction this actor covers
  (skipped honestly, not guessed -- see ns docstring).

  `source` is the operator-supplied provenance tag for this board
  (e.g. \"employer-direct\" for a company's own official job board)."
  [{:keys [id title company_name location content absolute_url updated_at]} source]
  (when-let [iso3 (detect-jurisdiction (:name location))]
    (let [plain (strip-html content)]
      {:id (str "gh-" id)
       :title title
       :employer company_name
       :source source
       :source-url absolute_url
       :source-updated-at updated_at
       :ad-content-discriminatory? (discriminatory-heuristic? (str title " " plain))
       :source-vacancy-closed? false
       :requires-source-consent? false
       :source-consent-verified? false
       :published? false :delisted? false
       :jurisdiction iso3 :status :ingested
       ;; see ns docstring -- wage-judgment deferred, generate.cljs
       ;; assesses this posting but skips :posting/publish for it.
       :compensation-verified? false})))

(defn collect
  "Normalizes every job in `jobs` (a seq of Greenhouse API job maps)
  for `source`, dropping jurisdiction-unmatched jobs. Returns
  {:postings [...] :skipped-count n} -- the skip count is reported,
  never silently dropped (coverage reported honestly, same discipline
  `jobsearchops.facts`'s own ns docstring commits to)."
  [jobs source]
  (let [normalized (keep #(job->posting % source) jobs)]
    {:postings (vec normalized)
     :skipped-count (- (count jobs) (count normalized))}))

(defn close-vanished
  "Given `previous-postings` (this connector's own prior output, i.e.
  every posting in the old `web/postings.edn` whose `:id` starts with
  \"gh-\") and `fresh-postings` (this run's `collect` output for the
  SAME source), returns fresh-postings plus every previously-collected
  posting no longer present in this run, carried forward with
  `:source-vacancy-closed? true`.

  Greenhouse's API only ever returns currently-OPEN postings --
  closed/filled roles simply disappear from the response. So a
  posting's disappearance from THIS run IS the source's own closure
  signal (not an assumption): the actor's real stale-vacancy HARD-hold
  (jobsearchops.governor) then keeps/removes it from the live index on
  its own, exactly the flow that already handles the demo's own
  posting-1 delisting story."
  [previous-postings fresh-postings]
  (let [fresh-ids (set (map :id fresh-postings))
        gh? #(str/starts-with? (:id %) "gh-")
        vanished (->> previous-postings
                      (filter gh?)
                      (remove #(fresh-ids (:id %)))
                      (map #(assoc % :source-vacancy-closed? true)))
        kept-non-gh (remove gh? previous-postings)]
    (vec (concat kept-non-gh vanished fresh-postings))))
