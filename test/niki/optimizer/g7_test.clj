(ns niki.optimizer.g7-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [niki.optimizer.portfolio :as portfolio]
            [niki.optimizer.portfolio-reference :as port-ref]
            [niki.optimizer.portfolio-runtime :as port-runtime]
            [niki.optimizer.portfolio-data :as port-data]
            [niki.optimizer.scheduling :as scheduling]
            [niki.optimizer.scheduling-reference :as sched-ref]
            [niki.optimizer.scheduling-runtime :as sched-runtime]
            [niki.optimizer.pareto :as pareto]
            [niki.optimizer.baselines :as baselines]
            [niki.optimizer.search :as search]
            [niki.optimizer.stats :as stats]))

(deftest statistical-analysis-paired-bootstrap-and-holm-bonferroni
  (let [paired-runs
        (mapv (fn [r]
                {:domain :portfolio
                 :run-seed r
                 :outcomes {:gp-mixed (+ 0.72 (* 0.001 (mod r 5)))
                            :random-dsl (+ 0.65 (* 0.001 (mod r 5)))
                            :formula-only (+ 0.718 (* 0.001 (mod r 5)))
                            :gp-mutation-only (+ 0.69 (* 0.002 (mod r 7)))
                            :configured-conventional (+ 0.75 (* 0.001 (mod r 5)))}})
              (range 30))
        res1 (stats/analyze-paired-runs paired-runs {:resamples 2000 :seed 99})
        res2 (stats/analyze-paired-runs paired-runs {:resamples 2000 :seed 99})
        contrasts (:contrasts res1)
        by-h (into {} (map (juxt :hypothesis identity) contrasts))]
    ;; Deterministic replay of bootstrap analysis
    (is (= res1 res2))
    (is (= 30 (:n-runs res1)))
    (is (= 4 (count contrasts)))
    ;; H1: +0.070 difference with zero variance -> practically superior
    (is (< 0.069 (:mean-difference (:H1 by-h)) 0.071))
    (is (= :practically-superior (:decision (:H1 by-h))))
    (is (true? (:null-rejected? (:H1 by-h))))
    ;; H2: +0.002 difference inside [-0.01, +0.01] -> practically equivalent
    (is (< 0.001 (:mean-difference (:H2 by-h)) 0.003))
    (is (= :practically-equivalent (:decision (:H2 by-h))))
    ;; H4: -0.030 difference -> practically inferior
    (is (< -0.031 (:mean-difference (:H4 by-h)) -0.029))
    (is (= :practically-inferior (:decision (:H4 by-h))))
    ;; Holm step-down adjusted p-values must be non-decreasing by holm-rank
    (let [ordered-p (mapv :holm-adjusted-p (sort-by :holm-rank contrasts))]
      (is (every? true? (map <= ordered-p (rest ordered-p))))
      (is (every? #(<= 0.0 % 1.0) ordered-p))))
  ;; Temporal block bootstrap check
  (let [matrix (mapv (fn [r]
                       (mapv (fn [t] (+ 0.03 (* 0.002 (Math/sin (+ r t)))))
                             (range 24)))
                     (range 15))
        tb1 (stats/temporal-block-bootstrap matrix {:block-length 3 :resamples 500 :seed 42})
        tb2 (stats/temporal-block-bootstrap matrix {:block-length 3 :resamples 500 :seed 42})]
    (is (= tb1 tb2))
    (is (= 15 (:n-runs tb1)))
    (is (= 24 (:n-tasks tb1)))
    (is (< 0.01 (first (:ci tb1)) (second (:ci tb1)) 0.05))))

(deftest frontier-headroom-on-exact-portfolio-and-scheduling-fixtures
  ;; Verify that neither exact fixture is degenerate and that simple initializer/random
  ;; baselines leave non-zero headroom below the exact Pareto front.
  (let [port-panel (edn/read-string (slurp (io/resource "fixtures/g1-portfolio.edn")))
        port-inst (portfolio/prepare-instance port-panel)
        port-norm (pareto/portfolio-normalization port-inst)
        port-adapter (port-runtime/make-adapter port-inst)
        port-all (mapv #(:objectives (portfolio/evaluate port-inst %))
                       (port-ref/selections (count (:assets port-panel)) (:k port-panel)))
        port-exact-hv (pareto/normalized-hypervolume (pareto/front port-all) port-norm)
        port-init (baselines/run-direct-control :initializer-only port-adapter {:solution-budget 1 :seed 0})
        port-init-hv (pareto/normalized-hypervolume (:archive port-init) port-norm)
        sched-panel (edn/read-string (slurp (io/resource "fixtures/g6-scheduling.edn")))
        sched-inst (scheduling/prepare-instance sched-panel)
        sched-norm (scheduling/scheduling-normalization sched-inst)
        sched-adapter (sched-runtime/make-adapter sched-inst)
        sched-all (mapv #(:objectives (scheduling/evaluate sched-inst %))
                        (sched-ref/permutations (count (:jobs sched-panel))))
        sched-exact-hv (pareto/normalized-hypervolume (pareto/front sched-all) sched-norm)
        sched-init (baselines/run-direct-control :initializer-only sched-adapter {:solution-budget 1 :seed 0})
        sched-init-hv (pareto/normalized-hypervolume (:archive sched-init) sched-norm)]
    (is (< 0.0 port-init-hv port-exact-hv 1.0))
    (is (> (- port-exact-hv port-init-hv) 0.05))
    (is (< 0.0 sched-init-hv sched-exact-hv 1.0))
    (is (> (- sched-exact-hv sched-init-hv) 0.05))))

(deftest smoke-proposal-protocol-and-pass-gates
  (let [smoke-cfg (edn/read-string (slurp "configs/smoke-proposal.edn"))
        port-suite (port-data/generate-synthetic-suite {:n 12 :k 4 :t 15 :base-seed 500})
        port-tasks (mapv (fn [t]
                           {:task-id (:task-id t)
                            :adapter (port-runtime/make-adapter (portfolio/prepare-instance (:panel t)))})
                         (take 2 (:train port-suite)))
        sched-suite (scheduling/generate-benchmark-suite {:jobs 12 :base-seed 600})
        sched-tasks (mapv (fn [inst]
                            {:task-id (:instance-id inst)
                             :adapter (sched-runtime/make-adapter (scheduling/prepare-instance inst))})
                          (take 2 (:train sched-suite)))]
    (is (= 32 (get-in smoke-cfg [:outer :assessment-slots])))
    (is (= 4096 (:maximum-solution-proposals smoke-cfg)))
    (doseq [[domain tasks] [[:portfolio port-tasks] [:scheduling sched-tasks]]
            arm [:gp-mixed :gp-mutation-only :random-dsl :formula-only :configured-conventional]]
      (let [spec {:arm arm
                  :domain domain
                  :run-seed 1
                  :outer (:outer smoke-cfg)
                  :train {:tasks tasks
                          :inner-seeds [0]
                          :solution-proposals (get-in smoke-cfg [:train :solution-proposals])
                          :step-budget 2048}}
            run1 (search/run-arm spec)
            run2 (search/run-arm spec)]
        ;; Pass-gate 1: deterministic-replay
        (is (= (:slot-ledger run1) (:slot-ledger run2)))
        (is (= (:selected run1) (:selected run2)))
        ;; Pass-gate 2: budget-conservation (32 slots * 2 tasks * 1 seed * 64 proposals <= 4096)
        (is (= 32 (get-in run1 [:slot-ledger :slots-used])))
        (is (<= 0.0 (get-in run1 [:selected :validation-mean-hv]) 1.0))
        (is (Double/isFinite (double (get-in run1 [:selected :validation-mean-hv]))))))))
