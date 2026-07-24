(ns jobsearchops.registry-test
  (:require [clojure.test :refer [deftest is]]
            [jobsearchops.registry :as r]))

;; ----------------------------- displayed-compensation-matches-claim? -----------------------------

(deftest matches-when-claim-equals-recompute
  (is (r/displayed-compensation-matches-claim?
       {:source-hourly-wage 1500 :source-monthly-hours 160 :displayed-compensation 240000.0})))

(deftest mismatches-when-claim-differs-from-recompute
  (is (not (r/displayed-compensation-matches-claim?
            {:source-hourly-wage 1600 :source-monthly-hours 165 :displayed-compensation 300000.0}))))

(deftest compute-displayed-compensation-is-a-flat-wage-times-hours
  (is (= 240000.0 (r/compute-displayed-compensation {:source-hourly-wage 1500 :source-monthly-hours 160}))))

;; -------------------- displayed-compensation-matches-claim? (RANGE shape) --------------------
;; real job-board data (jobsearchops.ingest): a disclosed pay RANGE, not a
;; committed hourly-rate x monthly-hours pair.

(deftest range-shape-matches-when-displayed-equals-source-range
  (is (r/displayed-compensation-matches-claim?
       {:source-compensation-min 17.0 :source-compensation-max 19.0
        :displayed-compensation-min 17.0 :displayed-compensation-max 19.0})))

(deftest range-shape-matches-when-displayed-is-a-subset-of-source-range
  (is (r/displayed-compensation-within-source-range?
       {:source-compensation-min 17.0 :source-compensation-max 19.0
        :displayed-compensation-min 17.5 :displayed-compensation-max 18.5})))

(deftest range-shape-mismatches-when-displayed-exceeds-source-max
  (is (not (r/displayed-compensation-within-source-range?
            {:source-compensation-min 17.0 :source-compensation-max 19.0
             :displayed-compensation-min 17.0 :displayed-compensation-max 25.0}))))

(deftest range-shape-mismatches-when-displayed-undercuts-source-min
  (is (not (r/displayed-compensation-within-source-range?
            {:source-compensation-min 17.0 :source-compensation-max 19.0
             :displayed-compensation-min 10.0 :displayed-compensation-max 19.0}))))

(deftest range-shape-mismatches-when-displayed-range-missing
  (is (not (r/displayed-compensation-within-source-range?
            {:source-compensation-min 17.0 :source-compensation-max 19.0}))))

(deftest range-shape-takes-priority-over-exact-shape-when-both-present
  ;; a posting should never carry both shapes, but if it somehow does,
  ;; range-shaped? dispatch must be deterministic, not silently fall
  ;; through to the exact-shape arithmetic.
  (is (r/displayed-compensation-matches-claim?
       {:source-compensation-min 17.0 :source-compensation-max 19.0
        :displayed-compensation-min 17.0 :displayed-compensation-max 19.0
        :source-hourly-wage 999 :source-monthly-hours 999 :displayed-compensation 1.0})))

;; ----------------------------- compensation-summary -----------------------------

(deftest compensation-summary-describes-exact-shape
  (is (= "displayed=240000.0 independent-recompute=240000.0"
         (r/compensation-summary {:source-hourly-wage 1500 :source-monthly-hours 160
                                   :displayed-compensation 240000.0}))))

(deftest compensation-summary-describes-range-shape
  (is (= "displayed=[17.0,19.0] source-range=[17.0,19.0]"
         (r/compensation-summary {:source-compensation-min 17.0 :source-compensation-max 19.0
                                   :displayed-compensation-min 17.0 :displayed-compensation-max 19.0}))))

;; ----------------------------- register-publication -----------------------------

(deftest publication-is-a-draft-not-a-real-publication
  (let [result (r/register-publication "posting-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest publication-assigns-publication-number
  (let [result (r/register-publication "posting-1" "JPN" 7)]
    (is (= (get result "publication_number") "JPN-PUB-000007"))
    (is (= (get-in result ["record" "posting_id"]) "posting-1"))
    (is (= (get-in result ["record" "kind"]) "publication-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest publication-validation-rules
  (is (thrown? Exception (r/register-publication "" "JPN" 0)))
  (is (thrown? Exception (r/register-publication "posting-1" "" 0)))
  (is (thrown? Exception (r/register-publication "posting-1" "JPN" -1))))

;; ----------------------------- register-delisting -----------------------------

(deftest delisting-is-a-draft-not-a-real-delisting
  (let [result (r/register-delisting "posting-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest delisting-assigns-delisting-number
  (let [result (r/register-delisting "posting-1" "JPN" 7)]
    (is (= (get result "delisting_number") "JPN-DLS-000007"))
    (is (= (get-in result ["record" "posting_id"]) "posting-1"))
    (is (= (get-in result ["record" "kind"]) "delisting-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest delisting-validation-rules
  (is (thrown? Exception (r/register-delisting "" "JPN" 0)))
  (is (thrown? Exception (r/register-delisting "posting-1" "" 0)))
  (is (thrown? Exception (r/register-delisting "posting-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-publication "posting-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-publication "posting-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-PUB-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-PUB-000001" (get-in hist2 [1 "record_id"])))))

;; ----------------------------- register-correction -----------------------------

(deftest correction-is-a-draft-not-a-real-correction
  (let [result (r/register-correction "posting-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest correction-assigns-correction-number
  (let [result (r/register-correction "posting-1" "JPN" 7)]
    (is (= (get result "correction_number") "JPN-COR-000007"))
    (is (= (get-in result ["record" "posting_id"]) "posting-1"))
    (is (= (get-in result ["record" "kind"]) "correction-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest correction-validation-rules
  (is (thrown? Exception (r/register-correction "" "JPN" 0)))
  (is (thrown? Exception (r/register-correction "posting-1" "" 0)))
  (is (thrown? Exception (r/register-correction "posting-1" "JPN" -1))))

;; ----------------------------- register-referral -----------------------------

(deftest referral-assigns-referral-number-and-target
  (let [result (r/register-referral "posting-1" "JPN" "applicant-ref-042" 3)]
    (is (= (get result "referral_number") "JPN-REF-000003"))
    (is (= (get-in result ["record" "posting_id"]) "posting-1"))
    (is (= (get-in result ["record" "applicant_ref"]) "applicant-ref-042"))
    (is (= (get-in result ["record" "target"]) "cloud-itonami-isic-7810"))
    (is (= (get-in result ["record" "kind"]) "referral-draft"))))

(deftest referral-validation-rules
  (is (thrown? Exception (r/register-referral "" "JPN" "a" 0)))
  (is (thrown? Exception (r/register-referral "posting-1" "" "a" 0)))
  (is (thrown? Exception (r/register-referral "posting-1" "JPN" "" 0)))
  (is (thrown? Exception (r/register-referral "posting-1" "JPN" "a" -1))))
