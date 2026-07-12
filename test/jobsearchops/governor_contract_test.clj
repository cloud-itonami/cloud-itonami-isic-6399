(ns jobsearchops.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  Trust Controls ('a posting the source has closed is never published;
  advertised pay always equals the source's own record; publication
  outside source consent is blocked; discriminatory advertisements are
  never published') implemented faithfully. The single invariant under
  test:

    JobSearch-LLM never publishes or delists a posting the Job Search
    Portal Governor would reject, `:posting/publish`/`:posting/delist`
    NEVER auto-commit at any phase, `:posting/ingest` (no direct
    public-facing risk) MAY auto-commit when clean, and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [jobsearchops.store :as store]
            [jobsearchops.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :portal-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- publish!
  "Walks `subject` through publish -> approve, leaving :published? true.
  Assumes `assess!` already ran for this subject."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-publish") {:op :posting/publish :subject subject} operator)
  (approve! actor (str tid-prefix "-publish")))

(deftest clean-ingest-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :posting/ingest :subject "posting-1"
                   :patch {:id "posting-1" :title "Warehouse Associate"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Warehouse Associate" (:title (store/posting db "posting-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "posting-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "posting-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "posting-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "posting-1")) "no assessment written"))))

(deftest publish-without-assessment-is-held
  (testing "posting/publish before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :posting/publish :subject "posting-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest stale-vacancy-is-held-and-unoverridable
  (testing "a posting whose source vacancy is already closed -> HOLD, and never reaches request-approval -- the FLAGSHIP domain-unique check this vertical adds, grounded in Japan's own 職業安定法5条の4 的確表示義務 (the 令和4年 provision written for 募集情報等提供事業者), the US's FTC Act §5, the UK's Conduct Regulations 2003 reg. 27 (genuine vacancies) and Germany's UWG §5"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "posting-7")
          res (exec-op actor "t5" {:op :posting/publish :subject "posting-7"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:stale-vacancy} (-> (store/ledger db) last :basis)))
      (is (empty? (store/publication-history db))))))

(deftest ad-content-discriminatory-is-held-and-unoverridable
  (testing "discriminatory ad content -> HOLD, and never reaches request-approval -- an honest reapplication of 7810's matching-basis-discriminatory discipline to advertisement content, under the ad-specific provisions (男女雇用機会均等法5条・労働施策総合推進法9条, Title VII §704(b), ADEA §4(e), Equality Act 2010, AGG §11)"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "posting-4")
          res (exec-op actor "t6" {:op :posting/publish :subject "posting-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:ad-content-discriminatory} (-> (store/ledger db) last :basis)))
      (is (empty? (store/publication-history db))))))

(deftest displayed-compensation-mismatch-is-held
  (testing "a displayed pay that doesn't equal source-hourly-wage x source-monthly-hours -> HOLD (the ground-truth-recompute discipline every sibling's cost/total-matching check establishes)"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "posting-3")
          res (exec-op actor "t7" {:op :posting/publish :subject "posting-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:displayed-compensation-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/publication-history db))))))

(deftest source-consent-unverified-is-held-and-unoverridable
  (testing "an unverified source consent on a consent-requiring posting -> HOLD, and never reaches request-approval -- the conditional variant, a new member of the fleet's consent-check family, conditional on the posting's own :requires-source-consent? ground truth"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "posting-5")
          res (exec-op actor "t8" {:op :posting/publish :subject "posting-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:source-consent-unverified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/publication-history db))))))

(deftest publish-is-clean-when-no-consent-required
  (testing "the source-consent check is CONDITIONAL: a posting submitted directly by the employer has no consent requirement at all"
    (let [[_db actor] (fresh)
          _ (assess! actor "t8bpre" "posting-1")
          res (exec-op actor "t8b" {:op :posting/publish :subject "posting-1"} operator)]
      (is (= :interrupted (:status res)) "clean publication still escalates for human sign-off, but is NOT a HARD hold"))))

(deftest publish-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, pay-matching, no-consent-required publication still ALWAYS interrupts for human approval -- actuation/publish-posting is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "posting-1")
          r1 (exec-op actor "t9" {:op :posting/publish :subject "posting-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, publication record drafted"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:published? (store/posting db "posting-1"))))
          (is (= 1 (count (store/publication-history db))) "one draft publication record"))))))

(deftest delist-always-escalates-then-human-decides
  (testing "a clean delisting of a published posting still ALWAYS interrupts for human approval -- actuation/delist-posting is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "posting-1")
          _ (publish! actor "t10pre" "posting-1")
          r1 (exec-op actor "t10" {:op :posting/delist :subject "posting-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, delisting record drafted"
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:delisted? (store/posting db "posting-1"))))
          (is (= 1 (count (store/delisting-history db))) "one draft delisting record"))))))

(deftest posting-double-publication-is-held
  (testing "publishing the same posting record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t11pre" "posting-1")
          _ (publish! actor "t11pre" "posting-1")
          res (exec-op actor "t11" {:op :posting/publish :subject "posting-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-published} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/publication-history db))) "still only the one earlier publication"))))

(deftest posting-double-delisting-is-held
  (testing "delisting the same posting twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t12pre" "posting-1")
          _ (publish! actor "t12pre" "posting-1")
          _ (exec-op actor "t12a" {:op :posting/delist :subject "posting-1"} operator)
          _ (approve! actor "t12a")
          res (exec-op actor "t12" {:op :posting/delist :subject "posting-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-delisted} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/delisting-history db))) "still only the one earlier delisting"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :posting/ingest :subject "posting-1"
                          :patch {:id "posting-1" :title "Warehouse Associate"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "posting-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

(deftest correction-always-escalates-then-human-decides
  (testing "訂正 (職業安定法5条の4): correcting a LIVE posting escalates like any actuation; approval stamps a correction record"
    (let [[db actor] (fresh)
          _ (assess! actor "t13pre" "posting-1")
          _ (publish! actor "t13pre" "posting-1")
          ;; the source record changed; ingest normalizes the new ground truth
          _ (exec-op actor "t13a" {:op :posting/ingest :subject "posting-1"
                                   :patch {:id "posting-1" :source-hourly-wage 1550
                                           :displayed-compensation 248000.0}} operator)
          r1 (exec-op actor "t13" {:op :posting/correct :subject "posting-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (let [r2 (approve! actor "t13")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "JPN-COR-000000" (:correction-number (store/posting db "posting-1"))))
        (is (= 1 (count (store/correction-history db)))))))) 

(deftest correction-of-unpublished-posting-is-held
  (testing "訂正対象は公開中の求人票のみ — an unpublished draft is corrected by plain ingest"
    (let [[db actor] (fresh)
          _ (assess! actor "t14pre" "posting-1")
          res (exec-op actor "t14" {:op :posting/correct :subject "posting-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:posting-not-live} (-> (store/ledger db) last :basis)))
      (is (empty? (store/correction-history db))))))

(deftest correction-may-not-introduce-a-pay-mismatch
  (testing "a correction passes the SAME content gates as a publication: mismatched displayed pay -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t15pre" "posting-1")
          _ (publish! actor "t15pre" "posting-1")
          ;; source truth moved but the displayed figure did not
          _ (exec-op actor "t15a" {:op :posting/ingest :subject "posting-1"
                                   :patch {:id "posting-1" :source-hourly-wage 1550}} operator)
          res (exec-op actor "t15" {:op :posting/correct :subject "posting-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:displayed-compensation-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/correction-history db))))))
