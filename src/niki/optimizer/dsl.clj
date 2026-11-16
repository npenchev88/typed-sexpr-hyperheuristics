(ns niki.optimizer.dsl
  (:require [clojure.edn :as edn])
  (:import [java.io PushbackReader StringReader]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(def grammar-version 1)
(def default-limits {:max-nodes 128 :max-depth 12 :max-count 256
                     :max-source-chars 65536})
(def allowed-effects {:operation #{:read :random :propose}
                      :predicate #{:read :random} :score #{:read}
                      :initializer #{:random} :operator #{:random} :count #{}})
(def ^:private integral-classes #{Byte Short Integer Long clojure.lang.BigInt java.math.BigInteger})
(def ^:private numeric-classes (into integral-classes [Float Double java.math.BigDecimal clojure.lang.Ratio]))
(defn- ensure! [truth message data] (when-not truth (throw (ex-info message data))))
(defn effective-limits [limits]
  (ensure! (map? limits) "Limits must be a map" {})
  (let [hard {:max-nodes 4096 :max-depth 64 :max-count 4096 :max-source-chars 262144}]
    (ensure! (every? #(and (keyword? %) (contains? hard %)) (keys limits)) "Unknown limit" {})
    (let [limits (merge default-limits limits)]
      (doseq [[k ceiling] hard :let [v (get limits k)]]
        (ensure! (and (integral-classes (class v)) (<= 1 v ceiling)) "Invalid limit" {:limit k}))
      (ensure! (and (<= 3 (:max-nodes limits)) (<= 3 (:max-depth limits))) "Limits exclude wrappers" {})
      (update-vals limits long))))

;; role, exact arity, typed child indices, intrinsic effects
(def ^:private forms
  {:noop [:operation 1 [] #{}]
   :seq [:operation 3 [[1 :operation] [2 :operation]] #{}]
   :repeat [:operation 3 [[1 :count] [2 :operation]] #{}]
   :if [:operation 4 [[1 :predicate] [2 :operation] [3 :operation]] #{}]
   :initialize [:operation 2 [[1 :initializer]] #{:propose}]
   :step [:operation 2 [[1 :operator]] #{:propose}]
   :search [:operation 4 [[1 :count] [2 :operator] [3 :score]] #{:propose :read}]
   :count [:count 2 [] #{}] :initializer [:initializer 2 [] #{}]
   :operator [:operator 2 [] #{:random}] :predicate [:predicate 2 [] #{}]
   :not [:predicate 2 [[1 :predicate]] #{}]
   :and [:predicate 3 [[1 :predicate] [2 :predicate]] #{}]
   :less [:predicate 3 [[1 :score] [2 :score]] #{}]
   :constant [:score 2 [] #{}] :feature [:score 2 [] #{:read}]
   :add [:score 3 [[1 :score] [2 :score]] #{}]
   :multiply [:score 3 [[1 :score] [2 :score]] #{}]})

(defn check-program
  ([program] (check-program program {}))
  ([program overrides]
   (let [limits (effective-limits overrides)]
     (ensure! (and (vector? program) (= 3 (count program)) (keyword? (first program)) (= :program (first program))) "Expected program vector" {})
     (let [domain (nth program 1) wrapper (nth program 2)]
       (ensure! (and (keyword? domain) (#{:portfolio :scheduling} domain)) "Unknown domain" {})
       (ensure! (and (vector? wrapper) (= 2 (count wrapper)) (keyword? (first wrapper)) (= :budget (first wrapper))) "Expected budget wrapper" {})
       (let [seen (volatile! 2)]
         (letfn [(walk [node expected path depth]
                   (ensure! (<= depth (:max-depth limits)) "Depth limit exceeded" {:path path})
                   (ensure! (<= (vswap! seen inc) (:max-nodes limits)) "Node limit exceeded" {:path path})
                   (ensure! (and (vector? node) (seq node)) "Expected AST vector" {:path path})
                   (let [op (first node)
                         _ (ensure! (keyword? op) "Expected keyword form tag" {:path path})
                         [role arity children intrinsic] (get forms op)]
                     (ensure! (and role (= expected role) (= arity (count node))) "Form/role/arity mismatch" {:path path})
                     (let [x (second node)
                           node (case op
                                  :count (do (ensure! (and (integral-classes (class x)) (<= 0 x (:max-count limits))) "Invalid Count" {:path path}) [:count (long x)])
                                  :constant (do (ensure! (and (numeric-classes (class x)) (Double/isFinite (double x)) (<= -1000000 x 1000000)) "Invalid Score constant" {:path path}) [:constant (if (zero? (double x)) 0.0 (double x))])
                                  :initializer (do (ensure! (and (keyword? x) (#{:random :first} x)) "Unknown initializer" {:path path}) node)
                                  :operator (do (ensure! (and (keyword? x) ((if (= domain :portfolio) #{:swap :restart} #{:swap :restart :insert :reverse}) x)) "Unknown operator" {:path path}) node)
                                  :feature (do (ensure! (and (keyword? x) (#{:objective-0 :objective-1 :progress} x)) "Unknown feature" {:path path}) node)
                                  :predicate (do (ensure! (and (keyword? x) (#{:archive-changed :budget-left :coin} x)) "Unknown predicate" {:path path}) node)
                                  node)
                           intrinsic (case op :initializer (if (= x :random) #{:random} #{}) :predicate (if (= x :coin) #{:random} #{:read}) intrinsic)
                           compiled (mapv (fn [[i r]] (walk (nth node i) r (conj path i) (inc depth))) children)
                           effects (reduce into intrinsic (map :effects compiled))
                           canonical (reduce (fn [n [[i _] c]] (assoc n i (:node c))) node (map vector children compiled))
                           site {:path path :type [role domain] :effects effects :allowed-effects (get allowed-effects role)}]
                       (ensure! (every? (get allowed-effects role) effects) "Effects exceed allowance" {:path path})
                       {:node canonical :effects effects :depth (reduce max depth (map :depth compiled))
                        :sites (into [site] (mapcat :sites compiled))})))]
           (let [body (walk (second wrapper) :operation [2 1] 3)]
             {:program [:program domain [:budget (:node body)]] :domain domain :node-count @seen
              :depth (:depth body) :sites (:sites body) :limits limits})))))))

(defn replace-subtree [program path replacement limits]
  (let [checked (check-program program limits)]
    (ensure! (and (vector? path) (some #(= path (:path %)) (:sites checked)))
             "Not an editable subtree path" {})
    (:program (check-program (assoc-in (:program checked) path replacement) limits))))

(defn read-program
  ([source] (read-program source {}))
  ([source overrides]
   (let [limits (effective-limits overrides)]
     (ensure! (and (string? source) (<= (count source) (:max-source-chars limits))) "Invalid/oversized source" {})
     ;; This grammar has no quoted strings/comments/tags; reject them before any reader dispatch.
     (ensure! (boolean (re-matches #"[A-Za-z0-9:_+./,\s\[\]-]*" source)) "Disallowed source character" {})
     (loop [i 0 depth 0]
       (if (= i (count source))
         (ensure! (zero? depth) "Unbalanced source" {})
         (let [c (.charAt ^String source i)
               d (+ depth (cond (= c \[) 1 (= c \]) -1 :else 0))]
           (ensure! (<= 0 d (:max-depth limits)) "Unbalanced/too deeply nested source" {})
           (recur (inc i) d))))
     (try
       (with-open [reader (PushbackReader. (StringReader. source))]
         (let [eof (Object.) opts {:eof eof :readers {} :default (fn [& _] (throw (ex-info "Tagged form" {})))}
               p (edn/read opts reader)]
           (ensure! (identical? eof (edn/read opts reader)) "Expected exactly one form" {})
           (:program (check-program p limits))))
       (catch RuntimeException e (throw (ex-info "Invalid DSL source" {} e)))))))

(defn canonical-edn
  ([program] (canonical-edn program {}))
  ([program overrides]
   (binding [*print-length* nil *print-level* nil *print-meta* false
             *print-readably* true *print-dup* false]
     (pr-str (:program (check-program program overrides))))))

(defn program-id
  ([program] (program-id program {}))
  ([program overrides]
   (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String (canonical-edn program overrides) StandardCharsets/UTF_8))]
     (apply str (map #(format "%02x" (bit-and 255 %)) bytes)))))

