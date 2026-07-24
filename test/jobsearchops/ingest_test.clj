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

(deftest never-populates-compensation-fields
  (let [p (ingest/job->posting carvana-driver-job "employer-direct")]
    (is (false? (:compensation-verified? p)))
    (is (not (contains? p :source-hourly-wage)))
    (is (not (contains? p :source-monthly-hours)))
    (is (not (contains? p :displayed-compensation)))))

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
