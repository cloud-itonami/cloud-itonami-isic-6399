(ns jobsearchops.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-7810`'s
  `employmentops.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [jobsearchops.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/posting s "posting-1"))))
      (is (= 240000.0 (:displayed-compensation (store/posting s "posting-1"))))
      (is (false? (:ad-content-discriminatory? (store/posting s "posting-1"))))
      (is (false? (:source-vacancy-closed? (store/posting s "posting-1"))))
      (is (false? (:requires-source-consent? (store/posting s "posting-1"))))
      (is (= 300000.0 (:displayed-compensation (store/posting s "posting-3"))))
      (is (true? (:ad-content-discriminatory? (store/posting s "posting-4"))))
      (is (true? (:requires-source-consent? (store/posting s "posting-5"))))
      (is (false? (:source-consent-verified? (store/posting s "posting-5"))))
      (is (true? (:source-consent-verified? (store/posting s "posting-6"))))
      (is (true? (:source-vacancy-closed? (store/posting s "posting-7"))))
      (is (false? (:published? (store/posting s "posting-1"))))
      (is (false? (:delisted? (store/posting s "posting-1"))))
      (is (= ["posting-1" "posting-2" "posting-3" "posting-4" "posting-5" "posting-6" "posting-7"]
             (mapv :id (store/all-postings s))))
      (is (nil? (store/assessment-of s "posting-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/publication-history s)))
      (is (= [] (store/delisting-history s)))
      (is (zero? (store/next-publication-sequence s "JPN")))
      (is (zero? (store/next-delisting-sequence s "JPN")))
      (is (false? (store/posting-already-published? s "posting-1")))
      (is (false? (store/posting-already-delisted? s "posting-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :posting/upsert
                                 :value {:id "posting-1" :title "Warehouse Associate"}})
        (is (= "Warehouse Associate" (:title (store/posting s "posting-1"))))
        (is (= 240000.0 (:displayed-compensation (store/posting s "posting-1"))) "unrelated field preserved"))
      (testing "assessment payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["posting-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "posting-1"))))
      (testing "publication drafts a record and advances the publication sequence"
        (store/commit-record! s {:effect :posting/mark-published :path ["posting-1"]})
        (is (= "JPN-PUB-000000" (get (first (store/publication-history s)) "record_id")))
        (is (= "publication-draft" (get (first (store/publication-history s)) "kind")))
        (is (true? (:published? (store/posting s "posting-1"))))
        (is (= 1 (count (store/publication-history s))))
        (is (= 1 (store/next-publication-sequence s "JPN")))
        (is (true? (store/posting-already-published? s "posting-1"))))
      (testing "delisting drafts a record and advances the delisting sequence"
        (store/commit-record! s {:effect :posting/mark-delisted :path ["posting-1"]})
        (is (= "JPN-DLS-000000" (get (first (store/delisting-history s)) "record_id")))
        (is (= "delisting-draft" (get (first (store/delisting-history s)) "kind")))
        (is (true? (:delisted? (store/posting s "posting-1"))))
        (is (= 1 (count (store/delisting-history s))))
        (is (= 1 (store/next-delisting-sequence s "JPN")))
        (is (true? (store/posting-already-delisted? s "posting-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/posting s "nope")))
    (is (= [] (store/all-postings s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/publication-history s)))
    (is (= [] (store/delisting-history s)))
    (is (zero? (store/next-publication-sequence s "JPN")))
    (is (zero? (store/next-delisting-sequence s "JPN")))
    (store/with-postings s {"x" {:id "x" :title "t" :employer "e" :source "employer-direct"
                                 :source-hourly-wage 1 :source-monthly-hours 1 :displayed-compensation 1.0
                                 :ad-content-discriminatory? false :source-vacancy-closed? false
                                 :requires-source-consent? false :source-consent-verified? false
                                 :published? false :delisted? false
                                 :jurisdiction "JPN" :status :ingested}})
    (is (= "t" (:title (store/posting s "x"))))))
