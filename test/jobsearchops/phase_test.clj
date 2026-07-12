(ns jobsearchops.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:posting/publish`/`:posting/delist` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [jobsearchops.phase :as phase]))

(deftest posting-publish-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real posting publication"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :posting/publish))
          (str "phase " n " must not auto-commit :posting/publish")))))

(deftest posting-delist-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real posting delisting"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :posting/delist))
          (str "phase " n " must not auto-commit :posting/delist")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-public-facing-risk-ops
  (testing ":posting/ingest carries no direct public-facing risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:posting/ingest} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :posting/ingest} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :posting/publish} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :posting/delist} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :posting/ingest} :commit)))))
