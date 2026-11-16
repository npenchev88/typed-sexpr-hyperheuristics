(ns niki.optimizer.dsl-test
  (:require [clojure.test :refer [deftest is testing]]
            [niki.optimizer.dsl :as dsl]))

(def minimal [:program :portfolio [:budget [:noop]]])
(defn call [name & args]
  (when-let [f (ns-resolve 'niki.optimizer.dsl name)] (apply f args)))

(deftest minimal-checked-program
  (let [p (call 'check-program minimal)]
    (is (= minimal (:program p)))
    (is (= :portfolio (:domain p)))
    (is (= 3 (:node-count p)))
    (is (= 3 (:depth p)))
    (is (= [{:path [2 1] :type [:operation :portfolio]
             :effects #{} :allowed-effects #{:read :random :propose}}]
           (:sites p)))))

(def complex-program
  [:program :portfolio
   [:budget
    [:seq [:initialize [:initializer :random]]
     [:if [:and [:predicate :coin] [:not [:predicate :archive-changed]]]
      [:repeat [:count 2]
       [:search [:count 3] [:operator :swap]
        [:add [:feature :objective-0]
         [:multiply [:constant 0.5] [:feature :objective-1]]]]]
      [:step [:operator :restart]]]]]])

(deftest compositional-grammar-and-effects
  (let [r (call 'check-program complex-program)
        body (first (:sites r))
        vectors (tree-seq vector? #(filter vector? %) complex-program)]
    (is (= complex-program (:program r)))
    (is (= (count vectors) (:node-count r)))
    (is (= #{:read :random :propose} (:effects body)))
    (is (= [:operation :portfolio] (:type body)))
    (is (= 2 (get-in (:program r) [2 1 2 2 1 1])))))

(deftest serialization-is-bounded-and-closed
  (is (= minimal (call 'read-program (pr-str minimal))))
  (is (= (pr-str minimal) (call 'canonical-edn minimal)))
  (is (re-matches #"[a-f0-9]{64}" (or (call 'program-id minimal) "")))
  (is (= (call 'program-id minimal)
         (binding [*print-length* 1 *print-level* 1] (call 'program-id minimal))))
  (doseq [s ["" "[:program :portfolio [:budget [:noop]]] [:noop]"
             "#=(System/exit 0)" "#inst \"2020-01-01\"" "#uuid \"a\""
             "[:program :portfolio [:budget (noop)]]"
             "[:program :portfolio [:budget [:noop]]] ;comment"
             (str (apply str (repeat 65 "[")) (apply str (repeat 65 "]")))]]
    (is (thrown? clojure.lang.ExceptionInfo (call 'read-program s))))
  (is (thrown? clojure.lang.ExceptionInfo (call 'read-program (pr-str minimal) {:max-source-chars 10}))))

(deftest canonical-serialization-honors-count-limits
  (let [p [:program :portfolio [:budget [:repeat [:count 257] [:noop]]]]
        limits {:max-count 257}
        deep (reduce (fn [body _] [:seq [:noop] body]) [:noop] (range 11))
        deep-p [:program :portfolio [:budget deep]]
        deep-limits {:max-depth 14 :max-nodes 30}]
    (is (= p (:program (dsl/check-program p limits))))
    (is (= p (dsl/read-program (pr-str p) limits)))
    (is (thrown? clojure.lang.ExceptionInfo (dsl/canonical-edn p)))
    (is (thrown? clojure.lang.ExceptionInfo (dsl/program-id p)))
    (is (thrown? clojure.lang.ExceptionInfo (dsl/read-program (pr-str p))))
    (is (= (pr-str p) (dsl/canonical-edn p limits)))
    (is (re-matches #"[a-f0-9]{64}" (dsl/program-id p limits)))
    (is (= p (dsl/read-program (dsl/canonical-edn p limits) limits)))
    (is (thrown? clojure.lang.ExceptionInfo (dsl/canonical-edn deep-p)))
    (is (thrown? clojure.lang.ExceptionInfo (dsl/program-id deep-p)))
    (is (= (pr-str deep-p) (dsl/canonical-edn deep-p deep-limits)))
    (is (re-matches #"[a-f0-9]{64}" (dsl/program-id deep-p deep-limits)))
    (is (= (dsl/canonical-edn minimal) (dsl/canonical-edn minimal {})))
    (is (= (dsl/program-id minimal) (dsl/program-id minimal {})))
    (is (= (dsl/canonical-edn complex-program) (dsl/canonical-edn complex-program {})))
    (is (= (dsl/program-id complex-program) (dsl/program-id complex-program {})))
    (doseq [bad [{:max-nodes 2} {:max-depth 65} {:max-count 0} {:unknown 1} nil]]
      (is (thrown? clojure.lang.ExceptionInfo (dsl/canonical-edn minimal bad)))
      (is (thrown? clojure.lang.ExceptionInfo (dsl/program-id minimal bad))))))

(deftest shape-numeric-and-structural-limits
  (let [touched (atom false) poison (lazy-seq (reset! touched true) (throw (Exception. "realized")))]
    (is (thrown? clojure.lang.ExceptionInfo (call 'check-program poison)))
    (is (false? @touched)))
  (doseq [limits [{:max-nodes 2} {:max-depth 65} {:max-count 0} {:unknown 1} {:max-nodes 1.5}]]
    (is (thrown? clojure.lang.ExceptionInfo (call 'check-program minimal limits))))
  (doseq [x [##NaN ##Inf -1000001 (java.util.concurrent.atomic.DoubleAdder.)]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (call 'check-program [:program :portfolio [:budget [:search [:count 1] [:operator :swap] [:constant x]]]]))))
  (is (thrown? clojure.lang.ExceptionInfo (call 'check-program complex-program {:max-nodes 4})))
  (is (thrown? clojure.lang.ExceptionInfo (call 'check-program complex-program {:max-depth 4})))
  (is (thrown? clojure.lang.ExceptionInfo
               (call 'check-program [:program :portfolio [:budget [:repeat [:count 257] [:noop]]]]))))

(deftest substitution-preserves-context-and-protects-wrappers
  (let [p (call 'replace-subtree minimal [2 1] [:step [:operator :swap]] {})]
    (is (= [:program :portfolio [:budget [:step [:operator :swap]]]] p))
    (is (= :portfolio (:domain (call 'check-program p)))))
  (doseq [[path replacement] [[[] [:noop]] [[2] [:budget [:noop]]]
                              [[2 1 5] [:noop]] [[2 1] [:constant 2]]]]
    (is (thrown? clojure.lang.ExceptionInfo (call 'replace-subtree minimal path replacement {}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (call 'replace-subtree minimal [2 1] [:step [:operator :swap]] {:max-nodes 3})))
  (is (thrown? clojure.lang.ExceptionInfo
               (call 'replace-subtree minimal [2 1] [:step [:operator :insert]] {}))))

(deftest grammar-rejects-wrong-roles-and-arity
  (doseq [p [[:program :alien [:budget [:noop]]]
             [:program :portfolio [:budget [:seq [:noop]]]]
             [:program :portfolio [:budget [:repeat [:constant 1] [:noop]]]]
             [:program :portfolio [:budget [:step [:operator :insert]]]]
             [:program :portfolio [:budget [:initialize [:initializer :greedy]]]]
             [:program :portfolio [:budget [:if [:constant 1] [:noop] [:noop]]]]
             [:program :portfolio [:budget [:noop :extra]]]]]
    (is (thrown? clojure.lang.ExceptionInfo (call 'check-program p))))
  (is (= :scheduling (:domain (call 'check-program
                        [:program :scheduling [:budget [:step [:operator :insert]]]])))))

