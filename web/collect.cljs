#!/usr/bin/env nbb
;; Fetches real, currently-open job postings from two public job-board
;; APIs (Greenhouse Job Board API, Lever Postings API) for every
;; company listed in web/sources.edn, normalizes them through
;; jobsearchops.ingest (portable, unit-tested in
;; test/jobsearchops/ingest_test.clj), diffs against the previous
;; web/postings.edn to detect source-side closures, and writes the
;; merged result back to web/postings.edn -- the same file
;; web/generate.cljs already knows how to walk through the REAL actor
;; (:jurisdiction/assess, then :posting/publish for postings with
;; :compensation-verified? true).
;;
;; No auth, no scraping, no bot-detection evasion: both APIs are
;; documented and published by their vendor specifically for this kind
;; of external aggregation (see web/sources.edn's own header for the
;; docs links and the api.lever.co vs jobs.lever.co robots.txt note);
;; every listed company chose to expose its postings this way.
;;
;; Compensation fields are only populated when jobsearchops.ingest
;; found an UNAMBIGUOUS disclosed wage range in the source's own text
;; -- see that ns's docstring. A posting without one keeps
;; :compensation-verified? false, so generate.cljs assesses it through
;; the real actor but does not attempt to publish it.
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

(defmulti board-url :platform)
(defmethod board-url :greenhouse [{:keys [board]}]
  (str "https://boards-api.greenhouse.io/v1/boards/" board "/jobs?content=true"))
(defmethod board-url :lever [{:keys [board]}]
  (str "https://api.lever.co/v0/postings/" board "?mode=json"))

;; Greenhouse wraps its jobs in {"jobs" [...]}; Lever returns the
;; array directly -- both normalized to a plain seq of job maps here
;; so collect-source stays platform-agnostic past this point.
(defmulti response->jobs :platform)
(defmethod response->jobs :greenhouse [{:keys [parsed]}] (:jobs parsed))
(defmethod response->jobs :lever [{:keys [parsed]}] parsed)

(defn fetch-board-jobs [{:keys [board] :as source-cfg}]
  (-> (js/fetch (board-url source-cfg))
      (.then (fn [^js r]
               (if (.-ok r)
                 (.json r)
                 (throw (js/Error. (str "HTTP " (.-status r) " fetching board " board))))))
      (.then #(response->jobs (assoc source-cfg :parsed (js->clj % :keywordize-keys true))))))

(defn- collect-source [acc-promise {:keys [board platform employer source] :as source-cfg}]
  (.then acc-promise
         (fn [acc]
           (.then (fetch-board-jobs source-cfg)
                  (fn [jobs]
                    (let [{:keys [postings skipped-count]}
                          (ingest/collect jobs {:platform platform :employer employer :source source})]
                      (println "  " (name platform) "/" board "(" source ") ->" (count postings)
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

(defn- connector-managed? [{:keys [id]}]
  (boolean (some #(str/starts-with? id %) ingest/connector-id-prefixes)))

(defn -main []
  (println "Collecting from" (count sources) "real source(s):"
            (str/join ", " (map #(str (name (:platform %)) "/" (:board %)) sources)))
  (-> (collect-all sources)
      (.then
       (fn [fresh]
         (let [previous (read-previous)
               fresh-ids (set (map :id fresh))
               previously-open-ids (set (map :id (remove :source-vacancy-closed? previous)))
               newly-closed (remove fresh-ids
                                     (set (map :id (filter connector-managed? previous))))
               merged (ingest/close-vanished previous fresh)]
           (write-postings! merged)
           (println "wrote" (count merged) "posting(s) to" postings-path
                     "(" (count fresh) "fresh," (count newly-closed) "newly source-closed,"
                     (- (count previously-open-ids) (count newly-closed)) "still open from prior runs)"))))
      (.catch (fn [e]
                (println "FAILED:" (.-message e))
                (js/process.exit 1)))))

(-main)
