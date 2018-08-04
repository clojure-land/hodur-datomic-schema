(ns hodur-datomic-schema.core
  (:require [camel-snake-kebab.core :refer [->kebab-case-string]]
            [datascript.core :as d]
            [datascript.query-v3 :as q]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti ^:private primitive-or-ref-type
  (fn [field]
    (let [ref-type (-> field :field/type :type/name)
          dat-type (-> field :datomic/type)]
      (or dat-type ref-type))))

(defmethod primitive-or-ref-type "String" [_] :db.type/string)

(defmethod primitive-or-ref-type "Float" [_] :db.type/float)

(defmethod primitive-or-ref-type "Integer" [_] :db.type/long)

(defmethod primitive-or-ref-type "Boolean" [_] :db.type/boolean)

(defmethod primitive-or-ref-type "DateTime" [_] :db.type/instant)

(defmethod primitive-or-ref-type "ID" [_] :db.type/uuid)

(defmethod primitive-or-ref-type :default [_] :db.type/ref)

(defn ^:private get-value-type
  [field]
  (if-let [dat-type (-> field :datomic/type)]
    dat-type
    (primitive-or-ref-type field)))

(defn ^:private get-cardinality
  [{:keys [field/arity]}]
  (if arity
    (if (and (= (first arity) 1)
             (= (second arity) 1))
      :db.cardinality/one
      :db.cardinality/many) 
    :db.cardinality/one))

(defn ^:private assoc-documentation
  [m {:keys [field/doc field/deprecation]}]
  (if (or doc deprecation)
    (assoc m :db/doc
           (cond-> ""
             doc                   (str doc)
             (and doc deprecation) (str "\n\n")
             deprecation           (str "DEPRECATION NOTE: " deprecation)))
    m))

(defn ^:private assoc-attributes
  [m field]
  (let [table {:datomic/isComponent :db/isComponent
               :datomic/fulltext :db/fulltext
               :datomic/index :db/index
               :datomic/unique :db/unique
               :datomic/noHistory :db/noHistory}]
    (reduce-kv (fn [a k v]
                 (if-let [entry (get table k)]
                   (assoc a entry v)
                   a))
               m field)))

(defn ^:private process-field
  [entity-id is-enum? {:keys [field/name] :as field}]
  (cond-> {:db/ident (keyword entity-id
                              (->kebab-case-string name))}
    (not is-enum?) (assoc :db/valueType (get-value-type field)
                          :db/cardinality (get-cardinality field))
    (not is-enum?) (assoc-attributes field)
    
    :always        (assoc-documentation field)))


(defn ^:private get-type
  [{:keys [type/name type/enum field/_parent]}]
  (let [entity-id (->kebab-case-string name)]
    (->> _parent
         (sort-by :field/name)
         (reduce (fn [c field]
                   (conj c (process-field entity-id enum field)))
                 []))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn schema
  [conn]
  (let [selector '[* {:field/_parent
                      [* {:field/type [*]}]}]
        eids (-> (q/q '[:find ?e
                        :where
                        [?e :type/nature :user]
                        [?e :datomic/tag true]
                        (not [?e :type/interface true])]
                      @conn)
                 vec flatten)
        types (->> eids
                   (d/pull-many @conn selector)
                   (sort-by :type/name))]
    (->> types
         (reduce (fn [c t]
                   (concat c (get-type t)))
                 [])
         vec)))


(comment
  (do
    (require '[hodur-engine.core :as engine])

    (def conn (engine/init-schema
               '[^{:datomic/tag true}
                 default

                 ^:interface
                 Person
                 [^String name]
                 
                 Employee
                 [^String name
                  ^{:type String
                    :doc "The very employee number of this employee"
                    :datomic/unique :db.unique/identity}
                  number
                  ^Float salary
                  ^Integer age
                  ^DateTime start-date
                  ^Employee supervisor
                  ^{:type Employee
                    :arity [0 n]
                    :doc "Has documentation"
                    :deprecation "But also deprecation"}
                  co-workers
                  ^{:datomic/type :db.type/keyword}
                  keword-type
                  ^{:datomic/type :db.type/uri}
                  uri-type
                  ^{:datomic/type :db.type/double}
                  double-type
                  ^{:datomic/type :db.type/bigdec
                    :deprecation "This is deprecated" }
                  bigdec-type
                  ^EmploymentType employment-type]

                 ^{:enum true}
                 EmploymentType
                 [FULL_TIME
                  ^{:doc "Documented enum"}
                  PART_TIME]]))

    (clojure.pprint/pprint
     (schema conn))))
