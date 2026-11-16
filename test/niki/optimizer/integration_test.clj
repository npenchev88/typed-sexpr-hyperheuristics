(ns niki.optimizer.integration-test
  "Bounded synthetic component checks, not optimizer performance experiments."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [niki.optimizer.portfolio :as portfolio]
            [niki.optimizer.portfolio-reference :as reference]
            [niki.optimizer.portfolio-runtime :as adapter]
            [niki.optimizer.runtime :as runtime]
            [niki.optimizer.variation :as variation]
            [niki.optimizer.pareto :as pareto]))

(def handwritten
  [:program :portfolio
   [:budget
    [:seq [:initialize [:initializer :random]]
     [:repeat [:count 4]
      [:if [:predicate :coin]
       [:search [:count 3] [:operator :swap]
        [:add [:feature :objective-0]
         [:multiply [:constant 0.5] [:feature :objective-1]]]]
       [:step [:operator :restart]]]]]]])

(deftest varied-programs-execute-with-bounded-replayable-archives
  (let [panel (edn/read-string (slurp (io/resource "fixtures/g1-portfolio.edn")))
        instance (portfolio/prepare-instance panel)
        oracle (reference/prepare-reference panel)
        observed-calls (atom 0)
        prepared (adapter/make-adapter instance)
        real-evaluate (:evaluate prepared)
        prepared (assoc prepared :evaluate (fn [s] (swap! observed-calls inc) (real-evaluate s)))]
    (doseq [seed (range 20)
            :let [p (:program (variation/generate-program :portfolio {:seed seed}))
                  m (:program (variation/mutate p {:seed seed}))
                  children (:children (variation/crossover p handwritten {:seed seed}))]
            ast (into [handwritten p m] children)]
      (let [options {:solution-budget 12 :step-budget 512 :seed seed}
            before @observed-calls
            result (runtime/run ast prepared options)
            actual-calls (- @observed-calls before)
            repeated (runtime/run ast prepared options)
            quiet (runtime/run ast prepared (assoc options :trace? false))
            costs (:charged-costs result)
            candidates (filterv #(= :candidate (:event %)) (:trace result))
            traced-front (reduce #(pareto/insert %1 (:objectives %2)) [] candidates)]
        (is (contains? #{:completed :proposal-budget-exhausted :step-budget-exhausted} (:status result)))
        (is (= (dissoc result :timing) (dissoc repeated :timing)))
        (is (= (apply dissoc result [:timing :trace :trace-truncated? :trace-dropped])
               (apply dissoc quiet [:timing :trace :trace-truncated? :trace-dropped])))
        (is (<= 0 (:proposal-attempts costs) 12))
        (is (<= 0 (:interpreter-steps costs) 512))
        (is (<= (:unique-candidates costs) (:valid-candidates costs) (:proposal-attempts costs)))
        (is (= actual-calls (:objective-calculations costs) (:unique-candidates costs)))
        (is (= (:valid-candidates costs) (+ (:cache-hits costs) (:objective-calculations costs))))
        (is (= (:valid-candidates costs) (count candidates)))
        (is (false? (:trace-truncated? result)))
        (is (= traced-front (:archive result)))
        (is (= (:archive result) (pareto/front (:archive result))))
        (is (= (count (:archive result)) (count (:archive-solutions result))))
        (doseq [[point solution] (map vector (:archive result) (:archive-solutions result))]
          (is (= solution (portfolio/validate-selection instance solution)))
          (let [expected (:objectives (reference/evaluate oracle solution))]
            (is (every? true? (map #(<= (Math/abs (double (- %1 %2))) 1.0e-12) point expected))))
          (is (every? #(<= 0.0 % 1.0) (pareto/normalize-point (:normalization prepared) point))))
        (let [fronts (reductions #(pareto/insert %1 (:objectives %2)) [] candidates)
              areas (map #(pareto/normalized-hypervolume % (:normalization prepared)) fronts)]
          (is (every? (fn [[a b]] (<= a b)) (partition 2 1 areas))))))))
