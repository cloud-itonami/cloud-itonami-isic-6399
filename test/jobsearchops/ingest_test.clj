(ns jobsearchops.ingest-test
  (:require [clojure.test :refer [deftest is]]
            [jobsearchops.ingest :as ingest]))

;; Fixture shape mirrors real Greenhouse Job Board API responses
;; (verified live against boards-api.greenhouse.io/v1/boards/carvana/jobs
;; on 2026-07-24 -- job id/title/location/content below are a trimmed
;; real example, not invented).
(def carvana-driver-job
  {:id 7842664
   :title "4 day work week! - Customer Service Delivery Driver"
   :company_name "Carvana"
   :location {:name "Wichita, KS"}
   :content "<p>Pay Range: $17-$19 hourly. No women only need apply.</p>"
   :absolute_url "https://carvana.com/jobs/7842664"
   :updated_at "2026-07-20T10:00:00-04:00"})

(def figma-tokyo-job
  {:id 999
   :title "Solutions Engineer"
   :company_name "Figma"
   :location {:name "Tokyo, Japan"}
   :content "<p>Join our Tokyo team.</p>"
   :absolute_url "https://job-boards.greenhouse.io/figma/jobs/999"
   :updated_at "2026-07-21T00:00:00Z"})

;; Real fixture, same source as carvana-driver-job but with no wage
;; disclosure in the content -- the honest "can't verify" case.
(def chargepoint-salaried-job
  {:id 8594118002
   :title "Account Executive - Northern California"
   :company_name "ChargePoint"
   :location {:name "CA - Remote"}
   :content "<p>OTE: $112,500 to $170,000 annually, plus equity.</p>"
   :absolute_url "https://job-boards.greenhouse.io/chargepoint/jobs/8594118002"
   :updated_at "2026-07-10T16:52:03-04:00"})

(def unmatched-jurisdiction-job
  {:id 1 :title "Remote role" :company_name "Acme"
   :location {:name "Remote - Mars"} :content "" :absolute_url "https://x" :updated_at "2026-01-01"})

;; ----------------------------- detect-jurisdiction -----------------------------

(deftest detects-usa-from-state-style-location
  (is (= "USA" (ingest/detect-jurisdiction "Wichita, KS"))))

(deftest detects-japan-from-location
  (is (= "JPN" (ingest/detect-jurisdiction "Tokyo, Japan"))))

(deftest detects-germany-over-generic-remote
  (is (= "DEU" (ingest/detect-jurisdiction "Berlin, DE; Hamburg, DE"))))

(deftest returns-nil-for-unmatched-jurisdiction
  (is (nil? (ingest/detect-jurisdiction "Remote - Mars"))))

;; ----------------------------- extract-hourly-range -----------------------------
;; Real disclosed-text fixtures verified live against
;; boards-api.greenhouse.io/v1/boards/carvana/jobs on 2026-07-24.

(deftest extracts-a-real-disclosed-hourly-range
  (is (= [17.0 19.0] (ingest/extract-hourly-range "Pay Range: $17-$19 hourly")))
  (is (= [20.0 22.0] (ingest/extract-hourly-range "$20-$22 hour")))
  (is (= [18.5 19.0] (ingest/extract-hourly-range "$18.50-$19 hour"))))

(deftest does-not-extract-an-annual-salary-range
  (is (nil? (ingest/extract-hourly-range "$112,500 to $170,000 annually"))))

(deftest does-not-extract-an-unrelated-dollar-figure
  (is (nil? (ingest/extract-hourly-range "$6000 Bonus for 2nd Shift"))))

(deftest returns-nil-when-no-range-present
  (is (nil? (ingest/extract-hourly-range "Competitive, Performance-Based Compensation"))))

;; ----------------------------- job->posting -----------------------------

(deftest normalizes-a-real-job-into-the-posting-shape
  (let [p (ingest/job->posting carvana-driver-job "employer-direct")]
    (is (= "gh-7842664" (:id p)))
    (is (= "4 day work week! - Customer Service Delivery Driver" (:title p)))
    (is (= "Carvana" (:employer p)))
    (is (= "USA" (:jurisdiction p)))
    (is (= :ingested (:status p)))
    (is (false? (:published? p)))
    (is (false? (:source-vacancy-closed? p)))))

(deftest verifies-compensation-when-source-discloses-an-unambiguous-hourly-range
  (let [p (ingest/job->posting carvana-driver-job "employer-direct")]
    (is (true? (:compensation-verified? p)))
    (is (= 17.0 (:source-compensation-min p)))
    (is (= 19.0 (:source-compensation-max p)))
    ;; displayed verbatim as the source's own range -- see job->posting
    (is (= (:source-compensation-min p) (:displayed-compensation-min p)))
    (is (= (:source-compensation-max p) (:displayed-compensation-max p)))
    (is (not (contains? p :source-hourly-wage)))
    (is (not (contains? p :source-monthly-hours)))
    (is (not (contains? p :displayed-compensation)))))

(deftest never-populates-compensation-fields-when-source-discloses-no-usable-wage
  (let [p (ingest/job->posting chargepoint-salaried-job "employer-direct")]
    (is (false? (:compensation-verified? p)))
    (is (not (contains? p :source-compensation-min)))
    (is (not (contains? p :source-compensation-max)))
    (is (not (contains? p :displayed-compensation-min)))
    (is (not (contains? p :displayed-compensation-max)))))

(deftest flags-obvious-discriminatory-phrasing
  (let [p (ingest/job->posting carvana-driver-job "employer-direct")]
    (is (true? (:ad-content-discriminatory? p)))))

(deftest does-not-flag-clean-content
  (let [p (ingest/job->posting figma-tokyo-job "employer-direct")]
    (is (false? (:ad-content-discriminatory? p)))))

(deftest skips-jobs-whose-location-matches-no-covered-jurisdiction
  (is (nil? (ingest/job->posting unmatched-jurisdiction-job "employer-direct"))))

;; ----------------------------- collect -----------------------------

(deftest collect-reports-skip-count-honestly
  (let [{:keys [postings skipped-count]}
        (ingest/collect [carvana-driver-job figma-tokyo-job unmatched-jurisdiction-job]
                         "employer-direct")]
    (is (= 2 (count postings)))
    (is (= 1 skipped-count))))

;; ----------------------------- close-vanished -----------------------------

(deftest carries-forward-vanished-postings-as-closed
  (let [previous [{:id "gh-1" :title "Old role" :source-vacancy-closed? false}
                  {:id "own-1" :title "operator's own manual posting"}]
        fresh [{:id "gh-2" :title "New role"}]
        merged (ingest/close-vanished previous fresh)]
    (is (= 3 (count merged)))
    (is (some #(and (= "gh-1" (:id %)) (true? (:source-vacancy-closed? %))) merged))
    (is (some #(= "own-1" (:id %)) merged))
    (is (some #(= "gh-2" (:id %)) merged))))

(deftest keeps-still-present-postings-unclosed
  (let [previous [{:id "gh-1" :title "Still open" :source-vacancy-closed? false}]
        fresh [{:id "gh-1" :title "Still open" :source-vacancy-closed? false}]
        merged (ingest/close-vanished previous fresh)]
    (is (= 1 (count merged)))
    (is (false? (:source-vacancy-closed? (first merged))))))
