(ns jobsearchops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean posting through
  ingest -> jurisdiction assessment -> publication (escalate/approve/
  commit) -> delisting (escalate/approve/commit), then a SEPARATE
  clean source-consent-required posting through ingest -> assessment ->
  publication (demonstrating the conditional source-consent check
  passing cleanly), then shows HARD-hold scenarios: a jurisdiction
  with no spec-basis, a displayed-compensation mismatch, a
  discriminatory ad content, an unverified source consent on a
  consent-requiring posting, a stale (source-closed) vacancy, a double
  publication, and a double delisting.

  Like `employmentops`/7810's, `retailops`/4711's and `practiceops`/
  7110's own new checks, this actor's checks (`stale-vacancy`,
  `ad-content-discriminatory?`, `displayed-compensation-mismatch`,
  `source-consent-unverified?`) are evaluated directly at
  `:posting/publish` time rather than via a separate screening op -- a
  real publication decision validates vacancy currency, pay accuracy,
  ad content and source consent at the point of the act itself. Each
  check is still exercised directly and independently below, one
  posting per HARD-hold scenario, following the SAME 'exercise the
  failure mode directly, never only via a happy-path actuation'
  discipline `parksafety`'s ADR-2607071922 Decision 5 and every
  sibling since establish."
  (:require [langgraph.graph :as g]
            [jobsearchops.store :as store]
            [jobsearchops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :portal-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== posting/ingest posting-1 (JPN, clean, no source consent needed) ==")
    (println (exec-op actor "t1" {:op :posting/ingest :subject "posting-1"
                                  :patch {:id "posting-1" :title "Warehouse Associate"}} operator))

    (println "== jurisdiction/assess posting-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :jurisdiction/assess :subject "posting-1"} operator))
    (println (approve! actor "t2"))

    (println "== posting/publish posting-1 (always escalates -- actuation/publish-posting) ==")
    (let [r (exec-op actor "t3" {:op :posting/publish :subject "posting-1"} operator)]
      (println r)
      (println "-- human portal operator approves --")
      (println (approve! actor "t3")))

    (println "== posting/delist posting-1 (always escalates -- actuation/delist-posting) ==")
    (let [r (exec-op actor "t4" {:op :posting/delist :subject "posting-1"} operator)]
      (println r)
      (println "-- human portal operator approves --")
      (println (approve! actor "t4")))

    (println "== posting/ingest posting-6 (JPN, clean, source consent required and verified) ==")
    (println (exec-op actor "t5" {:op :posting/ingest :subject "posting-6"
                                  :patch {:id "posting-6" :title "Line Cook"}} operator))

    (println "== jurisdiction/assess posting-6 (escalates -- human approves) ==")
    (println (exec-op actor "t6" {:op :jurisdiction/assess :subject "posting-6"} operator))
    (println (approve! actor "t6"))

    (println "== posting/publish posting-6 (source consent required, verified -- escalates -- human approves) ==")
    (println (exec-op actor "t7" {:op :posting/publish :subject "posting-6"} operator))
    (println (approve! actor "t7"))

    (println "== jurisdiction/assess posting-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :jurisdiction/assess :subject "posting-2" :no-spec? true} operator))

    (println "== jurisdiction/assess posting-3 (escalates -- human approves; sets up the pay-mismatch test) ==")
    (println (exec-op actor "t9" {:op :jurisdiction/assess :subject "posting-3"} operator))
    (println (approve! actor "t9"))

    (println "== posting/publish posting-3 (displayed 300000.0 vs recompute 264000.0 -> HARD hold) ==")
    (println (exec-op actor "t10" {:op :posting/publish :subject "posting-3"} operator))

    (println "== jurisdiction/assess posting-4 (escalates -- human approves; sets up the discriminatory-ad test) ==")
    (println (exec-op actor "t11" {:op :jurisdiction/assess :subject "posting-4"} operator))
    (println (approve! actor "t11"))

    (println "== posting/publish posting-4 (discriminatory ad content -> HARD hold) ==")
    (println (exec-op actor "t12" {:op :posting/publish :subject "posting-4"} operator))

    (println "== jurisdiction/assess posting-5 (escalates -- human approves; sets up the source-consent test) ==")
    (println (exec-op actor "t13" {:op :jurisdiction/assess :subject "posting-5"} operator))
    (println (approve! actor "t13"))

    (println "== posting/publish posting-5 (source consent required, unverified -> HARD hold) ==")
    (println (exec-op actor "t14" {:op :posting/publish :subject "posting-5"} operator))

    (println "== jurisdiction/assess posting-7 (escalates -- human approves; sets up the stale-vacancy test) ==")
    (println (exec-op actor "t15" {:op :jurisdiction/assess :subject "posting-7"} operator))
    (println (approve! actor "t15"))

    (println "== posting/publish posting-7 (source vacancy already closed -> HARD hold) ==")
    (println (exec-op actor "t16" {:op :posting/publish :subject "posting-7"} operator))

    (println "== posting/publish posting-1 AGAIN (double-publication -> HARD hold) ==")
    (println (exec-op actor "t17" {:op :posting/publish :subject "posting-1"} operator))

    (println "== posting/delist posting-1 AGAIN (double-delisting -> HARD hold) ==")
    (println (exec-op actor "t18" {:op :posting/delist :subject "posting-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft publication records ==")
    (doseq [r (store/publication-history db)] (println r))

    (println "== draft delisting records ==")
    (doseq [r (store/delisting-history db)] (println r))))
