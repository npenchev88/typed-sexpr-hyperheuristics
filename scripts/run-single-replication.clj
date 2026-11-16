;; Single full outer replication (run-seed = 0) across all 5 search arms and
;; 3 direct deployment controls in both domains (:portfolio N=50, K=5, T=365
;; and :scheduling 30 jobs).
;; Uses 256 outer assessment slots (32 initial + 7 x 32 offspring),
;; 6 training tasks x 2 inner seeds x 256 proposals,
;; up to 8 validation finalists x 6 validation tasks x 2 inner seeds x 256 proposals,
;; and 1 frozen selected representative evaluated on 24 test tasks x 10 fresh inner seeds x 256 proposals.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-runtime :as port-runtime]
         '[niki.optimizer.portfolio-data :as port-data]
         '[niki.optimizer.scheduling :as scheduling]
         '[niki.optimizer.scheduling-runtime :as sched-runtime]
         '[niki.optimizer.pareto :as pareto]
         '[niki.optimizer.baselines :as baselines]
         '[niki.optimizer.search :as search])
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

(defn evaluate-direct-control [control-kind test-tasks inner-seeds solution-budget]
  (let [task-means
        (mapv (fn [{:keys [task-id adapter]}]
                (let [seed-hvs
                      (mapv (fn [s]
                              (let [res (baselines/run-direct-control
                                         control-kind adapter
                                         {:solution-budget solution-budget
                                          :seed (long s)
                                          :cache? true})]
                                (pareto/normalized-hypervolume (:archive res) (:normalization adapter))))
                            inner-seeds)]
                  {:task-id task-id
                   :mean-normalized-hv (/ (reduce + 0.0 seed-hvs) (double (count seed-hvs)))}))
              test-tasks)]
    {:control control-kind
     :test-mean-normalized-hv (/ (reduce + 0.0 (map :mean-normalized-hv task-means))
                                 (double (count task-means)))}))

(let [started-ns (System/nanoTime)
      _ (println "Preparing N=50, K=5, T=365 portfolio suite and 30-job scheduling suite...")
      port-raw (port-data/generate-synthetic-suite {:n 50 :k 5 :t 365 :base-seed 20261108})
      sched-raw (scheduling/generate-benchmark-suite {:jobs 30 :base-seed 20261108})
      domains
      {:portfolio {:train (prepare-portfolio-split (:train port-raw))
                   :validation (prepare-portfolio-split (:validation port-raw))
                   :test (prepare-portfolio-split (:test port-raw))}
       :scheduling {:train (prepare-scheduling-split (:train sched-raw))
                    :validation (prepare-scheduling-split (:validation sched-raw))
                    :test (prepare-scheduling-split (:test sched-raw))}}
      arms [:gp-mixed :gp-mutation-only :random-dsl :formula-only :configured-conventional]
      controls [:initializer-only :random-search :heuristic-local-search]
      outer-spec {:initial-population 32
                  :offspring-batches 7
                  :offspring-per-batch 32
                  :assessment-slots 256
                  :mixed-crossover-probability 0.5}
      train-seeds [101 102]
      val-seeds [201 202]
      test-seeds (vec (range 1001 1011))
      domain-results
      (into {}
            (for [[domain splits] domains]
              (do
                (println "Running 1 full outer replication (256 slots) for domain:" domain)
                (let [arm-runs
                      (vec
                       (pmap
                        (fn [arm]
                          (let [t0 (System/nanoTime)
                                res (search/run-arm
                                     {:arm arm
                                      :domain domain
                                      :run-seed 0
                                      :outer outer-spec
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
                            {:arm arm
                             :elapsed-seconds elapsed-s
                             :slot-ledger (:slot-ledger res)
                             :selected (:selected res)
                             :test-mean-normalized-hv (get-in res [:test-evaluation :mean-normalized-hv])
                             :test-totals (get-in res [:test-evaluation :totals])}))
                        arms))
                      ctrl-runs
                      (mapv #(evaluate-direct-control % (:test splits) test-seeds 256) controls)]
                  [domain {:arm-results arm-runs
                           :direct-controls ctrl-runs}]))))
      total-seconds (/ (double (- (System/nanoTime) started-ns)) 1.0e9)
      summary {:experiment :single-outer-replication-flagship-pilot
               :run-seed 0
               :executed-at (str (Instant/now))
               :total-elapsed-seconds total-seconds
               :outer-spec outer-spec
               :domains domain-results}]
  (io/make-parents "docs/evidence/single-replication-results.edn")
  (spit "docs/evidence/single-replication-results.edn" (str (pr-str summary) "\n") :encoding "UTF-8")
  (println "Completed in" (format "%.2f" total-seconds) "seconds.")
  (doseq [[domain {:keys [arm-results direct-controls]}] domain-results]
    (println "\n=== Domain:" domain "===")
    (doseq [r arm-results]
      (println (format "  Arm %-25s | Val HV: %.6f | Test HV (24x10): %.6f | Nodes: %2d | Distinct/256: %3d | Time: %.2fs"
                       (name (:arm r))
                       (double (get-in r [:selected :validation-mean-hv]))
                       (double (:test-mean-normalized-hv r))
                       (long (get-in r [:selected :node-count]))
                       (long (get-in r [:slot-ledger :distinct-candidates]))
                       (double (:elapsed-seconds r)))))
    (doseq [c direct-controls]
      (println (format "  Control %-21s | Test HV (24x10): %.6f"
                       (name (:control c))
                       (double (:test-mean-normalized-hv c))))))
  (shutdown-agents))
