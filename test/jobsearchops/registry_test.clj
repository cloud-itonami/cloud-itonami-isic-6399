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
