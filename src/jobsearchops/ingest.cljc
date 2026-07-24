(ns jobsearchops.ingest
  "Pure normalization: one real job-board-API job record -> this
  actor's posting-record shape (see `postings.example.edn`). No
  network I/O lives here (that's `web/collect.cljs`) so this is
  unit-testable in isolation, same split as `jobsearchops.registry`
  keeping ground-truth math separate from the graph that calls it.

  TWO PLATFORMS, both public/unauthenticated/documented-by-the-vendor
  for exactly this kind of external aggregation -- no scraping, no
  bot-detection evasion, no ToS violation, every employer using either
  has already chosen to publish its postings this way:
    - Greenhouse Job Board API (https://developers.greenhouse.io/job-
      board.html) -- `job->posting`, id prefix \"gh-\".
    - Lever Postings API (https://github.com/lever/postings-api) --
      `lever-job->posting`, id prefix \"lv-\". Lever's own `country`
      field is an ISO alpha-2 code disclosed directly by the source,
      preferred over free-text location parsing when present (see
      `detect-jurisdiction`) -- more reliable than Greenhouse's
      free-text `location.name`, which has no equivalent structured
      field.

  COMPENSATION: this actor's ORIGINAL `:source-hourly-wage`/
  `:source-monthly-hours` fields are documented (postings.example.edn)
  as 'the source record's own ground truth' -- but real job postings
  almost never state a committed monthly-hours figure alongside an
  hourly rate (they state a pay RANGE and shift times, not a fixed
  rate x guaranteed hours). Inventing the missing half to satisfy
  `jobsearchops.registry`'s original wage x hours recompute would be
  exactly the fabricated-ground-truth problem this actor's governor
  exists to catch, one layer upstream of where it could catch it.

  So this ns extracts the RANGE shape instead (`jobsearchops.registry`
  now checks displayed-range-within-source-range, not just wage x
  hours -- see its ns docstring): `extract-hourly-range` reads an
  UNAMBIGUOUS \"$lo-$hi (per) hour\" range the source itself printed
  and nothing else -- no currency conversion, no annual-to-hourly
  guess, no filling in a number the source didn't state. A posting
  where no such range is found keeps `:compensation-verified? false`
  and NO compensation fields at all; `web/generate.cljs` reads that
  flag and assesses-but-does-not-publish such postings.

  KNOWN LIMITATION: `extract-hourly-range` only recognizes a literal
  \"$\" (USD) hourly range -- the only disclosure shape verified
  against real data so far (Greenhouse-hosted US postings; see
  test/jobsearchops/ingest_test.clj's real fixtures). Non-USD
  disclosures (a real GBR/DEU/FRA/JPN/KOR posting stating its own
  wage in its own currency) are a further follow-up once real verified
  examples of that disclosure shape are found -- not guessed at here."
  (:require [clojure.string :as str]))

(defn- parse-wage-number [s]
  #?(:clj (Double/parseDouble s)
     :cljs (js/parseFloat s)))

(def hourly-range-pattern
  "Matches an UNAMBIGUOUS USD hourly-wage range the source itself
  printed, e.g. \"$17-$19 hourly\", \"$17.50 - $19.00 per hour\",
  \"$17/hr - $19/hr\" -- verified against real Carvana postings
  (test/jobsearchops/ingest_test.clj). Requires the trailing hour/hr
  unit so it doesn't mistake an unrelated dollar range (a signing
  bonus, an equipment allowance) for a wage; requires no comma in
  either number so it doesn't mistake a big comma-formatted annual
  salary figure for an hourly one."
  #"(?i)\$(\d+(?:\.\d+)?)\s*(?:/\s*hr)?\s*(?:-|–|to)\s*\$(\d+(?:\.\d+)?)\s*(?:/|per\s*)?\s*(?:hr\b|hour)")

(defn extract-hourly-range
  "[min max] USD hourly compensation `text` unambiguously states, or
  nil if none found or the extracted bounds are implausible (min <= 0,
  min > max, or max over 200 -- a generous ceiling for ANY real
  hourly wage; catches a mis-matched annual figure some other way).
  Never estimates -- only ever reads two numbers the source itself
  printed."
  [text]
  (when-let [[_ lo hi] (re-find hourly-range-pattern (or text ""))]
    (let [lo (parse-wage-number lo) hi (parse-wage-number hi)]
      (when (and (pos? lo) (<= lo hi) (<= hi 200.0))
        [lo hi]))))

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

;; USPS 2-letter state/territory codes -- checked as a WHOLE-CODE,
;; case-sensitive match (word-boundary) so e.g. "DE" (Delaware) never
;; collides with a bare substring match; "US"/"USA"/"United States" are
;; matched directly too. Checked only after the jurisdiction-specific
;; patterns above have had a chance to match a location string. Match
;; is CASE-SENSITIVE uppercase (real Greenhouse `location.name` values
;; print state codes in caps, e.g. "CA - Remote", "Wichita, KS") so an
;; ordinary lowercase word never collides; codes are checked as a
;; standalone 2-letter token regardless of the punctuation around it
;; (comma OR dash OR semicolon all appear in real data: "Wichita, KS",
;; "CA - Remote", "Remote, CA, US; Remote, WA, US").
(def us-state-codes
  #{"AL" "AK" "AZ" "AR" "CA" "CO" "CT" "DE" "FL" "GA" "HI" "ID" "IL" "IN" "IA"
    "KS" "KY" "LA" "ME" "MD" "MA" "MI" "MN" "MS" "MO" "MT" "NE" "NV" "NH" "NJ"
    "NM" "NY" "NC" "ND" "OH" "OK" "OR" "PA" "RI" "SC" "SD" "TN" "TX" "UT" "VT"
    "VA" "WA" "WV" "WI" "WY" "DC"})

(defn- usa-location? [s]
  (or (re-find #"(?i)united states" s)
      (re-find #"(?i)\bUSA\b" s)
      (re-find #"(?i)\bU\.S\.?\b" s)
      (boolean (some us-state-codes (re-seq #"\b[A-Z]{2}\b" s)))))

(def alpha2->iso3
  "ISO 3166-1 alpha-2 -> alpha-3, only for the jurisdictions this
  actor covers (`jobsearchops.facts/catalog`). For a source that
  discloses a clean country code directly (Lever's own `country`
  field) -- exact-matched, bypassing free-text location parsing
  entirely, so more reliable than `location-name` regex matching when
  available."
  {"JP" "JPN" "GB" "GBR" "DE" "DEU" "FR" "FRA" "KR" "KOR" "US" "USA"})

(defn detect-jurisdiction
  "First iso3 in `jobsearchops.facts/catalog` matching `country-code`
  (an ISO alpha-2 code, exact-matched, preferred when present) or
  `location-name` (free text, regex-matched), or nil (never guessed)
  if neither matches."
  ([location-name] (detect-jurisdiction location-name nil))
  ([location-name country-code]
   (or (get alpha2->iso3 country-code)
       (let [s (or location-name "")]
         (or (some (fn [[iso3 patterns]]
                     (when (some #(re-find % s) patterns) iso3))
                   jurisdiction-patterns)
             (when (usa-location? s) "USA"))))))

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

(defn- assemble-posting
  "Shared posting-record assembly once a platform-specific normalizer
  has extracted id/title/employer/source-url/updated-at/iso3 and the
  plain text to scan for wage/discriminatory-content signals. Not
  public -- `job->posting`/`lever-job->posting` are the entry points."
  [{:keys [id title employer source source-url source-updated-at iso3 scan-text]}]
  (let [wage-range (extract-hourly-range scan-text)]
    (cond-> {:id id
             :title title
             :employer employer
             :source source
             :source-url source-url
             :source-updated-at source-updated-at
             :ad-content-discriminatory? (discriminatory-heuristic? scan-text)
             :source-vacancy-closed? false
             :requires-source-consent? false
             :source-consent-verified? false
             :published? false :delisted? false
             :jurisdiction iso3 :status :ingested
             ;; see ns docstring -- only true when the source itself
             ;; printed an unambiguous wage range; generate.cljs
             ;; assesses-but-does-not-publish false postings.
             :compensation-verified? (some? wage-range)}
      wage-range
      (assoc :source-compensation-min (first wage-range)
             :source-compensation-max (second wage-range)
             ;; displayed verbatim as the source's own range -- never
             ;; narrowed/widened, so it trivially satisfies
             ;; jobsearchops.registry's containment check while still
             ;; being independently re-verified there, not trusted
             ;; blindly.
             :displayed-compensation-min (first wage-range)
             :displayed-compensation-max (second wage-range)))))

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
      (assemble-posting {:id (str "gh-" id) :title title :employer company_name
                          :source source :source-url absolute_url
                          :source-updated-at updated_at :iso3 iso3
                          :scan-text (str title " " plain)}))))

(defn lever-job->posting
  "Normalizes one Lever Postings API job (as parsed EDN/JSON, keyword
  keys) into this actor's posting-record shape, or nil if unmatched --
  same contract as `job->posting`. Lever's own `country` (ISO
  alpha-2) is preferred for jurisdiction detection over its free-text
  `categories.location` -- see `detect-jurisdiction`.

  `employer` is supplied by the caller (Lever's per-job payload has no
  company-name field of its own -- the board token IS the company,
  known from `web/sources.edn`, not from the API response). `source`
  is the same operator-supplied provenance tag `job->posting` takes."
  [{:keys [id text categories country descriptionPlain hostedUrl createdAt]} employer source]
  (when-let [iso3 (detect-jurisdiction (:location categories) country)]
    (assemble-posting {:id (str "lv-" id) :title text :employer employer
                        :source source :source-url hostedUrl
                        :source-updated-at createdAt :iso3 iso3
                        :scan-text (str text " " descriptionPlain)})))

(defn collect
  "Normalizes every job in `jobs` per `opts`, dropping jurisdiction-
  unmatched jobs. Returns {:postings [...] :skipped-count n} -- the
  skip count is reported, never silently dropped (coverage reported
  honestly, same discipline `jobsearchops.facts`'s own ns docstring
  commits to).

  `opts` is {:platform :greenhouse | :lever (default :greenhouse)
             :source \"employer-direct\" | ...
             :employer \"Company Name\"}   ; :lever only"
  [jobs {:keys [platform source employer] :or {platform :greenhouse}}]
  (let [normalize (case platform
                     :greenhouse #(job->posting % source)
                     :lever #(lever-job->posting % employer source))
        normalized (keep normalize jobs)]
    {:postings (vec normalized)
     :skipped-count (- (count jobs) (count normalized))}))

(def connector-id-prefixes
  "Every id prefix a `collect` normalizer stamps on. `close-vanished`
  only ever manages postings under one of these -- an operator's own
  hand-authored postings (postings.example.edn-style) never carry
  one, and are left untouched regardless of shape."
  ["gh-" "lv-"])

(defn- connector-managed? [{:keys [id]}]
  (boolean (some #(str/starts-with? id %) connector-id-prefixes)))

(defn close-vanished
  "Given `previous-postings` (this connector's own prior output, i.e.
  every posting in the old `web/postings.edn` carrying one of
  `connector-id-prefixes`) and `fresh-postings` (this run's `collect`
  output, any platform), returns fresh-postings plus every previously-
  collected posting no longer present in this run, carried forward
  with `:source-vacancy-closed? true`.

  Both platforms' APIs only ever return currently-OPEN postings --
  closed/filled roles simply disappear from the response. So a
  posting's disappearance from THIS run IS the source's own closure
  signal (not an assumption): the actor's real stale-vacancy HARD-hold
  (jobsearchops.governor) then keeps/removes it from the live index on
  its own, exactly the flow that already handles the demo's own
  posting-1 delisting story."
  [previous-postings fresh-postings]
  (let [fresh-ids (set (map :id fresh-postings))
        vanished (->> previous-postings
                      (filter connector-managed?)
                      (remove #(fresh-ids (:id %)))
                      (map #(assoc % :source-vacancy-closed? true)))
        kept-non-connector (remove connector-managed? previous-postings)]
    (vec (concat kept-non-connector vanished fresh-postings))))
