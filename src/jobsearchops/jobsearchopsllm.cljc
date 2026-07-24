(ns jobsearchops.jobsearchopsllm
  "JobSearch-LLM client -- the *contained intelligence node* for the
  meta-job-search actor.

  It normalizes posting ingest from source feeds, drafts a
  per-jurisdiction job-advertising evidence checklist, drafts the
  posting-publication action, and drafts the posting-delisting action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real publication/delisting. Every output is
  censored downstream by `jobsearchops.governor` before anything
  touches the SSoT, and `:posting/publish`/`:posting/delist` proposals
  NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- shown to the human approver;
                                 ; NOT machine-scanned (the gates read
                                 ; :cites and the Store's own ground
                                 ; truth; a keyword scan here would
                                 ; false-positive on statute names like
                                 ; 男女雇用機会均等法 in clean assess
                                 ; rationales)
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/publish-posting | :actuation/delist-posting | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [jobsearchops.facts :as facts]
            [jobsearchops.registry :as registry]
            [jobsearchops.store :as store]
            [langchain.model :as model]))

(defn- normalize-ingest
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the employer, wage/hours or jurisdiction. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "求人票記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :posting/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction job-advertising evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `jobsearchops.facts` -- the Job Search Portal Governor must
  reject this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [p (store/posting db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction p))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "jobsearchops.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-publication
  "Draft the actual POSTING-PUBLICATION action -- publishing a real
  posting into the public search index. ALWAYS `:stake :actuation/
  publish-posting` -- this is a REAL-WORLD act (a real advertisement
  reaches real job seekers), never a draft the actor may auto-run.
  See README `Actuation`: no phase ever adds this op to a phase's
  `:auto` set (`jobsearchops.phase`); the governor also always
  escalates on `:actuation/publish-posting`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [p (store/posting db subject)
        pay-ok? (and p (registry/displayed-compensation-matches-claim? p))
        consent-ok? (and p (or (not (:requires-source-consent? p)) (:source-consent-verified? p)))
        clean? (and p pay-ok? consent-ok?
                    (not (:source-vacancy-closed? p))
                    (not (:ad-content-discriminatory? p)))]
    {:summary    (str subject " 掲載提案"
                      (when p (str " (" (:title p) " / " (:employer p) ")")))
     :rationale  (if p
                   (str "source-vacancy-closed?=" (:source-vacancy-closed? p)
                        " " (registry/compensation-summary p)
                        " consent-ok?=" consent-ok?)
                   "postingが見つかりません")
     :cites      (if p [subject] [])
     :effect     :posting/mark-published
     :value      {:posting-id subject}
     :stake      :actuation/publish-posting
     :confidence (if clean? 0.9 0.3)}))

(defn- propose-delisting
  "Draft the actual POSTING-DELISTING action -- removing a real
  posting from the public search index (the 的確表示義務 currency
  duty's other half; a wrongful delisting also harms a real source
  employer). ALWAYS `:stake :actuation/delist-posting` -- this is a
  REAL-WORLD act, never a draft the actor may auto-run. See README
  `Actuation`: no phase ever adds this op to a phase's `:auto` set
  (`jobsearchops.phase`); the governor also always escalates on
  `:actuation/delist-posting`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [p (store/posting db subject)]
    {:summary    (str subject " 掲載取下げ提案"
                      (when p (str " (" (:title p) " / " (:employer p) ")")))
     :rationale  (if p
                   (str "published?=" (:published? p)
                        " source-vacancy-closed?=" (:source-vacancy-closed? p))
                   "postingが見つかりません")
     :cites      (if p [subject] [])
     :effect     :posting/mark-delisted
     :value      {:posting-id subject}
     :stake      :actuation/delist-posting
     :confidence (if p 0.9 0.3)}))

(defn- propose-correction
  "Draft the actual POSTING-CORRECTION action -- updating a LIVE
  posting's public content after its source record changed
  (職業安定法5条の4: keep posted information accurate; correct on the
  求人者's request). The corrected field values are already in the
  Store (plain `:posting/ingest` normalization); this act is the
  governed step that changes what the PUBLIC sees and stamps the
  correction record. ALWAYS `:stake :actuation/correct-posting`; the
  governor re-runs the same content gates a fresh publication passes.
  See README `Actuation`."
  [db {:keys [subject]}]
  (let [p (store/posting db subject)
        live? (and p (:published? p) (not (:delisted? p)))
        pay-ok? (and p (registry/displayed-compensation-matches-claim? p))]
    {:summary    (str subject " 訂正提案"
                      (when p (str " (" (:title p) " / " (:employer p) ")")))
     :rationale  (if p
                   (str "live?=" live? " " (registry/compensation-summary p))
                   "postingが見つかりません")
     :cites      (if p [subject] [])
     :effect     :posting/mark-corrected
     :value      {:posting-id subject}
     :stake      :actuation/correct-posting
     :confidence (if (and live? pay-ok?) 0.9 0.3)}))

(defn- propose-referral
  "Draft the APPLICATION-REFERRAL record (ADR-2607131000) -- the paper
  a human agency operator carries into cloud-itonami-isic-7810's
  candidacy intake. Not an actuation (nothing public changes); the
  governor still requires a live posting and the applicant's own
  consent flag, and the phase gate routes every referral to a human
  (the carry IS the human act)."
  [db {:keys [subject applicant-ref applicant-consent?]}]
  (let [p (store/posting db subject)
        live? (and p (:published? p) (not (:delisted? p)))]
    {:summary    (str subject " への応募 referral 起票"
                      (when p (str " (" (:title p) " / " (:employer p) ")")))
     :rationale  (str "live?=" live? " consent?=" (boolean applicant-consent?)
                      " applicant-ref=" (pr-str applicant-ref) " (参照のみ、PII本体は保持しない)")
     :cites      (if p [subject] [])
     :effect     :referral/record
     :value      {:posting-id subject :applicant-ref applicant-ref}
     :stake      nil
     :confidence (if (and live? (true? applicant-consent?) (seq (str applicant-ref))) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :posting/ingest       (normalize-ingest db request)
    :jurisdiction/assess  (assess-jurisdiction db request)
    :posting/publish      (propose-publication db request)
    :posting/delist       (propose-delisting db request)
    :posting/correct      (propose-correction db request)
    :application/refer    (propose-referral db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは求人メタサーチ(募集情報等提供事業者)の掲載・取下げエージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:posting/upsert|:assessment/set|:posting/mark-published|"
       ":posting/mark-delisted|:posting/mark-corrected) "
       ":stake(:actuation/publish-posting か :actuation/delist-posting か :actuation/correct-posting か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "求人元の募集終了状況・表示賃金の正確性・転載許諾の確認状況を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:posting (store/posting st subject)}
    :posting/publish      {:posting (store/posting st subject)}
    :posting/delist       {:posting (store/posting st subject)}
    {:posting (store/posting st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Job Search Portal Governor
  escalates/holds -- an LLM hiccup can never auto-publish or
  auto-delist a posting."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :jobsearchopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
