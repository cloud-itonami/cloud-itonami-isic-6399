(ns jobsearchops.screen-test
  "The screener is decision SUPPORT, never a gate -- these tests pin
  that contract: hits carry a statutory basis for the human to check,
  an empty result is not clearance, and the governor namespace has no
  dependency on this one (verified by the absence of any require -- see
  jobsearchops.governor's ns form)."
  (:require [clojure.test :refer [deftest is]]
            [jobsearchops.screen :as screen]))

(deftest age-restrictions-are-flagged-with-basis
  (let [hits (screen/screen-text "エンジニア募集(35歳未満)")]
    (is (seq hits))
    (is (some #(= :age (:category %)) hits))
    (is (every? (comp string? :basis) hits) "every hit carries the statute to check")))

(deftest gender-restrictions-are-flagged
  (is (some #(= :gender (:category %)) (screen/screen-text "ホール係 女性限定")))
  (is (some #(= :gender (:category %)) (screen/screen-text "Waitress wanted for cafe"))))

(deftest nationality-restrictions-are-flagged
  (is (some #(= :other (:category %)) (screen/screen-text "配送スタッフ(日本人のみ)"))))

(deftest clean-text-yields-empty-but-not-clearance
  (let [r (screen/screen-posting {:id "p" :title "Warehouse Associate" :employer "Kita Logistics"})]
    (is (empty? (:suspects r)))
    (is (re-find #"クリアランスではない" (:attestation-hint r)) "empty result is explicitly not clearance")))

(deftest posting-screen-covers-displayed-fields
  (let [r (screen/screen-posting {:id "x" :title "Line Cook" :employer "男性限定食堂"})]
    (is (seq (:suspects r)))
    (is (re-find #"HARD hold" (:attestation-hint r)) "hint routes the human to the attestation that gates")))

(deftest case-insensitive-for-latin-phrases
  (is (seq (screen/screen-text "MALES ONLY apply"))))
