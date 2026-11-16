;; Two outer replications (run-seed = 0, 1) comparing:
;;   1. All 5 primary search arms under :budget-aware-v2
;;   2. Generator-prior ablation (:budget-aware-v2 vs :development-only-v1) on :gp-mixed, :gp-mutation-only, :random-dsl
;;   3. Direct deployment controls (:initializer-only, :random-search, :heuristic-local-search)
;; Reuses the verified run-seed = 0 :development-only-v1 / baseline runs from
;; docs/evidence/single-replication-results.edn and computes both outer-replication
;; and task-replication paired bootstrap statistics via niki.optimizer.stats/analyze-paired-runs.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-runtime :as port-runtime]
         '[niki.optimizer.portfolio-data :as port-data]
         '[niki.optimizer.scheduling :as scheduling]
         '[niki.optimizer.scheduling-runtime :as sched-runtime]
         '[niki.optimizer.pareto :as pareto]
         '[niki.optimizer.baselines :as baselines]
         '[niki.optimizer.search :as search]
         '[niki.optimizer.stats :as stats])
(import '[java.time Instant])

(defn prepare-portfolio-split [tasks]
  (mapv (fn [t]
          {:task-id (:task-id t)
           :adapter (port-runtime/make-adapter (portfolio/prepare-instance (:panel t)))})
        tasks))

(defn prepare-scheduling-split [instances]
  (mapv (fn [inst]
          {:task-id (:instance-id inst)
           :adapter (sched-runtime/make-adapter (scheduling/prepare-instance inst))})
        instances))

(defn compact-run-summary [res elapsed-s prior]
  {:arm (:arm res)
   :generator-prior prior
   :run-seed (:run-seed res)
   :elapsed-seconds elapsed-s
   :slot-ledger (:slot-ledger res)
   :selected (:selected res)
   :test-mean-normalized-hv (get-in res [:test-evaluation :mean-normalized-hv])
   :test-task-means (mapv #(select-keys % [:task-id :mean-normalized-hv])
                          (get-in res [:test-evaluation :task-outcomes]))
   :test-totals (get-in res [:test-evaluation :totals])})

(defn hydrate-rep0-from-existing [existing-arm-res test-tasks test-seeds prior]
  (let [arm (:arm existing-arm-res)
        sel (:selected existing-arm-res)
        test-eval (search/assess-candidate arm (:candidate sel) (:candidate-id sel)
                                           test-tasks test-seeds
                                           {:solution-budget 256 :step-budget 4096 :failure-score 0.0})]
    {:arm arm
     :generator-prior prior
     :run-seed 0
     :elapsed-seconds (:elapsed-seconds existing-arm-res)
     :slot-ledger (:slot-ledger existing-arm-res)
     :selected sel
     :test-mean-normalized-hv (:test-mean-normalized-hv existing-arm-res)
     :test-task-means (mapv #(select-keys % [:task-id :mean-normalized-hv])
                            (:task-outcomes test-eval))
     :test-totals (:test-totals existing-arm-res)}))

(let [started-ns (System/nanoTime)
      _ (println "Preparing N=50, K=5, T=365 portfolio suite and 30-job scheduling suite...")
      port-raw (port-data/generate-synthetic-suite {:n 50 :k 5 :t 365 :base-seed 20261108})
      sched-raw (scheduling/generate-benchmark-suite {:jobs 30 :base-seed 20261108})
      existing-rep0 (edn/read-string (slurp "docs/evidence/single-replication-results.edn"))
      domains
      {:portfolio {:train (prepare-portfolio-split (:train port-raw))
                   :validation (prepare-portfolio-split (:validation port-raw))
                   :test (prepare-portfolio-split (:test port-raw))}
       :scheduling {:train (prepare-scheduling-split (:train sched-raw))
                    :validation (prepare-scheduling-split (:validation sched-raw))
                    :test (prepare-scheduling-split (:test sched-raw))}}
      train-seeds [101 102]
      val-seeds [201 202]
      test-seeds (vec (range 1001 1011))
      base-outer {:initial-population 32
                  :offspring-batches 7
                  :offspring-per-batch 32
                  :assessment-slots 256
                  :mixed-crossover-probability 0.5}
      all-configs
      [[:gp-mixed :budget-aware-v2]
       [:gp-mutation-only :budget-aware-v2]
       [:random-dsl :budget-aware-v2]
       [:formula-only :budget-aware-v2]
       [:configured-conventional :budget-aware-v2]
       [:gp-mixed :development-only-v1]
       [:gp-mutation-only :development-only-v1]
       [:random-dsl :development-only-v1]]
      ;; Only run jobs not already completed in single-replication-results.edn
      jobs-to-run
      [{:run-seed 0 :arm :gp-mixed :prior :budget-aware-v2}
       {:run-seed 0 :arm :gp-mutation-only :prior :budget-aware-v2}
       {:run-seed 0 :arm :random-dsl :prior :budget-aware-v2}
       {:run-seed 1 :arm :gp-mixed :prior :budget-aware-v2}
       {:run-seed 1 :arm :gp-mutation-only :prior :budget-aware-v2}
       {:run-seed 1 :arm :random-dsl :prior :budget-aware-v2}
       {:run-seed 1 :arm :formula-only :prior :budget-aware-v2}
       {:run-seed 1 :arm :configured-conventional :prior :budget-aware-v2}
       {:run-seed 1 :arm :gp-mixed :prior :development-only-v1}
       {:run-seed 1 :arm :gp-mutation-only :prior :development-only-v1}
       {:run-seed 1 :arm :random-dsl :prior :development-only-v1}]
      domain-results
      (into {}
            (for [[domain splits] domains]
              (do
                (println "Running new outer replications for domain:" domain "(" (count jobs-to-run) "jobs )...")
                (let [rep0-by-arm (into {}
                                        (map (juxt :arm identity)
                                             (get-in existing-rep0 [:domains domain :arm-results])))
                      reused-rep0
                      [(hydrate-rep0-from-existing (rep0-by-arm :gp-mixed) (:test splits) test-seeds :development-only-v1)
                       (hydrate-rep0-from-existing (rep0-by-arm :gp-mutation-only) (:test splits) test-seeds :development-only-v1)
                       (hydrate-rep0-from-existing (rep0-by-arm :random-dsl) (:test splits) test-seeds :development-only-v1)
                       (hydrate-rep0-from-existing (rep0-by-arm :formula-only) (:test splits) test-seeds :budget-aware-v2)
                       (hydrate-rep0-from-existing (rep0-by-arm :configured-conventional) (:test splits) test-seeds :budget-aware-v2)]
                      new-runs
                      (vec
                       (pmap
                        (fn [{:keys [run-seed arm prior]}]
                          (let [t0 (System/nanoTime)
                                res (search/run-arm
                                     {:arm arm
                                      :domain domain
                                      :run-seed run-seed
                                      :outer (assoc base-outer :generator-prior prior)
                                      :train {:tasks (:train splits)
                                              :inner-seeds train-seeds
                                              :solution-proposals 256
                                              :step-budget 4096}
                                      :validation {:max-finalists 8
                                                   :tasks (:validation splits)
                                                   :inner-seeds val-seeds
                                                   :solution-proposals 256
                                                   :step-budget 4096}
                                      :test {:tasks (:test splits)
                                             :inner-seeds test-seeds
                                             :solution-proposals 256
                                             :step-budget 4096}})
                                elapsed-s (/ (double (- (System/nanoTime) t0)) 1.0e9)]
                            (println (format "  Finished %s | %s [%s] seed=%d in %.1fs (Test HV=%.5f)"
                                             (name domain) (name arm) (name prior) run-seed elapsed-s
                                             (double (get-in res [:test-evaluation :mean-normalized-hv]))))
                            (compact-run-summary res elapsed-s prior)))
                        jobs-to-run))
                      all-runs (into reused-rep0 new-runs)
                      by-key (group-by (juxt :arm :generator-prior) all-runs)
                      arm-summaries
                      (mapv (fn [[arm prior]]
                              (let [rs (vec (sort-by :run-seed (get by-key [arm prior])))
                                    hvs (mapv :test-mean-normalized-hv rs)
                                    val-hvs (mapv #(get-in % [:selected :validation-mean-hv]) rs)
                                    distincts (mapv #(get-in % [:slot-ledger :distinct-candidates]) rs)
                                    proposals (mapv #(/ (double (get-in % [:test-totals :proposal-attempts])) 240.0) rs)]
                                {:arm arm
                                 :generator-prior prior
                                 :replication-test-hvs hvs
                                 :mean-test-hv (/ (reduce + 0.0 hvs) (double (count hvs)))
                                 :mean-validation-hv (/ (reduce + 0.0 val-hvs) (double (count val-hvs)))
                                 :mean-distinct-candidates (/ (reduce + 0.0 distincts) (double (count distincts)))
                                 :mean-proposals-used-per-run (/ (reduce + 0.0 proposals) (double (count proposals)))
                                 :replications rs}))
                            all-configs)
                      ;; Build 48 paired task-replication observations (2 outer seeds x 24 test tasks)
                      run-lookup (into {} (map (fn [r] [[(:arm r) (:generator-prior r) (:run-seed r)] r]) all-runs))
                      paired-task-runs
                      (vec
                       (for [rseed [0 1]
                             task-idx (range 24)]
                         (let [hv-at (fn [arm prior]
                                       (get-in (run-lookup [arm prior rseed])
                                               [:test-task-means task-idx :mean-normalized-hv]))]
                           {:domain domain
                            :run-seed rseed
                            :task-index task-idx
                            :outcomes {:gp-mixed (hv-at :gp-mixed :budget-aware-v2)
                                       :gp-mutation-only (hv-at :gp-mutation-only :budget-aware-v2)
                                       :random-dsl (hv-at :random-dsl :budget-aware-v2)
                                       :formula-only (hv-at :formula-only :budget-aware-v2)
                                       :configured-conventional (hv-at :configured-conventional :budget-aware-v2)
                                       :gp-mixed-v1 (hv-at :gp-mixed :development-only-v1)
                                       :gp-mutation-only-v1 (hv-at :gp-mutation-only :development-only-v1)
                                       :random-dsl-v1 (hv-at :random-dsl :development-only-v1)}})))
                      ablation-stats
                      (stats/analyze-paired-runs
                       paired-task-runs
                       {:resamples 2000 :alpha 0.05 :practical-margin 0.01 :seed 7001
                        :contrasts [{:hypothesis :A-gp-mixed :focal :gp-mixed :comparator :gp-mixed-v1
                                     :description "GP-Mixed: :budget-aware-v2 vs :development-only-v1"}
                                    {:hypothesis :A-gp-mutation :focal :gp-mutation-only :comparator :gp-mutation-only-v1
                                     :description "GP-Mutation-Only: :budget-aware-v2 vs :development-only-v1"}
                                    {:hypothesis :A-random-dsl :focal :random-dsl :comparator :random-dsl-v1
                                     :description "Random-DSL: :budget-aware-v2 vs :development-only-v1"}]})
                      primary-stats
                      (stats/analyze-paired-runs
                       paired-task-runs
                       {:resamples 2000 :alpha 0.05 :practical-margin 0.01 :seed 7002
                        :contrasts stats/primary-contrasts})]
                  [domain {:arm-summaries arm-summaries
                           :ablation-v2-vs-v1 ablation-stats
                           :primary-hypotheses-v2 primary-stats
                           :direct-controls (get-in existing-rep0 [:domains domain :direct-controls])}]))))
      total-seconds (/ (double (- (System/nanoTime) started-ns)) 1.0e9)
      summary {:experiment :two-outer-replications-and-generator-ablation
               :run-seeds [0 1]
               :executed-at (str (Instant/now))
               :total-elapsed-seconds total-seconds
               :outer-spec base-outer
               :domains domain-results}]
  (io/make-parents "docs/evidence/two-replication-ablation-results.edn")
  (spit "docs/evidence/two-replication-ablation-results.edn" (str (pr-str summary) "\n") :encoding "UTF-8")
  (println "\nCompleted 2-replication & ablation run in" (format "%.2f" total-seconds) "seconds.")
  (doseq [[domain {:keys [arm-summaries ablation-v2-vs-v1 primary-hypotheses-v2]}] domain-results]
    (println "\n==================================================================")
    (println "DOMAIN:" domain)
    (println "==================================================================")
    (doseq [s arm-summaries]
      (println (format "  %-24s [%-20s] | Rep0: %.5f | Rep1: %.5f | Mean Test HV: %.5f | Distinct/256: %5.1f | Avg Proposals/256: %5.1f"
                       (name (:arm s))
                       (name (:generator-prior s))
                       (double (first (:replication-test-hvs s)))
                       (double (second (:replication-test-hvs s)))
                       (double (:mean-test-hv s))
                       (double (:mean-distinct-candidates s))
                       (double (:mean-proposals-used-per-run s)))))
    (println "\n  --- Generator Prior Ablation (:budget-aware-v2 minus :development-only-v1) ---")
    (doseq [c (:contrasts ablation-v2-vs-v1)]
      (println (format "  %-16s | Mean Diff: %+.5f | 95%% CI: [%+.5f, %+.5f] | p = %.4f | Holm p = %.4f | %s"
                       (name (:id c))
                       (double (:mean-difference c))
                       (double (:ci-low c))
                       (double (:ci-high c))
                       (double (:p-value c))
                       (double (:holm-adjusted-p c))
                       (name (:decision c)))))
    (println "\n  --- Primary Hypotheses H1-H4 under :budget-aware-v2 ---")
    (doseq [c (:contrasts primary-hypotheses-v2)]
      (println (format "  %-16s | Mean Diff: %+.5f | 95%% CI: [%+.5f, %+.5f] | p = %.4f | Holm p = %.4f | %s"
                       (name (:id c))
                       (double (:mean-difference c))
                       (double (:ci-low c))
                       (double (:ci-high c))
                       (double (:p-value c))
                       (double (:holm-adjusted-p c))
                       (name (:decision c))))))
  (shutdown-agents))
