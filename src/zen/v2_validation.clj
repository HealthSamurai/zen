(ns zen.v2-validation
  (:require
   [zen.schema]
   [zen.validation.utils
    :refer :all
    :exclude [add-err fhir-date-regex fhir-datetime-regex types-cfg]
    :as validation.utils]
   [zen.effect]
   [zen.match]
   [clojure.set :as set]
   [clojure.string :as str]
   [zen.utils :as utils]))


;; backwards-compatible aliases used in this ns
(def get-cached       zen.schema/get-cached)
(def compile-key      zen.schema/compile-key)
(def add-err   zen.validation.utils/add-err)
(def types-cfg zen.validation.utils/types-cfg)


#_"NOTE: aliases for backwards-compatibility.
Uncomment if something breaks.
Probably safe to remove if no one relies on them"
#_(def resolve-props zen.schema/resolve-props)
#_(def compile-schema zen.schema/compile-schema)
#_(def safe-compile-key zen.schema/safe-compile-key)
#_(def validate-props zen.schema/navigate-props)
#_(def rule-priority zen.schema/rule-priority)
#_(def fhir-date-regex zen.validation.utils/fhir-date-regex)
#_(def fhir-datetime-regex zen.validation.utils/fhir-datetime-regex)


(defn resolve-props [ztx]
  (let [props-syms   (utils/get-tag ztx 'zen/property)
        cached-props (::cached-props @ztx)]
    (if (= cached-props props-syms)
      (::prop-schemas @ztx)
      (->> props-syms
           (map (fn [prop]
                  (zen.utils/get-symbol ztx prop)))
           (map (fn [sch]
                  [sch (get-cached ztx sch false)]))
           (reduce (fn [acc [sch v]]
                     (assoc acc (keyword (:zen/name sch)) v))
                   {})
           (swap! ztx assoc ::cached-pops props-syms, ::prop-schemas)
           ::prop-schemas))))


(defn validate-props [vtx data props opts]
  (reduce (fn [vtx* prop]
            (if-let [prop-value (get data prop)]
              (-> (validation.utils/node-vtx&log vtx* [:property prop] [prop])
                  ((get props prop) prop-value opts)
                  (validation.utils/merge-vtx vtx*))
              vtx*))
          vtx
          (keys props)))


(defn props-pre-process-hook [ztx schema]
  (let [#_"FIXME: won't reeval proprs if they change in run-time"
        props (resolve-props ztx)]
    (fn [vtx data opts]
      (validate-props vtx data props opts))))


(zen.schema/register-schema-pre-process-hook!
  ::validate
  props-pre-process-hook)


(defn valtype-rule [vtx data open-world?] #_"NOTE: maybe refactor name to 'set-unknown-keys ?"
  (let [filter-allowed
        (fn [unknown]
          (->> unknown
               (remove #(= (vec (butlast %)) (:path vtx)))
               set))

        set-unknown
        (fn [unknown]
          (let [empty-unknown? (empty? unknown)
                empty-visited? (empty? (:visited vtx))]
            (cond (and empty-unknown? (not empty-visited?))
                  (set/difference (cur-keyset vtx data)
                                  (:visited vtx))

                  (and empty-unknown? empty-visited?)
                  (set (validation.utils/cur-keyset vtx data))

                  (not empty-unknown?)
                  (set/difference unknown (:visited vtx)))))]

    (if open-world?
      (-> vtx
          (update :unknown-keys filter-allowed)
          (update :visited into (validation.utils/cur-keyset vtx data)))
      (update vtx :unknown-keys set-unknown))))


(defn unknown-keys-post-process-hook [ztx schema]
  (when (and (some? (:type schema))
             (nil? (:validation-type schema)))
    (let [open-world? (or (:key schema)
                          (:values schema)
                          (= (:validation-type schema) :open)
                          (= (:type schema) 'zen/any))]
      (fn [vtx data opts]
        (when (map? data)
          (valtype-rule vtx data open-world?))))))


(zen.schema/register-schema-post-process-hook!
  ::validate
  unknown-keys-post-process-hook)


(zen.schema/register-compile-key-interpreter!
  [:validation-type ::validate]
  (fn [_ ztx tp]
    (let [open-world? (= :open tp)]
      (fn [vtx data opts] (valtype-rule vtx data open-world?)))))


(defmulti compile-type-check (fn [tp ztx] tp))


(defn *validate-schema
  "internal, use validate function"
  [ztx vtx schema data {:keys [_sch-symbol] :as opts}]
  (zen.schema/apply-schema ztx vtx schema data (assoc opts :interpreters [::validate])))


(defn validate-schema [ztx schema data & [opts]]
  (let [empty-vtx {:errors []
                   :warnings []
                   :visited #{}
                   :unknown-keys #{}
                   :effects []}]
    (-> ztx
        (*validate-schema empty-vtx schema data opts)
        (unknown-errs))))

(defn validate [ztx schemas data & [opts]]
  (loop [schemas (seq schemas)
         vtx {:errors []
              :warnings []
              :visited #{}
              :unknown-keys #{}
              :effects []}]
    (if (empty? schemas)
      (unknown-errs vtx)
      #_(-> (unknown-errs vtx)
            (dissoc :unknown-keys ::confirmed)
            (cond-> (not (:vtx-visited opts)) (dissoc :visited)))
      (if-let [schema (utils/get-symbol ztx (first schemas))]
        (if (true? (get-in vtx [::confirmed [] (first schemas)]))
          (recur (rest schemas) vtx)
          (recur (rest schemas)
                 (*validate-schema ztx vtx schema data (assoc opts :sch-symbol (first schemas)))))
        (recur (rest schemas)
               (update vtx :errors conj
                       {:message (str "Could not resolve schema '" (first schemas))
                        :type "schema"}))))))

(defn type-fn [sym]
  (let [type-cfg (get types-cfg sym)
        type-pred (if (fn? type-cfg) type-cfg (:fn type-cfg))]
    (fn [vtx data _]
      (let [pth-key (last (:path vtx))]
        (cond
          ;; TODO fix this when compile-opts are implemented
          (get #{:zen/tags :zen/file :zen/desc :zen/name} pth-key) vtx

          (type-pred data) vtx

          :else
          (let [error-msg
                {:message (str "Expected type of '" (or (:to-str type-cfg) sym)
                               ", got '" (pretty-type data))}]
            (add-err vtx :type error-msg)))))))

(defmethod compile-type-check 'zen/string [_ _] (type-fn 'zen/string))
(defmethod compile-type-check 'zen/number [_ _] (type-fn 'zen/number))
(defmethod compile-type-check 'zen/set [_ _] (type-fn 'zen/set))
(defmethod compile-type-check 'zen/map [_ _] (type-fn 'zen/map))
(defmethod compile-type-check 'zen/vector [_ _] (type-fn 'zen/vector))
(defmethod compile-type-check 'zen/boolean [_ _] (type-fn 'zen/boolean))
(defmethod compile-type-check 'zen/list [_ _] (type-fn 'zen/list))
(defmethod compile-type-check 'zen/keyword [_ _] (type-fn 'zen/keyword))
(defmethod compile-type-check 'zen/any [_ _] (type-fn 'zen/any))
(defmethod compile-type-check 'zen/integer [_ _] (type-fn 'zen/integer))
(defmethod compile-type-check 'zen/symbol [_ _] (type-fn 'zen/symbol))
(defmethod compile-type-check 'zen/qsymbol [_ _] (type-fn 'zen/qsymbol))
(defmethod compile-type-check 'zen/regex [_ _] (type-fn 'zen/regex))
(defmethod compile-type-check 'zen/case [_ _] (type-fn 'zen/case))
(defmethod compile-type-check 'zen/date [_ _] (type-fn 'zen/date))
(defmethod compile-type-check 'zen/datetime [_ _] (type-fn 'zen/datetime))

(defmethod compile-type-check :default
  [tp ztx]
  (fn [vtx data opts]
    (add-err vtx :type {:message (format "No validate-type multimethod for '%s" tp)})))

(defmethod compile-type-check 'zen/apply
  [tp ztx]
  (fn [vtx data opts]
    (cond
      (not (list? data))
      (add-err vtx :type {:message (str "Expected fn call '(fn-name args-1 arg-2), got '"
                                        (pretty-type data))})

      (not (symbol? (nth data 0)))
      (add-err vtx :apply {:message (str "Expected symbol, got '" (first data))
                           :type "apply.fn-name"})

      :else
      (let [sch-sym (nth data 0)
            {:keys [zen/tags args] :as sch} (utils/get-symbol ztx sch-sym)]
        (cond
          (nil? sch)
          (add-err vtx :apply {:message (str "Could not resolve fn '" sch-sym)
                               :type "apply.fn-name"})

          (not (contains? tags 'zen/fn))
          (add-err vtx :apply {:message (format "fn definition '%s should be tagged with 'zen/fn, but '%s" sch-sym tags)
                               :type "apply.fn-tag"})

          :else
          (let [v (get-cached ztx args false)]
            (-> (node-vtx vtx [sch-sym :args])
                (v (vec (rest data)) opts)
                (merge-vtx vtx))))))))

(zen.schema/register-compile-key-interpreter!
  [:type ::validate]
  (fn [_ ztx tp] (compile-type-check tp ztx)))

(zen.schema/register-compile-key-interpreter!
  [:case ::validate]
  #_"NOTE: this is a conditional navigation.
           Conditions are taken from ::validate interpreter
           Can't split into independant :zen.schema/navigate and ::validate"
  (fn [_ ztx cases]
    (let [vs (doall
               (map (fn [{:keys [when then]}]
                      (cond-> {:when (get-cached ztx when false)}
                        (not-empty then) (assoc :then (get-cached ztx then false))))
                    cases))]
      (fn [vtx data opts]
        (loop [[{wh :when th :then :as v} & rest] vs
               item-idx 0
               vtx* vtx
               passed []]
          (cond
            (and (nil? v) (not-empty passed))
            vtx*

            (nil? v)
            (add-err vtx*
                     :case
                     {:message (format "Expected one of the cases to be true") :type "case"})

            :else
            (let [when-vtx (wh (node-vtx vtx* [:case item-idx :when]) data opts)]
              (cond
                (and (empty? (:errors when-vtx)) th)
                (let [merged-vtx (merge-vtx when-vtx vtx*)]
                  (-> merged-vtx
                      (node-vtx [:case item-idx :then])
                      (th data opts)
                      (merge-vtx merged-vtx)))

                (empty? (:errors when-vtx))
                (recur rest (inc item-idx) (merge-vtx when-vtx vtx*) (conj passed v))

                :else (recur rest (inc item-idx) vtx* passed)))))))))

(zen.schema/register-compile-key-interpreter!
  [:enum ::validate]
  (fn [_ ztx values]
    (let [values* (set (map :value values))]
      (fn [vtx data opts]
        (if-not (contains? values* data)
          (add-err vtx :enum {:message (str "Expected '" data "' in " values*) :type "enum"})
          vtx)))))

(zen.schema/register-compile-key-interpreter!
  [:match ::validate]
  (fn [_ ztx pattern]
    (fn match-fn [vtx data opts]
      (let [errs (zen.match/match data pattern)]
        (if-not (empty? errs)
          (->> errs
               (reduce (fn [acc err]
                         (let [err-msg
                               (or (:message err)
                                   (str "Expected " (pr-str (:expected err)) ", got " (pr-str (:but err))))]
                           (apply add-err (into [acc :match {:message err-msg :type "match"}]
                                                (:path err)))))
                       vtx))
          vtx)))))

(zen.schema/register-compile-key-interpreter!
  [:scale ::validate]
  (fn [_ ztx scale]
    (fn [vtx num opts]
      (let [dc (bigdec num)
            num-scale (.scale dc)]
        (if (<= num-scale scale)
          vtx
          (add-err vtx :scale
                   {:message (str "Expected scale = " scale ", got " (.scale dc))}))))))

(zen.schema/register-compile-key-interpreter!
  [:precision ::validate]
  (fn [_ ztx precision]
    (fn [vtx num opts]
      (let [dc (bigdec num)
            num-precision (.precision dc)
            ;; NOTE: fraction will be used when we add composite checking scale + precision
            #_#_fraction (.remainder dc BigDecimal/ONE)]
        (if (<= num-precision precision)
          vtx
          (add-err vtx :precision
                   {:message (str "Expected precision = " precision ", got " num-precision)}))))))

(zen.schema/register-compile-key-interpreter!
  [:min ::validate]
  (fn [_ ztx min]
    (fn [vtx data opts]
      (if (< data min)
        (add-err vtx :min {:message (str "Expected >= " min ", got " data)})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:max ::validate]
  (fn [_ ztx max]
    (fn [vtx data opts]
      (if (> data max)
        (add-err vtx :max {:message (str "Expected <= " max ", got " data)})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:minLength ::validate]
  (fn [_ ztx min-len]
    (fn [vtx data opts]
      (if (< (count data) min-len)
        (add-err vtx
                 :minLength
                 {:message (str "Expected length >= " min-len ", got " (count data))})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:maxLength ::validate]
  (fn [_ ztx max-len]
    (fn [vtx data opts]
      (if (> (count data) max-len)
        (add-err vtx
                 :maxLength
                 {:message (str "Expected length <= " max-len ", got " (count data))})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:minItems ::validate]
  (fn [_ ztx items-count]
    (fn [vtx data opts]
      (if (< (count data) items-count)
        (add-err vtx
                 :minItems
                 {:message (str "Expected >= " items-count ", got " (count data))})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:maxItems ::validate]
  (fn [_ ztx items-count]
    (fn [vtx data opts]
      (if (> (count data) items-count)
        (add-err vtx
                 :maxItems
                 {:message (str "Expected <= " items-count ", got " (count data))})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:const ::validate]
  (fn [_ ztx {:keys [value]}]
    (fn [vtx data opts]
      (if (not= value data)
        (add-err vtx :const
                 {:message (str "Expected '" value "', got '" data "'")
                  :type "schema"})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:keys ::validate]
  (fn [_ ztx ks]
    (let [known-keys (set (keys ks))]
      (fn keys-sch [vtx data opts]
        (let [data-keys    (set (keys data))
              unknown-keys (set/difference data-keys known-keys)]
          (update vtx
                  :unknown-keys
                  into
                  (map #(conj (:path vtx) %))
                  unknown-keys))))))

(zen.schema/register-compile-key-interpreter!
  [:subset-of ::validate]
  (fn [_ ztx superset]
    (fn [vtx data opts]
      (if-not (clojure.set/subset? data superset)
        (add-err vtx :subset-of {:type "set"})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:superset-of ::validate]
  (fn [_ ztx subset]
    (fn [vtx data opts]
      (if-not (clojure.set/subset? subset data)
        (add-err vtx :superset-of {:type "set"})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:regex ::validate]
  (fn [_ ztx regex]
    (fn [vtx data opts]
      (if (not (re-find (re-pattern regex) data))
        (add-err vtx :regex
                 {:message (str "Expected match /" (str regex) "/, got \"" data "\"")})
        vtx))))

(zen.schema/register-compile-key-interpreter!
  [:require ::validate]
  (fn [_ ztx ks] ;; TODO decide if require should add to :visited keys vector
    (let [one-of-fn
          (fn [vtx data s]
            (let [reqs (->> (select-keys data s) (remove nil?))]
              (if (empty? reqs)
                (add-err vtx :require {:type "map.require"
                                       :message (str "one of keys " s " is required")})
                vtx)))]
      (fn [vtx data opts]
        (reduce (fn [vtx* k]
                  (cond
                    (set? k) (one-of-fn vtx* data k)
                    (contains? data k) vtx*
                    :else
                    (add-err vtx* :require {:type "require" :message (str k " is required")} k)))
                vtx
                ks)))))

(defmethod compile-key :nth
  [_ ztx cfg]
  (let [schemas (doall
                 (map (fn [[index v]] [index (get-cached ztx v false)])
                      cfg))]
    {:when sequential?
     :rule
     (fn [vtx data opts]
       (reduce (fn [vtx* [index v]]
                 (if-let [nth-el (and (< index (count data))
                                      (nth data index))]
                   (-> (node-vtx vtx* [:nth index] [index])
                       (v nth-el opts)
                       (merge-vtx vtx*))
                   vtx*))
               vtx
               schemas))}))

(defmethod compile-key :keyname-schemas
  [_ ztx {:keys [tags]}]
  {:when map?
   :rule
   (fn [vtx data opts]
     (let [rule-fn
           (fn [vtx* [schema-key data*]]
             (if-let [sch (and (qualified-ident? schema-key) (utils/get-symbol ztx (symbol schema-key)))]
                ;; TODO add test on nil case
               (if (or (nil? tags)
                       (clojure.set/subset? tags (:zen/tags sch)))
                 (-> (node-vtx&log vtx* [:keyname-schemas schema-key] [schema-key])
                     ((get-cached ztx sch false) data* opts)
                     (merge-vtx vtx*))
                 vtx*)
               vtx*))]
       (reduce rule-fn vtx data)))})

(defmethod compile-key :default [schema-key ztx sch-params]
  (cond
    (qualified-ident? schema-key)
    (let [{:keys [zen/tags] :as sch} (utils/get-symbol ztx (symbol schema-key))] #_"NOTE: `:keys [zen/tags]` does it work? Is it used?"
      {:rule
       (fn [vtx data opts]
         (cond
           (contains? tags 'zen/schema-fx)
           (add-fx vtx (:zen/name sch)
                   {:name (:zen/name sch)
                    :params sch-params
                    :data data})

           :else vtx))})

    :else {:rule (fn [vtx data opts] vtx)}))

(defn is-exclusive? [group data]
  (->> group
       (filter #(->> (if (set? %) % #{%})
                     (select-keys data)
                     seq))
       (bounded-count 2)
       (> 2)))

(defmethod compile-key :exclusive-keys
  [_ ztx groups]
  (let [err-fn
        (fn [group [vtx data]]
          (if (is-exclusive? group data)
            (list vtx data)
            (let [err-msg
                  (format "Expected only one of keyset %s, but present %s"
                          (str/join " or " group)
                          (keys data))
                  vtx*
                  (add-err vtx :exclusive-keys
                           {:message err-msg
                            :type "map.exclusive-keys"})]
              (list vtx* data))))

        comp-fn
        (->> groups
             (map #(partial err-fn %))
             (apply comp))]

    {:rule
     (fn [vtx data opts]
       (-> (list vtx data)
           comp-fn
           (nth 0)))}))

(defmethod compile-key :key
  [_ ztx sch]
  (let [v (get-cached ztx sch false)]
    {:when map?
     :rule
     (fn [vtx data opts]
       (reduce (fn [vtx* [k _]]
                 (let [node-visited?
                       (when-let [pth (get (:visited vtx*) (cur-path vtx* [k]))]
                         (:keys (get (:visited-by vtx*) pth)))

                       strict?
                       (= (:valmode opts) :strict)]
                   (if (and (not strict?) node-visited?)
                     vtx*
                     (-> (node-vtx&log vtx* [:key] [k])
                         (v k opts)
                         (merge-vtx vtx*)))))
               vtx
               data))}))

(defmethod compile-key :tags
  [_ ztx sch-tags]
  ;; currently :tags implements three usecases:
  ;; tags check where schema name is string or symbol
  ;; and zen.apply tags check (list notation)
  {:when #(or (symbol? %)
              (list? %)
              (string? %))
   :rule
   (fn [vtx data opts]
     (let [[sym type-err]
           (cond
             (list? data) [(nth data 0) "apply.fn-tag"]
             (string? data) [(symbol data) "string"]
             (symbol? data) [data "symbol"])
           {:keys [zen/tags] :as sch} (utils/get-symbol ztx sym)]
       (if (not (clojure.set/superset? tags sch-tags))
         (add-err vtx :tags
                  {:message
                   (cond
                     (nil? sch) (format "No symbol '%s found" sym)
                     :else
                     (format "Expected symbol '%s tagged with '%s, but only %s"
                             (str sym) (str sch-tags) (or tags #{})))
                   :type type-err})
         vtx)))})


(defmulti slice-fn (fn [ztx [_slice-name slice-schema]]
                     (get-in slice-schema [:filter :engine])))


(defmethod slice-fn :default [ztx [slice-name slice-schema]]
  (fn [vtx [idx el] opts]
    nil) #_"TODO add error if engine is not found?")


(defmethod slice-fn :zen [ztx [slice-name slice-schema]]
  (let [v (get-cached ztx (get-in slice-schema [:filter :zen]) false)]
    (fn [vtx [idx el] opts]
      (let [vtx* (v (node-vtx vtx [:slicing] [idx]) el opts)]
        (when (empty? (:errors vtx*))
          slice-name)))))


(defmethod slice-fn :match [ztx [slice-name slice-schema]]
  (fn [vtx [idx el] opts]
    (let [errs
          (->> (get-in slice-schema [:filter :match])
               (zen.match/match el))]
      (when (empty? errs)
        slice-name))))


(defmethod slice-fn :zen-fx [ztx [slice-name slice-schema]]
  (let [v (get-cached ztx (get-in slice-schema [:filter :zen]) false)]
        (fn [vtx [idx el] opts]
          (let [vtx* (v (node-vtx vtx [:slicing] [idx]) el opts)
                effect-errs (zen.effect/apply-fx ztx vtx* el)]
            (when (empty? (:errors effect-errs))
              slice-name)))))


(defn err-fn [schemas rest-fn opts vtx [slice-name slice]]
  (if (and (= slice-name :slicing/rest) (nil? rest-fn))
    vtx
    (let [v (if (= slice-name :slicing/rest)
              rest-fn
              (get schemas slice-name))]
      (-> (node-vtx vtx [:slicing slice-name])
          (v (mapv #(nth % 1) slice) (assoc opts :indices (map #(nth % 0) slice)))
          (merge-vtx vtx)))))

(defmethod compile-key :slicing
  [_ ztx {slices :slices rest-schema :rest}]
  (let [schemas
        (->> slices
             (map (fn [[slice-name {:keys [schema]}]]
                    [slice-name (get-cached ztx schema false)]))
             (into {}))

        rest-fn
        (when (not-empty rest-schema)
          (get-cached ztx rest-schema false))

        slice-fns (map (partial slice-fn ztx) slices)

        slices-templ
        (->> slices
             (map (fn [[slice-name _]]
                    [slice-name []]))
             (into {}))]

    {:when sequential?
     :rule
     (fn slicing-sch [vtx data opts]
       (->> data
            (map-indexed vector)
            (group-by (fn [indexed-el]
                        (or (some #(apply % [vtx indexed-el opts]) slice-fns)
                            :slicing/rest)))
            (merge slices-templ)
            (reduce (partial err-fn schemas rest-fn opts) vtx)))}))

(zen.schema/register-compile-key-interpreter!
  [:fail ::validate]
  (fn [_ ztx err-msg]
    (fn fail-fn [vtx data opts]
      (add-err vtx :fail {:message err-msg}))))

(defmethod compile-key :key-schema
  [_ ztx {:keys [tags key]}]
  {:when map?
   :rule
   (fn key-schema-fn [vtx data opts]
     (let [keys-schemas
           (->> tags
                (mapcat #(utils/get-tag ztx %))
                (mapv (fn [sch-name]
                        (let [sch (utils/get-symbol ztx sch-name)] ;; TODO get rid of type coercion
                          {:sch-key (if (= "zen" (namespace sch-name))
                                      (keyword (name sch-name))
                                      (keyword sch-name))
                           :for?    (:for sch)
                           :v       (get-cached ztx sch false)}))))

           key-rules
           (into {}
                 (keep (fn [{:keys [sch-key for? v]}]
                         (when (or (nil? for?)
                                   (contains? for? (get data key)))
                           [sch-key v])))
                 keys-schemas)]

       (loop [data (seq data)
              unknown (transient [])
              vtx* vtx]
         (if (empty? data)
           (update vtx* :unknown-keys into (persistent! unknown))
           (let [[k v] (first data)]
             (if (not (contains? key-rules k))
               (recur (rest data) (conj! unknown (conj (:path vtx) k)) vtx*)
               (recur (rest data)
                      unknown
                      (-> (node-vtx&log vtx* [k] [k])
                          ((get key-rules k) v opts)
                          (merge-vtx vtx*)))))))))})
