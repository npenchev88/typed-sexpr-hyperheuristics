(ns niki.optimizer.shape-validation-test
  "Standalone bounded malformed-shape regressions; never loads dsl-test."
  (:require [clojure.test :as test :refer [deftest is testing]]
            [niki.optimizer.dsl :as dsl]
            [niki.optimizer.variation :as variation]))

(defn program [domain body] [:program domain [:budget body]])
(defn minimal [domain] (program domain [:noop]))
(defn other [domain] (program domain [:step [:operator :swap]]))

(defn poison [touched]
  ;; Bounded: if realized, mark the atom and immediately throw. No infinite seq.
  (lazy-seq (reset! touched true) (throw (Exception. "poison realized"))))

(defn rejects-untouched! [touched f]
  ;; Report only a class/identity label, never the malformed value (printing can realize it).
  (let [outcome (try (f) :returned
                     (catch clojure.lang.ExceptionInfo _ :exception-info)
                     (catch Throwable e (.getName (class e))))]
    (is (= :exception-info outcome) "must reject with ExceptionInfo")
    (is (false? @touched) "must not realize poison")))

(def option-apis
  [[:generate (fn [domain options] (variation/generate-program domain options))]
   [:mutate (fn [domain options] (variation/mutate (minimal domain) options))]
   [:crossover (fn [domain options] (variation/crossover (minimal domain) (other domain) options))]])

(def malformed-key-builders
  ;; Vector pairs and array-map deliberately avoid hashing poison in fixtures.
  [[:lazy identity] [:vector (fn [p] [p])] [:map (fn [p] (array-map :payload p))]
   [:string (constantly "seed")] [:symbol (constantly 'seed)]
   [:number (constantly 7)] [:nil (constantly nil)] [:unknown-keyword (constantly :unknown)]])

(deftest option-key-shape-precedes-membership
  (doseq [domain [:portfolio :scheduling]
          [api-label invoke] option-apis
          [key-label make-key] malformed-key-builders]
    (testing (str domain " " api-label " " key-label)
      (let [touched (atom false) p (poison touched)
            options (array-map (make-key p) 1)]
        (rejects-untouched! touched #(invoke domain options))))))

(deftest nested-limit-key-shape-precedes-membership
  (doseq [domain [:portfolio :scheduling]
          [api-label invoke] (conj option-apis
                                   [:check (fn [d o] (dsl/check-program (minimal d) (:limits o)))]
                                   [:limits (fn [_ o] (dsl/effective-limits (:limits o)))])
          [key-label make-key] malformed-key-builders]
    (testing (str domain " " api-label " " key-label)
      (let [touched (atom false) p (poison touched)
            options (array-map :limits (array-map (make-key p) 1))]
        (rejects-untouched! touched #(invoke domain options))))))

(deftest option-and-limit-roots-reject-without-traversal
  (doseq [domain [:portfolio :scheduling] [api-label invoke] option-apis
          [root-label make-root] [[:lazy identity] [:vector vector] [:list list]
                                 [:nil (constantly nil)] [:number (constantly 7)]]
          nesting [:options :limits]]
    (testing (str domain " " api-label " " root-label " " nesting)
      (let [touched (atom false) p (poison touched)
            root (make-root p)
            options (if (= nesting :limits) (array-map :limits root) root)]
        (rejects-untouched! touched #(invoke domain options))))))

(def parent-apis
  [[:check (fn [_ p] (dsl/check-program p))]
   [:mutate (fn [_ p] (variation/mutate p {}))]
   [:crossover-left (fn [domain p] (variation/crossover p (other domain) {}))]
   [:crossover-right (fn [domain p] (variation/crossover (other domain) p {}))]])

(def keyword-position-builders
  [[:domain (fn [d x] (assoc (minimal d) 1 x))]
   [:body-form-tag (fn [d x] (program d [x]))]
   [:nested-operation-tag (fn [d x] (program d [:seq [:noop] [x]]))]
   [:nested-count-tag (fn [d x] (program d [:repeat [x 1] [:noop]]))]
   [:nested-initializer-tag (fn [d x] (program d [:initialize [x :first]]))]
   [:nested-operator-tag (fn [d x] (program d [:step [x :swap]]))]
   [:nested-score-tag (fn [d x] (program d [:search [:count 1] [:operator :swap] [x :progress]]))]
   [:nested-predicate-tag (fn [d x] (program d [:if [x :coin] [:noop] [:noop]]))]
   [:initializer-payload (fn [d x] (program d [:initialize [:initializer x]]))]
   [:operator-payload (fn [d x] (program d [:step [:operator x]]))]
   [:feature-payload (fn [d x] (program d [:search [:count 1] [:operator :swap]
                                               [:add [:constant 0] [:feature x]]]))]
   [:predicate-payload (fn [d x] (program d [:if [:not [:predicate x]] [:noop] [:noop]]))]])

(deftest ast-keyword-shape-precedes-lookup
  (doseq [domain [:portfolio :scheduling]
          [api-label invoke] parent-apis
          [position make-program] keyword-position-builders
          [shape make-token] malformed-key-builders]
    (testing (str domain " " api-label " " position " " shape)
      (let [touched (atom false) p (poison touched)
            malformed (make-program domain (make-token p))]
        (rejects-untouched! touched #(invoke domain malformed))))))

(deftest ast-roots-wrappers-and-numeric-payloads-reject-without-traversal
  (doseq [domain [:portfolio :scheduling] [api-label invoke] parent-apis
          [position make-program]
          [[:root (fn [_ p] p)]
           [:nonvector-root (fn [_ p] (array-map :payload p))]
           [:short-root (fn [_ p] [p])]
           [:program-tag (fn [d p] [p d [:budget [:noop]]])]
           [:wrapper (fn [d p] [:program d p])]
           [:wrapper-tag (fn [d p] [:program d [p [:noop]]])]
           [:body (fn [d p] (program d p))]
           [:nested-body (fn [d p] (program d [:seq [:noop] p]))]
           [:count-payload (fn [d p] (program d [:repeat [:count p] [:noop]]))]
           [:constant-payload (fn [d p] (program d [:search [:count 1] [:operator :swap]
                                                         [:constant p]]))]]]
    (testing (str domain " " api-label " " position)
      (let [touched (atom false) p (poison touched)]
        (rejects-untouched! touched #(invoke domain (make-program domain p)))))))

(deftest generation-domain-shape-rejects-without-traversal
  (doseq [[shape make-token] malformed-key-builders]
    (testing (str "generation domain " shape)
      (let [touched (atom false) p (poison touched)]
        (rejects-untouched! touched #(variation/generate-program (make-token p) {}))))))

(defn -main [& [slice]]
  (println "Clojure" (clojure-version) "standalone shape validation" (or slice "all"))
  (let [vars (case slice
               "slice1" [#'option-key-shape-precedes-membership
                         #'nested-limit-key-shape-precedes-membership
                         #'option-and-limit-roots-reject-without-traversal]
               "slice2" [#'ast-keyword-shape-precedes-lookup
                         #'ast-roots-wrappers-and-numeric-payloads-reject-without-traversal
                         #'generation-domain-shape-rejects-without-traversal]
               (->> (ns-publics 'niki.optimizer.shape-validation-test)
                    vals (filter #(-> % meta :test)) (sort-by #(-> % meta :name))))
        result (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                 (test/test-vars vars)
                 (let [r @test/*report-counters*]
                   (test/do-report (assoc r :type :summary)) r))]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
