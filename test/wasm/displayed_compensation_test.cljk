(ns wasm.displayed-compensation-test
  "Hosts wasm/displayed_compensation.wasm (compiled from wasm/
  displayed_compensation.kotoba, see wasm/README.md) via kototama.tender
  -- proves jobsearchops.governor's displayed-compensation-mismatch check
  (`displayed-compensation-mismatch-violations` in src/jobsearchops/
  governor.cljc, backed by jobsearchops.registry/displayed-compensation-
  matches-claim?) runs as a real WASM guest, not just as JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the three real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/displayed_compensation.kotoba's ns-adjacent header comment for the
  offset layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/displayed_compensation.wasm"))))

(defn- run-displayed-compensation-matches?
  [source-hourly-wage source-monthly-hours displayed-compensation]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 source-hourly-wage)
    (.writeI32 memory 4 source-monthly-hours)
    (.writeI32 memory 8 displayed-compensation)
    (tender/call-main instance)))

(deftest displayed-compensation-wasm-approves-exact-match
  (testing "displayed-compensation equals source-hourly-wage x source-monthly-hours -> matches (no violation)"
    ;; jobsearchops.store's own posting-1 fixture: 1500 yen/hr x 160 hr = 240000
    (is (= 1 (run-displayed-compensation-matches? 1500 160 240000)))))

(deftest displayed-compensation-wasm-rejects-mismatch
  (testing "displayed-compensation does not equal the recompute -> mismatch (HARD violation)"
    ;; jobsearchops.registry_test's own mismatch fixture: 1600 x 165 = 264000, claimed 300000
    (is (= 0 (run-displayed-compensation-matches? 1600 165 300000)))))

(deftest displayed-compensation-wasm-handles-zero-hours
  (testing "zero source-monthly-hours -> recomputed compensation is 0 (not a crash); matches a 0 claim, rejects a nonzero claim"
    (is (= 1 (run-displayed-compensation-matches? 1500 0 0)))
    (is (= 0 (run-displayed-compensation-matches? 1500 0 1)))))

(deftest displayed-compensation-wasm-approves-boundary-off-by-one-rejects
  (testing "the recompute is exact-equality, not a tolerance band -- one yen off is still a mismatch"
    (is (= 1 (run-displayed-compensation-matches? 1300 150 195000)))
    (is (= 0 (run-displayed-compensation-matches? 1300 150 195001)))))
