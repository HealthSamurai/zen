(ns zen.schema-test
  (:require [zen.schema :as sut]
            [matcho.core :as matcho]
            [clojure.test :as t]
            [clojure.string :as str]
            [zen.core]
            [zen.package]
            [clojure.pprint :as pp]))

;; (defn set-to-string [value]
;;   (reduce
;;    (fn [acc item]
;;      (println item)
;;      (if (set? item)
;;        (set-to-string item)
;;        (if
;;         (keyword? item)
;;          (conj acc
;;                (str/replace (name item) #"hl7-fhir-r4-core." ""))
;;          (conj acc
;;                (str/replace (namespace item) #"hl7-fhir-r4-core." "")))))
;;    [] value))

(defn set-to-string [value]
  (reduce (fn [acc item]
            (cond
              (set? item) (set-to-string item)
              (keyword? item) (conj acc
                                    (str/replace (name item) #"hl7-fhir-r4-core." ""))
              :else (conj acc
                          (str/replace (namespace item) #"hl7-fhir-r4-core." ""))))
          [] value))

;; (defn get-desc [{desc :zen/desc}]
;;   (when desc
;;     (str "/* " desc " */\n")))

(defn get-desc [{desc :zen/desc}]
  (when desc
    ""))

(defn get-not-required-filed-sign [vtx]
  (when-not (contains? (get
                        (::require vtx)
                        (if (= (count (pop  (pop (:path vtx)))) 0)
                          "root"
                          (str/join "." (pop (pop (:path vtx)))))) (last (:path vtx))) "?"))

(defn exclusive-keys-child? [vtx]
  (and (> (count (::exclusive-keys vtx)) 0) (> (count (:path vtx)) 1) 
              (or (contains? (::exclusive-keys vtx) (str/join "." (pop (pop (:path vtx))))) 
                  (contains? (::exclusive-keys vtx) (str/join "." (pop (:path vtx)))))))

(sut/register-compile-key-interpreter!
 [:keys ::ts]
 (fn [_ ztx ks]
   (fn [vtx data opts]
    ;; (println "COMPILE")
    ;;  (println "path:")
    ;;  (pp/pprint (:path vtx)) 
    ;;  (println "type:")
    ;;  (pp/pprint (:type vtx)) 
    ;;  (println "schema:")
    ;;  (pp/pprint (:schema vtx))
    ;;  (println "data:")
    ;;  (pp/pprint data)
    ;;  (println "ts:")
    ;;  (pp/pprint (:zen.schema-test/ts vtx))
     (if-let [s (or (when-let [nm (:zen.fhir/type data)]
                      (str (get-desc data) "interface " (::interface-name vtx) " "))
                    (when (:exclusive-keys data) (str/join " | " (set-to-string (:exclusive-keys data))))
                    (when (exclusive-keys-child? vtx) "")
                    (when (:confirms data)
                      (str 
                       (cond 
                         (= (first (set-to-string (:confirms data))) "Reference")
                         (str "Reference<" (str/join " | " (map (fn [item] (str "'" item "'")) (set-to-string (:refers (:zen.fhir/reference data))))) ">")
                         (= (first (set-to-string (:confirms data))) "BackboneElement") ""
                         :else (str
                                (first (set-to-string (:confirms data)))))))

                    (when-let [tp (and
                                   (= (:type vtx) 'zen/symbol)
                                   (not (= (last (:path vtx)) :every))
                                   (not (:enum data))
                                   (or (= (:type data) 'zen/string)
                                       (= (:type data) 'zen/number)
                                       (= (:type data) 'zen/boolean))
                                   (:type data))]
                      (name tp))
                    (when (and (= (last (:path vtx)) :every) (= (last (:schema vtx)) 'zen/string))
                      "string; "))]
       (update vtx ::ts conj s)
       vtx))))

(zen.schema/register-schema-pre-process-hook!
 ::ts
 (fn [ztx schema]
   (fn [vtx data opts]
    ;;  (println "PRE")
    ;;  (println "path:")
    ;;  (pp/pprint (:path vtx))
    ;;  (println "type:")
    ;;  (pp/pprint (:type vtx))
    ;;  (println "schema:")
    ;;  (pp/pprint (:schema vtx))
    ;;  (println "data:")
    ;;  (println data)
    ;;  (println "ts:")
    ;;  (pp/pprint (:zen.schema-test/ts vtx))
     (let [new-vtx (cond
                     (:exclusive-keys data) 
                     (update vtx ::exclusive-keys conj {(str/join "." (:path vtx)) (:exclusive-keys data)})
                     (:require data)
                     (update vtx ::require conj {(if (= (count (:path vtx)) 0) "root" (str/join "." (:path vtx))) (:require data)})
                     :else vtx)] 
    
       (cond
         (= (last (:path new-vtx)) :zen.fhir/type) new-vtx
         (exclusive-keys-child? new-vtx) new-vtx
         (= (last (:schema new-vtx)) :enum)
         (update new-vtx ::ts conj (str  (str/join " | " (map (fn [item] (str "'" (:value item) "'")) data))))
         (= (last (:schema new-vtx)) :values)
         (update new-vtx ::ts conj (get-desc data)
                 (str (name (last (:path new-vtx)))
                      (get-not-required-filed-sign new-vtx)
                      ":"))
         (= (last (:path new-vtx)) :keys) (update new-vtx ::ts conj "{ ")
         (= (last (:schema new-vtx)) :every) (update new-vtx ::ts conj "Array<")
         :else new-vtx)))))

(zen.schema/register-schema-post-process-hook!
 ::ts
 (fn [ztx schema]
   (fn [vtx data opts]
    ;;  (println "POST")
    ;;  (println "path:")
    ;;  (pp/pprint (:path vtx))
    ;;  (println "type:")
    ;;  (pp/pprint (:type vtx))
    ;;  (println "schema:")
    ;;  (pp/pprint (:schema vtx))
    ;;  (println "data:")
    ;;  (pp/pprint data)
    ;;  (println "ts:")
    ;;  (pp/pprint (:zen.schema-test/ts vtx))


     (cond
       (exclusive-keys-child? vtx) vtx
       (= (last (:path vtx)) :keys) (update vtx ::ts conj " }")
       (= (last (:schema vtx)) :every) (update vtx ::ts conj ">")
       (= (last (:schema vtx)) :values) (update vtx ::ts conj ";")))))


(t/deftest patient-test
  (t/testing "patient test"
    (def ztx (zen.core/new-context {}))

    (def my-structs-ns
      '{:ns my-sturcts

        defaults
        {:zen/tags #{zen/property zen/schema}
         :type zen/boolean}

        User
        {:zen.fhir/version "0.6.12-1",
         :confirms #{hl7-fhir-r4-core.DomainResource/schema
                     zen.fhir/Resource},
         :zen/tags #{zen/schema zen.fhir/base-schema},
         :zen.fhir/profileUri "http://hl7.org/fhir/StructureDefinition/Composition",
         :require #{:date :type :title :author :status},
         :type zen/map,
         :zen/desc "A set of healthcare-related information that is assembled together into a single logical package that provides a single coherent statement of meaning, establishes its own context and that has clinical attestation with regard to who is making the statement. A Composition defines the structure and narrative content necessary for a document. However, a Composition alone does not constitute a document. Rather, the Composition must be the first entry in a Bundle where Bundle.type=document, and any other resources referenced from Composition must be included as subsequent entries in the Bundle (for example Patient, Practitioner, Encounter, etc.).",
         :keys {:category {:type zen/vector,
                           :every {:confirms #{hl7-fhir-r4-core.CodeableConcept/schema},
                                   :fhir/flags #{:SU},
                                   :zen.fhir/value-set {:symbol hl7-fhir-r4-core.value-set.document-classcodes/value-set,
                                                        :strength :example},
                                   :zen/desc "Categorization of Composition"}},
                :date {:confirms #{hl7-fhir-r4-core.dateTime/schema},
                       :fhir/flags #{:SU},
                       :zen/desc "Composition editing time"},
                :encounter {:confirms #{hl7-fhir-r4-core.Reference/schema
                                        zen.fhir/Reference},
                            :fhir/flags #{:SU},
                            :zen.fhir/reference {:refers #{hl7-fhir-r4-core.Encounter/schema}},
                            :zen/desc "Context of the Composition"},
                :_date {:confirms #{hl7-fhir-r4-core.Element/schema}},
                :section {:type zen/vector,
                          :every {:confirms #{hl7-fhir-r4-core.Composition/section-schema}}},
                :_status {:confirms #{hl7-fhir-r4-core.Element/schema}},
                :attester {:type zen/vector,
                           :every {:confirms #{hl7-fhir-r4-core.BackboneElement/schema},
                                   :type zen/map,
                                   :keys {:mode {:confirms #{hl7-fhir-r4-core.code/schema},
                                                 :zen.fhir/value-set {:symbol hl7-fhir-r4-core.value-set.composition-attestation-mode/value-set,
                                                                      :strength :required},
                                                 :zen/desc "personal | professional | legal | official"},
                                          :_mode {:confirms #{hl7-fhir-r4-core.Element/schema}},
                                          :time {:confirms #{hl7-fhir-r4-core.dateTime/schema},
                                                 :zen/desc "When the composition was attested"},
                                          :_time {:confirms #{hl7-fhir-r4-core.Element/schema}},
                                          :party {:confirms #{hl7-fhir-r4-core.Reference/schema
                                                              zen.fhir/Reference},
                                                  :zen.fhir/reference {:refers #{hl7-fhir-r4-core.Patient/schema
                                                                                 hl7-fhir-r4-core.PractitionerRole/schema
                                                                                 hl7-fhir-r4-core.Organization/schema
                                                                                 hl7-fhir-r4-core.Practitioner/schema
                                                                                 hl7-fhir-r4-core.RelatedPerson/schema}},
                                                  :zen/desc "Who attested the composition"}},
                                   :require #{:mode},
                                   :zen/desc "Attests to accuracy of composition"}},
                :type {:confirms #{hl7-fhir-r4-core.CodeableConcept/schema},
                       :fhir/flags #{:SU},
                       :zen.fhir/value-set {:symbol hl7-fhir-r4-core.value-set.doc-typecodes/value-set,
                                            :strength :preferred},
                       :zen/desc "Kind of composition (LOINC if possible)"},
                :title {:confirms #{hl7-fhir-r4-core.string/schema},
                        :fhir/flags #{:SU},
                        :zen/desc "Human Readable name/title"},
                :author {:type zen/vector,
                         :every {:confirms #{hl7-fhir-r4-core.Reference/schema
                                             zen.fhir/Reference},
                                 :fhir/flags #{:SU},
                                 :zen.fhir/reference {:refers #{hl7-fhir-r4-core.Patient/schema
                                                                hl7-fhir-r4-core.PractitionerRole/schema
                                                                hl7-fhir-r4-core.Organization/schema
                                                                hl7-fhir-r4-core.Device/schema
                                                                hl7-fhir-r4-core.Practitioner/schema
                                                                hl7-fhir-r4-core.RelatedPerson/schema}},
                                 :zen/desc "Who and/or what authored the composition"},
                         :minItems 1},
                :_confidentiality {:confirms #{hl7-fhir-r4-core.Element/schema}},
                :event {:type zen/vector,
                        :every {:confirms #{hl7-fhir-r4-core.BackboneElement/schema},
                                :type zen/map,
                                :keys {:code {:type zen/vector,
                                              :every {:confirms #{hl7-fhir-r4-core.CodeableConcept/schema},
                                                      :fhir/flags #{:SU},
                                                      :zen.fhir/value-set {:symbol hl7-terminology-r4.value-set.v3-ActCode/value-set,
                                                                           :strength :example},
                                                      :zen/desc "Code(s) that apply to the event being documented"}},
                                       :period {:confirms #{hl7-fhir-r4-core.Period/schema},
                                                :fhir/flags #{:SU},
                                                :zen/desc "The period covered by the documentation"},
                                       :detail {:type zen/vector,
                                                :every {:confirms #{hl7-fhir-r4-core.Reference/schema
                                                                    zen.fhir/Reference},
                                                        :fhir/flags #{:SU},
                                                        :zen.fhir/reference {:refers #{}},
                                                        :zen/desc "The event(s) being documented"}}},
                                :fhir/flags #{:SU},
                                :zen/desc "The clinical service(s) being documented"}},
                :custodian {:confirms #{hl7-fhir-r4-core.Reference/schema
                                        zen.fhir/Reference},
                            :fhir/flags #{:SU},
                            :zen.fhir/reference {:refers #{hl7-fhir-r4-core.Organization/schema}},
                            :zen/desc "Organization which maintains the composition"},
                :status {:confirms #{hl7-fhir-r4-core.code/schema},
                         :fhir/flags #{:SU :?!},
                         :zen.fhir/value-set {:symbol hl7-fhir-r4-core.value-set.composition-status/value-set,
                                              :strength :required},
                         :zen/desc "preliminary | final | amended | entered-in-error"},
                :identifier {:confirms #{hl7-fhir-r4-core.Identifier/schema},
                             :fhir/flags #{:SU},
                             :zen/desc "Version-independent identifier for the Composition"},
                :relatesTo {:type zen/vector,
                            :every {:confirms #{hl7-fhir-r4-core.BackboneElement/schema},
                                    :type zen/map,
                                    :keys {:code {:confirms #{hl7-fhir-r4-core.code/schema},
                                                  :zen.fhir/value-set {:symbol hl7-fhir-r4-core.value-set.document-relationship-type/value-set,
                                                                       :strength :required},
                                                  :zen/desc "replaces | transforms | signs | appends"},
                                           :_code {:confirms #{hl7-fhir-r4-core.Element/schema}},
                                           :target {:fhir/polymorphic true,
                                                    :type zen/map,
                                                    :exclusive-keys #{#{:Identifier
                                                                        :Reference}},
                                                    :keys {:Identifier {:confirms #{hl7-fhir-r4-core.Identifier/schema}},
                                                           :Reference {:confirms #{hl7-fhir-r4-core.Reference/schema
                                                                                   zen.fhir/Reference},
                                                                       :zen.fhir/reference {:refers #{hl7-fhir-r4-core.Composition/schema}}}},
                                                    :zen/desc "Target of the relationship"}},
                                    :require #{:code :target},
                                    :zen/desc "Relationships to other compositions/documents"}},
                :_title {:confirms #{hl7-fhir-r4-core.Element/schema}},
                :subject {:confirms #{hl7-fhir-r4-core.Reference/schema
                                      zen.fhir/Reference},
                          :fhir/flags #{:SU},
                          :zen.fhir/reference {:refers #{}},
                          :zen/desc "Who and/or what the composition is about"},
                :confidentiality {:confirms #{hl7-fhir-r4-core.code/schema},
                                  :fhir/flags #{:SU},
                                  :zen.fhir/value-set {:symbol hl7-fhir-r4-core.value-set.v3-ConfidentialityClassification/value-set,
                                                       :strength :required},
                                  :zen/desc "As defined by affinity domain"}},
         :zen.fhir/type "Composition"}})

    (zen.core/load-ns ztx my-structs-ns)

    (def ts-typedef-assert
      (str "type User = {"
           "id: string;"
           "email: string;"
           "name: Array < {"
           "given: Array < string >;"
           "family: string;"
           "}>;}"))

    (def r
      (sut/apply-schema ztx
                        {::ts []
                         ::require {}
                         ::exclusive-keys {}}
                        (zen.core/get-symbol ztx 'zen/schema)
                        (zen.core/get-symbol ztx 'my-sturcts/User)
                        {:interpreters [::ts]})) 

    (str/join "" (::ts r))

    (t/is (= ts-typedef-assert (str/join "" (::ts r))))))

(defn remove-prac [st]
  (str/replace
   st #"generalPractitioner:Array<Reference<'PractitionerRole' \| 'Organization' \| 'Practitioner'>>;" ""))

(comment
  ;; CLASSPATH
  ;; :paths (path to zrc/)
  ;; :package-paths (path to a project. project = dir with zrc/ and zen-package.edn)

  (zen.package/zen-init-deps! "/Users/pavel/Desktop/zen/test/test_project")

  (def ztx
    (zen.core/new-context
     {:package-paths ["/Users/ross/Desktop/HS/zen/test/test_project"]}))
  (zen.core/read-ns ztx 'hl7-fhir-r4-core)
  (zen.core/get-symbol ztx 'hl7-fhir-r4-core/ig)
  (zen.core/get-symbol ztx 'hl7-fhir-r4-core/base-schemas)
  (zen.core/get-symbol ztx 'hl7-fhir-r4-core/structures)
  (zen.core/read-ns ztx 'hl7-fhir-r4-core.code)
  (zen.core/get-symbol ztx 'hl7-fhir-r4-core.code/schema)
  (zen.core/get-symbol ztx 'hl7-fhir-r4-core.CodeableConcept/schema)


  (def r
    (sut/apply-schema ztx
                      {::ts []}
                      (zen.core/get-symbol ztx 'zen/schema)
                      (zen.core/get-symbol ztx 'hl7-fhir-r4-core.Patient/schema)
                      {:interpreters [::ts]}))


  (str/join "" (::ts r))

  (def ignored-resources ["base64Binary"
                          "canonical"
                          "code"
                          "date"
                          "dateTime"
                          "decimal"
                          "email"
                          "id"
                          "instant"
                          "integer"
                          "keyword"
                          "markdown"
                          "oid"
                          "password"
                          "positiveInt"
                          "time"
                          "unsignedInt"
                          "uri"
                          "url"
                          "uuid"
                          "xhtml"])

  (def schema (:schemas (zen.core/get-symbol ztx 'hl7-fhir-r4-core/base-schemas)))
  (def structures (:schemas (zen.core/get-symbol ztx 'hl7-fhir-r4-core/structures)))

  (mapv (fn [[k _v]]
          (println k)
          (zen.core/read-ns ztx (symbol (str "hl7-fhir-r4-core." k)))
          (zen.core/get-symbol ztx (symbol (str "hl7-fhir-r4-core." k "/schema")))
          (def r (sut/apply-schema ztx
                                   {::ts []
                                    ::require {}
                                    ::exclusive-keys {}}
                                   (zen.core/get-symbol ztx 'zen/schema)
                                   (zen.core/get-symbol ztx (symbol (str "hl7-fhir-r4-core." k "/schema")))
                                   {:interpreters [::ts]}))
          (spit "./result.ts" (str/join "" (conj (::ts r) ";\n")) :append true)) schema)

  (mapv (fn [[_k v]]
          (let [n (str/trim (str/replace (namespace v) #"hl7-fhir-r4-core." ""))
                ns  (zen.core/read-ns ztx (symbol (namespace v)))
                schema (zen.core/get-symbol ztx (symbol v))]
           
            (when (:keys schema) ((def r (sut/apply-schema ztx
                                                                                   {::ts []
                                                                                    ::require {}
                                                                                    ::exclusive-keys {}
                                                                                    ::interface-name n}
                                                                                   (zen.core/get-symbol ztx 'zen/schema)
                                                                                   (zen.core/get-symbol ztx (symbol v))
                                                                                   {:interpreters [::ts]})) 
                                                          (spit "./result.ts" (str/join "" (conj (::ts r) ";\n")) :append true)))
            )
          
         ) structures)
  )

(t/deftest ^:kaocha/pending custom-interpreter-test
  (t/testing "typescript type generation"
    (def ztx (zen.core/new-context {}))

    (def my-structs-ns
      '{:ns my-sturcts

        User
        {:zen/tags #{zen/schema}
         :type zen/map
         :keys {:id {:type zen/string}
                :email {:type zen/string
                        #_#_:regex "@"}
                :name {:type zen/vector
                       :every {:type zen/map
                               :keys {:given {:type zen/vector
                                              :every {:type zen/string}}
                                      :family {:type zen/string}}}}}}})

    (zen.core/load-ns ztx my-structs-ns)

    (def ts-typedef-assert
      (str "type User = {"
           "id: string;"
           "email: string;"
           "name: Array < {"
           "given: Array < string >;"
           "family: string;"
           "}>;}"))

    (def r
      (sut/apply-schema ztx
                        {::ts []}
                        (zen.core/get-symbol ztx 'zen/schema)
                        (zen.core/get-symbol ztx 'my-sturcts/User)
                        {:interpreters [::ts]}))

    (t/is (= ts-typedef-assert (str/join "" (::ts r))))))



(t/deftest ^:kaocha/pending custom-interpreter-test
  (t/testing "should correctly generate"
    (def ztx (zen.core/new-context {}))
    (zen.core/get-tag ztx 'zen/type)

    (def my-ns
      '{:ns my

        defaults
        {:zen/tags #{zen/schema zen/is-key}
         :zen/desc "only primitive default values are supported currently"
         :for #{zen/map}
         :priority 100
         :type zen/map
         :key {:type zen/keyword}
         :values {:type zen/case
                  :case [{:when {:type zen/boolean}}
                         {:when {:type zen/date}}
                         {:when {:type zen/datetime}}
                         {:when {:type zen/integer}}
                         {:when {:type zen/keyword}}
                         {:when {:type zen/number}}
                         {:when {:type zen/qsymbol}}
                         {:when {:type zen/regex}}
                         {:when {:type zen/string}}
                         {:when {:type zen/symbol}}]}}

        HumanName
        {:zen/tags #{zen/schema}
         :type zen/map
         :require #{:family :given}
         :keys {:given {:type zen/vector
                        :minItems 1
                        :every {:type zen/string}}
                :family {:type zen/string}}}

        DefaultHumanName
        {:zen/tags #{zen/schema}
         :type zen/map
         :my/defaults {:family "None"}}

        User
        {:zen/tags #{zen/schema}
         :type zen/map
         :keys {:id {:type zen/string}
                :name {:type zen/vector
                       :every {:confirms #{HumanName DefaultHumanName}}}
                :active {:type zen/boolean}
                :count {:type zen/number}}
         :zen.fhir/type "Patient"}})

    (zen.core/load-ns ztx my-structs-ns)

    #_(matcho/match (zen.core/errors ztx) #_"NOTE: FIXME: keys that use get-cached during compile time won't be recompiled when these schemas used in get-cached updated. E.g. adding new is-key for zen/schema won't cause zen/schema recompile and the key won't be recognized by zen/schema validation"
                    empty?)

    (def data
      {:id "foo"
       :email "bar@baz"
       :name [{:given ["foo"]}]})

    (def r
      (sut/apply-schema ztx
                        {::ts []}
                        (zen.core/get-symbol ztx 'zen/schema)
                        (zen.core/get-symbol ztx 'my-sturcts/User)
                        {:interpreters [::ts]}))

    (t/is (= ts-typedef-assert (str/join "" (::ts r))))))


    (matcho/match (::with-defaults r)
                  {:id "foo"
                   :email "bar@baz"
                   :active true
                   :name   [{:family "None"
                             :given  ["foo"]}]})


(t/deftest ^:kaocha/pending dynamic-confirms-cache-reset-test
  (def ztx (zen.core/new-context {}))

  #_"NOTE: you can drop cache to see that this fixes validation"
  #_(swap! ztx
           dissoc
           :errors
           :zen.v2-validation/compiled-schemas
           :zen.v2-validation/prop-schemas
           :zen.v2-validation/cached-pops)

  (def my-ns
    '{:ns my-ns

      b {:zen/tags #{zen/schema}
         :type zen/string
         :const {:value "foo"}}

      a {:zen/tags #{zen/schema}
         :my-ns/test-key "should be no errorors, this key is registred via my-ns/test-key"
         :confirms #{b}}})

  (zen.core/load-ns ztx my-ns)

  (matcho/match
    (zen.core/validate ztx #{'my-ns/a} "foo")
    {:errors empty?})

  (zen.core/load-ns ztx (assoc-in my-ns ['b :const :value] "bar"))

  (matcho/match
    (zen.core/validate ztx #{'my-ns/a} "foo")
    {:errors [{} nil]}))


(t/deftest ^:kaocha/pending dynamic-key-schema-cache-reset-test
  (def ztx (zen.core/new-context {}))

  #_"NOTE: you can drop cache to see that this fixes validation"
  #_(swap! ztx
           dissoc
           :errors
           :zen.v2-validation/compiled-schemas
           :zen.v2-validation/prop-schemas
           :zen.v2-validation/cached-pops)

  (def my-ns
    '{:ns my-ns

      test-key
      {:zen/tags #{zen/is-key zen/schema}
       :zen/desc "just a key that should be allowed in any schema"
       :type zen/any}

      a {:zen/tags #{zen/schema}
         :my-ns/test-key "should be no errorors, this key is registred via my-ns/test-key"}})

  (zen.core/load-ns ztx my-ns)

  (matcho/match
    (zen.core/errors ztx)
    empty?))
