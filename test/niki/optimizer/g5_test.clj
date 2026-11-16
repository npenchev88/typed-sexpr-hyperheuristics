(ns niki.optimizer.g5-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [niki.optimizer.portfolio :as portfolio]
            [niki.optimizer.portfolio-runtime :as port-runtime]
            [niki.optimizer.portfolio-data :as port-data]
            [niki.optimizer.scheduling :as scheduling]
            [niki.optimizer.scheduling-runtime :as sched-runtime]
            [niki.optimizer.baselines :as baselines]
            [niki.optimizer.search :as search]
            [niki.optimizer.pareto :as pareto]
            [niki.optimizer.dsl :as dsl]))

(deftest point-in-time-and-cross-split-footprint-audits
  (let [suite (port-data/generate-synthetic-suite {:n 10 :k 3 :t 15 :base-seed 77})
        audit (port-data/audit-split-footprints suite)]
    (is (true? (:point-in-time-valid? audit)))
    (is (true? (:cross-split-disjoint? audit)))
    (is (= {:train 6 :validation 6 :test 24} (:counts audit)))
    ;; Future-information leakage must fail closed
    (let [leaked (assoc-in suite [:train 0 :decision-date] "2018-01-05")]
      (is (thrown? clojure.lang.ExceptionInfo (port-data/audit-split-footprints leaked))))
    ;; Cross-split overlap between train and validation must fail closed
    (let [overlapped (assoc-in suite [:validation 0 :start-date] "2018-01-10")]
      (is (thrown? clojure.lang.ExceptionInfo (port-data/audit-split-footprints overlapped))))
    ;; Cross-split overlap between validation and test must fail closed
    (let [overlapped-test (assoc-in suite [:test 0 :start-date] "2021-01-10")]
      (is (thrown? clojure.lang.ExceptionInfo (port-data/audit-split-footprints overlapped-test))))))

(deftest conventional-families-and-direct-controls-conserve-budgets-and-replay
  (let [port-panel (edn/read-string (slurp (io/resource "fixtures/g1-portfolio.edn")))
        port-inst (portfolio/prepare-instance port-panel)
        port-adapter (port-runtime/make-adapter port-inst)
        sched-panel (edn/read-string (slurp (io/resource "fixtures/g6-scheduling.edn")))
        sched-inst (scheduling/prepare-instance sched-panel)
        sched-adapter (sched-runtime/make-adapter sched-inst)]
    (doseq [[domain adapter validate-fn]
            [[:portfolio port-adapter #(portfolio/validate-selection port-inst %)]
             [:scheduling sched-adapter #(scheduling/validate-schedule sched-inst %)]]]
      ;; Test all three conventional solver families (both :cache? true and :cache? false)
      (doseq [seed (range 12)
              budget [0 1 8 24]]
        (let [{:keys [config]} (baselines/sample-conventional-config domain {:seed seed})
              r1 (baselines/run-conventional config adapter {:solution-budget budget :seed seed :cache? true})
              r2 (baselines/run-conventional config adapter {:solution-budget budget :seed seed :cache? true})
              r-nocache (baselines/run-conventional config adapter {:solution-budget budget :seed seed :cache? false})]
          (is (= (dissoc r1 :timing) (dissoc r2 :timing)))
          (is (= (:archive r1) (:archive r-nocache)))
          (is (= (:archive-solutions r1) (:archive-solutions r-nocache)))
          (is (zero? (get-in r-nocache [:charged-costs :cache-hits])))
          (is (<= 0 (get-in r1 [:charged-costs :proposal-attempts]) budget))
          (is (= (count (:archive r1)) (count (:archive-solutions r1))))
          (doseq [s (:archive-solutions r1)]
            (is (= s (validate-fn s))))))
      ;; Test all three direct deployment controls
      (doseq [ctrl [:initializer-only :random-search :heuristic-local-search]
              budget [1 12]]
        (let [r1 (baselines/run-direct-control ctrl adapter {:solution-budget budget :seed 19})
              r2 (baselines/run-direct-control ctrl adapter {:solution-budget budget :seed 19})]
          (is (= (dissoc r1 :timing) (dissoc r2 :timing)))
          (is (= (if (= ctrl :initializer-only) 1 budget)
                 (get-in r1 [:charged-costs :proposal-attempts])))
          (doseq [s (:archive-solutions r1)]
            (is (= s (validate-fn s)))))))))

(deftest formula-only-generator-and-mutator-preserve-fixed-scaffold
  (doseq [domain [:portfolio :scheduling]
          seed (range 25)]
    (let [g (search/generate-formula-program domain {:seed seed})
          p (:program g)
          m (search/mutate-formula-program p {:seed (+ 1000 seed)})
          mp (:program m)
          expected-scaffold (search/formula-scaffold domain [:constant 0.0])]
      (is (= p (:program (dsl/check-program p))))
      (is (= mp (:program (dsl/check-program mp))))
      ;; Outside the score expression at [2 1 2 2 3], the scaffold must be identical
      (is (= expected-scaffold (assoc-in p search/formula-score-root-path [:constant 0.0])))
      (is (= expected-scaffold (assoc-in mp search/formula-score-root-path [:constant 0.0]))))))

(deftest five-search-arms-enforce-equal-slot-ledger-and-shared-initial-populations
  (let [port-panel (edn/read-string (slurp (io/resource "fixtures/g1-portfolio.edn")))
        port-adapter (port-runtime/make-adapter (portfolio/prepare-instance port-panel))
        sched-panel (edn/read-string (slurp (io/resource "fixtures/g6-scheduling.edn")))
        sched-adapter (sched-runtime/make-adapter (scheduling/prepare-instance sched-panel))]
    (doseq [[domain adapter] [[:portfolio port-adapter] [:scheduling sched-adapter]]]
      (let [spec {:domain domain
                  :run-seed 42
                  :outer {:initial-population 4
                          :offspring-batches 2
                          :offspring-per-batch 4
                          :assessment-slots 12
                          :mixed-crossover-probability 0.5}
                  :train {:tasks [{:task-id "t0" :adapter adapter}]
                          :inner-seeds [1 2]
                          :solution-proposals 16
                          :step-budget 512}
                  :validation {:max-finalists 4
                               :tasks [{:task-id "v0" :adapter adapter}]
                               :inner-seeds [3 4]
                               :solution-proposals 16
                               :step-budget 512}
                  :test {:tasks [{:task-id "test0" :adapter adapter}]
                         :inner-seeds [5 6 7]
                         :solution-proposals 16
                         :step-budget 512}}
            results (into {}
                          (map (fn [arm]
                                 [arm (search/run-arm (assoc spec :arm arm))])
                               [:gp-mixed :gp-mutation-only :random-dsl :formula-only :configured-conventional]))]
        ;; 1. Every arm uses the exact same assessment-slot allowance (12 slots)
        (doseq [[arm res] results]
          (is (= 12 (get-in res [:slot-ledger :assessment-slots])))
          (is (= 12 (get-in res [:slot-ledger :slots-used])))
          (is (= 12 (count (:slots res))))
          (is (<= 1 (get-in res [:slot-ledger :distinct-candidates]) 12))
          (is (<= 0.0 (get-in res [:selected :validation-mean-hv]) 1.0))
          (is (<= 0.0 (get-in res [:test-evaluation :mean-normalized-hv]) 1.0))
          ;; Replay check
          (is (= (update-in res [:test-evaluation :totals] dissoc :elapsed-ns)
                 (update-in (search/run-arm (assoc spec :arm arm))
                            [:test-evaluation :totals] dissoc :elapsed-ns))))
        ;; 2. Shared initial population between :gp-mixed, :gp-mutation-only, and :random-dsl
        (let [init-ids (fn [arm] (mapv :candidate-id (take 4 (get-in results [arm :slots]))))]
          (is (= (init-ids :gp-mixed) (init-ids :gp-mutation-only) (init-ids :random-dsl))))
        ;; 3. Verify no cross-seed archive pooling on test evaluation:
        ;; mean-normalized-hv must equal the arithmetic mean of the 3 individual seed normalized-hvs
        (doseq [[_ res] results
                task-out (get-in res [:test-evaluation :task-outcomes])]
          (let [seed-hvs (mapv :normalized-hv (:seed-runs task-out))
                expected-mean (/ (reduce + 0.0 seed-hvs) (double (count seed-hvs)))]
            (is (= 3 (count seed-hvs)))
            (is (<= (Math/abs (- expected-mean (:mean-normalized-hv task-out))) 1.0e-12))))))))

(deftest budget-aware-v2-generator-and-mutator-preserve-invariants
  (doseq [domain [:portfolio :scheduling]
          seed (range 25)]
    (let [g (search/generate-program-v2 domain {:seed seed})
          p (:program g)
          m (search/mutate-program-v2 p {:seed (+ 1000 seed)})
          mp (:program m)]
      (is (= :budget-aware-v2 (:distribution g)))
      (is (= p (:program (dsl/check-program p))))
      (is (= mp (:program (dsl/check-program mp))))
      ;; Never generates a degenerate root [:noop]
      (is (not= [:noop] (get-in p [2 1]))))))
