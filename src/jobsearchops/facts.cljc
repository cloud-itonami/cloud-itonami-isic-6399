(ns jobsearchops.facts
  "Per-jurisdiction job-advertising regulatory catalog -- the G2-style
  spec-basis table the Job Search Portal Governor checks every
  `:jurisdiction/assess` proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's requirements, or did
  it invent one?').

  This blueprint's own text (docs/business-model.md's own Trust
  Controls: 'a posting the source has closed is never published;
  advertised pay always equals the source's own record; publication
  outside source consent is blocked; discriminatory advertisements are
  never published') names two real, distinct regulatory concerns: the
  general job-advertising accuracy/anti-discrimination framework a
  publication must not violate (staleness, pay misstatement,
  discriminatory ad content -- independent of where the posting came
  from), and a SEPARATE source-republication consent regime
  specifically governing whether a third-party source's postings may
  be republished at all (database right / copyright / the operator's
  own feed agreements -- independent of whether the posting itself is
  accurate and lawful). Each jurisdiction entry below therefore cites
  BOTH the general job-advertising law AND a SEPARATE
  source-republication/database-right law.

  Japan is the anchor jurisdiction here: the 令和4年 (2022) 職業安定法
  amendment (5条の4 的確表示義務, 43条の2 特定募集情報等提供事業者の届出制)
  regulates 募集情報等提供事業者 -- job-posting aggregators, i.e.
  EXACTLY this business -- by name.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries. ALL FOUR seeded
  jurisdictions actually have a real source-republication/database-
  right regime, reported honestly (a full-coverage sub-citation,
  matching `employmentops`/7810's own work-authorization full coverage
  rather than `hospitalityops`/5510's own honest single-jurisdiction
  gap).")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  source/compensation/publication-record evidence set (PLUS a source-
  consent record for every seeded jurisdiction); `:legal-basis` /
  `:owner-authority` / `:provenance` are the G2 citation the governor
  requires before any `:jurisdiction/assess` proposal can commit.
  `:consent-owner-authority` / `:consent-legal-basis` /
  `:consent-provenance` are the SEPARATE source-republication citation
  the governor's `source-consent-unverified?` check is grounded in."
  {"JPN" {:name "Japan"
          :owner-authority "厚生労働省 (Ministry of Health, Labour and Welfare, MHLW)"
          :legal-basis "職業安定法5条の4 (求人等に関する情報の的確な表示) 及び 男女雇用機会均等法5条・労働施策総合推進法9条 (募集・採用における差別禁止)"
          :national-spec "職業安定法に基づく募集情報等提供事業者の業務運営 (令和4年改正: 的確表示義務・特定募集情報等提供事業者届出制)"
          :provenance "https://www.mhlw.go.jp/stf/seisakunitsuite/bunya/koyou_roudou/koyou/shokugyoushoukai/"
          :required-evidence ["求人元記録 (source record)"
                              "労働条件表示記録 (compensation-disclosure record)"
                              "掲載記録 (publication record)"
                              "転載許諾記録 (source-consent record)"]
          :consent-owner-authority "文化庁 (Agency for Cultural Affairs) / 厚生労働省"
          :consent-legal-basis "著作権法12条の2 (データベースの著作物) 及び 職業安定法43条の2 (特定募集情報等提供事業者の届出)"
          :consent-provenance "https://www.bunka.go.jp/seisaku/chosakuken/"}
   "USA" {:name "United States"
          :owner-authority "Equal Employment Opportunity Commission (EEOC) / Federal Trade Commission (FTC)"
          :legal-basis "Title VII §704(b) (42 U.S.C. §2000e-3(b), discriminatory job notices/advertisements), ADEA §4(e) (29 U.S.C. §623(e)) and FTC Act §5 (15 U.S.C. §45, deceptive practices)"
          :national-spec "EEOC prohibited practices guidance (job advertisements)"
          :provenance "https://www.eeoc.gov/prohibited-employment-policiespractices"
          :required-evidence ["Source record"
                              "Compensation-disclosure record"
                              "Publication record"
                              "Source-consent record"]
          :consent-owner-authority "U.S. Copyright Office"
          :consent-legal-basis "17 U.S.C. §103/§106 (compilations; exclusive rights in copyrighted works)"
          :consent-provenance "https://www.copyright.gov/title17/"}
   "GBR" {:name "United Kingdom"
          :owner-authority "Employment Agency Standards Inspectorate (EAS) / Equality and Human Rights Commission (EHRC)"
          :legal-basis "Conduct of Employment Agencies and Employment Businesses Regulations 2003 (SI 2003/3319) reg. 27 (advertisements must relate to genuine vacancies) and Equality Act 2010"
          :national-spec "EAS guidance on job advertising by agencies and employment businesses"
          :provenance "https://www.legislation.gov.uk/uksi/2003/3319"
          :required-evidence ["Source record"
                              "Compensation-disclosure record"
                              "Publication record"
                              "Source-consent record"]
          :consent-owner-authority "Intellectual Property Office (IPO)"
          :consent-legal-basis "Copyright and Rights in Databases Regulations 1997 (SI 1997/3032, sui generis database right)"
          :consent-provenance "https://www.legislation.gov.uk/uksi/1997/3032"}
   "DEU" {:name "Germany"
          :owner-authority "Antidiskriminierungsstelle des Bundes"
          :legal-basis "Allgemeines Gleichbehandlungsgesetz (AGG) §11 (Ausschreibung -- non-discriminatory job advertisements) und UWG §5 (irreführende geschäftliche Handlungen)"
          :national-spec "AGG Diskriminierungsverbot bei Stellenausschreibungen"
          :provenance "https://www.gesetze-im-internet.de/agg/__11.html"
          :required-evidence ["Quellenprotokoll (source record)"
                              "Vergütungsangabeprotokoll (compensation-disclosure record)"
                              "Veröffentlichungsprotokoll (publication record)"
                              "Quellenzustimmungsprotokoll (source-consent record)"]
          :consent-owner-authority "Deutsches Patent- und Markenamt (DPMA) / Justizministerium"
          :consent-legal-basis "Urheberrechtsgesetz (UrhG) §§87a–87e (Datenbankherstellerrecht, sui generis database right)"
          :consent-provenance "https://www.gesetze-im-internet.de/urhg/__87a.html"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to publish or
  delist a posting on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6399 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `jobsearchops.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn consent-spec-basis
  "The jurisdiction's source-republication/database-right requirement
  map, or nil -- nil means this jurisdiction has NO formal
  source-republication regime this catalog is aware of. In this R0
  catalog all four seeded jurisdictions actually have one, reported
  honestly (a full-coverage sub-citation, matching `employmentops`/
  7810's own work-authorization full coverage)."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    (when (:consent-owner-authority sb)
      (select-keys sb [:consent-owner-authority :consent-legal-basis :consent-provenance]))))
