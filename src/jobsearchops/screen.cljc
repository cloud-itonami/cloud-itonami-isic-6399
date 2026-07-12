(ns jobsearchops.screen
  "Decision SUPPORT for the human attestation of
  `:ad-content-discriminatory?` -- NEVER a gate.

  The governor's ad-content check reads an operator-attested boolean;
  this namespace helps the operator make that attestation by surfacing
  phrases in a posting's text that commonly indicate a legally
  problematic restriction, each tagged with the statutory basis the
  operator should check against. It is deliberately NOT wired into
  `jobsearchops.governor` and must never be: keyword screening over
  free text has real false positives (e.g. 「男性用品の販売経験」 is
  not a gender restriction; '35 years of history' is not an age cap),
  so an automated hold here would suppress lawful postings -- the
  screen proposes, the HUMAN decides, exactly the actor pattern's own
  division of labor applied one level down. (The same reasoning is why
  the live jobs lane's discrimination screen remains an owner-gated
  proposal -- gftdcojp/cloud-itonami ADR-0023 §2.)

  The phrase lists are deliberately SHORT and high-precision starters,
  not a compliance corpus: extend per deployment, never treat an empty
  result as clearance. Categories follow the ad-specific statutes the
  facts catalog already cites:
    :age    -- 労働施策総合推進法9条 (募集・採用における年齢制限の禁止,
               例外は雇用対策法施行規則1条の3), US ADEA §4(e)
    :gender -- 男女雇用機会均等法5条, US Title VII §704(b),
               UK Equality Act 2010, DEU AGG §11
    :other  -- 国籍/住居等: 職業安定法3条(均等待遇), 労働施策総合推進法,
               各法域の一般差別禁止規定"
  (:require [clojure.string :as str]))

(def suspect-phrases
  "category -> {:basis <statute string> :phrases [..]}. High-precision
  starters only -- see ns docstring."
  {:age    {:basis "労働施策総合推進法9条 / ADEA §4(e)"
            :phrases ["歳まで" "歳以下" "若手のみ" "35歳未満" "年齢制限あり"
                      "under 35" "young workforce only" "recent graduates only"]}
   :gender {:basis "男女雇用機会均等法5条 / Title VII §704(b) / Equality Act 2010 / AGG §11"
            :phrases ["男性限定" "女性限定" "男性のみ" "女性のみ" "主婦歓迎(男性不可"
                      "males only" "females only" "waitress wanted" "salesman wanted"]}
   :other  {:basis "職業安定法3条(均等待遇) ほか一般差別禁止規定"
            :phrases ["日本人のみ" "外国人不可" "国籍限定"
                      "no foreigners" "citizens only"]}})

(defn screen-text
  "Scan free text for suspect phrases. Returns a vector of
  {:category kw :phrase str :basis str} hits (possibly empty).
  An empty result is NOT clearance -- it only means none of the
  starter phrases matched."
  [text]
  (let [t (str/lower-case (str text))]
    (vec
     (for [[category {:keys [basis phrases]}] suspect-phrases
           phrase phrases
           :when (str/includes? t (str/lower-case phrase))]
       {:category category :phrase phrase :basis basis}))))

(defn screen-posting
  "Screen the posting fields a job ad actually displays (title +
  employer are what reaches the public; extend per deployment if your
  postings carry a description field). Returns
  {:posting-id .. :suspects [..] :attestation-hint str}."
  [{:keys [id title employer description]}]
  (let [suspects (screen-text (str title " " employer " " description))]
    {:posting-id id
     :suspects suspects
     :attestation-hint
     (if (seq suspects)
       "疑義あり: 各 :basis の条文に照らして人間が判断し、該当するなら :ad-content-discriminatory? true で attest する(ガバナーが掲載を HARD hold する)"
       "スクリーナー一致なし — ただし空の結果はクリアランスではない(語句リストは高精度スターターに過ぎない)")}))
