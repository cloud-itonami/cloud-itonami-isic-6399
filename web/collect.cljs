#!/usr/bin/env nbb
;; Fetches real, currently-open job postings from the public Greenhouse
;; Job Board API for every company listed in web/sources.edn,
;; normalizes them through jobsearchops.ingest (portable, unit-tested
;; in test/jobsearchops/ingest_test.clj), diffs against the previous
;; web/postings.edn to detect source-side closures, and writes the
;; merged result back to web/postings.edn -- the same file
;; web/generate.cljs already knows how to walk through the REAL actor
;; (:jurisdiction/assess, then :posting/publish for postings with
;; :compensation-verified? true).
;;
;; No auth, no scraping, no bot-detection evasion: Greenhouse's Job
;; Board API is documented and published by Greenhouse specifically
;; for this kind of external aggregation
;; (https://developers.greenhouse.io/job-board.html); every listed
;; company chose to expose its postings this way.
;;
;; Compensation fields are deliberately NOT populated (see
;; jobsearchops.ingest's ns docstring) -- wage-judgment is an
;; explicitly deferred follow-up, not solved here. Every posting this
;; script produces carries :compensation-verified? false, so
;; generate.cljs assesses it through the real actor but does not
;; attempt to publish it.
;;
;; Run (from this web/ directory, inside the monorepo checkout):
;;   ../../../../node_modules/.bin/nbb --classpath "../src" collect.cljs
(ns collect
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jobsearchops.ingest :as ingest]
            ["fs" :as fs]))

(def sources-path "sources.edn")
(def postings-path "postings.edn")

(def sources (edn/read-string (fs/readFileSync sources-path "utf8")))

(defn- board-url [board]
  (str "https://boards-api.greenhouse.io/v1/boards/" board "/jobs?content=true"))

(defn fetch-board-jobs [board]
  (-> (js/fetch (board-url board))
      (.then (fn [^js r]
               (if (.-ok r)
                 (.json r)
                 (throw (js/Error. (str "HTTP " (.-status r) " fetching board " board))))))
      (.then #(:jobs (js->clj % :keywordize-keys true)))))

(defn- collect-source [acc-promise {:keys [board source]}]
  (.then acc-promise
         (fn [acc]
           (.then (fetch-board-jobs board)
                  (fn [jobs]
                    (let [{:keys [postings skipped-count]} (ingest/collect jobs source)]
                      (println "  " board "(" source ") ->" (count postings)
                               "in-scope," skipped-count "jurisdiction-skipped, out of"
                               (count jobs) "live postings on the board")
                      (into acc postings)))))))

(defn collect-all [sources]
  (reduce collect-source (js/Promise.resolve []) sources))

(defn- read-previous []
  (if (fs/existsSync postings-path)
    (edn/read-string (fs/readFileSync postings-path "utf8"))
    []))

(defn- write-postings! [postings]
  (fs/writeFileSync postings-path
                     (str "[" (str/join "\n " (map pr-str postings)) "]\n")))

(defn -main []
  (println "Collecting from" (count sources) "real source(s):"
            (str/join ", " (map :board sources)))
  (-> (collect-all sources)
      (.then
       (fn [fresh]
         (let [previous (read-previous)
               fresh-ids (set (map :id fresh))
               previously-open-gh-ids (set (map :id (remove :source-vacancy-closed? previous)))
               newly-closed (remove fresh-ids
                                     (set (map :id (filter #(str/starts-with? (:id %) "gh-") previous))))
               merged (ingest/close-vanished previous fresh)]
           (write-postings! merged)
           (println "wrote" (count merged) "posting(s) to" postings-path
                     "(" (count fresh) "fresh," (count newly-closed) "newly source-closed,"
                     (- (count previously-open-gh-ids) (count newly-closed)) "still open from prior runs)"))))
      (.catch (fn [e]
                (println "FAILED:" (.-message e))
                (js/process.exit 1)))))

(-main)
