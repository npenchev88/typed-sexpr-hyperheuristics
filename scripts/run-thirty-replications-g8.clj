(ns run-thirty-replications-g8
  "Checkpointed runner for the G8 30-outer-replication confirmatory experiment (`run-seed = 0..29`).
   - Automatically imports already-completed `run-seed = 0, 1` runs from
     `docs/evidence/two-replication-ablation-results.edn` into `docs/evidence/g8-checkpoints/`.
   - Saves every completed `(domain, run-seed, arm, prior)` immediately to disk so zero work
     is lost if interrupted or run in batches.
   - Aggregates all completed replications, runs 10,000-resample paired bootstrap + Holm-Bonferroni
     + temporal block bootstrap (`niki.optimizer.stats`), writes
     `docs/evidence/g8-thirty-replications-results.edn`, and updates
     `docs/figures/springer/fig3_five_arms_and_prior_ablation.svg`.

   Usage:
     .\\clojure.cmd -M scripts/run-thirty-replications-g8.clj          ; runs seeds 0..29
     .\\clojure.cmd -M scripts/run-thirty-replications-g8.clj 0 9      ; runs batch seeds 0..9"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [niki.optimizer.portfolio :as portfolio]
            [niki.optimizer.portfolio-runtime :as port-runtime]
            [niki.optimizer.portfolio-data :as port-data]
            [niki.optimizer.scheduling :as scheduling]
            [niki.optimizer.scheduling-runtime :as sched-runtime]
            [niki.optimizer.search :as search]
            [niki.optimizer.stats :as stats]
            [niki.optimizer.springer-figures :as sfig])
  (:import [java.time Instant]))

(def checkpoint-dir "docs/evidence/g8-checkpoints")

(defn checkpoint-file [domain run-seed arm prior]
  (io/file checkpoint-dir
           (format "%s-seed-%02d-%s-%s.edn"
                   (name domain) (long run-seed) (name arm) (name prior))))

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
  (let [task-outcomes (get-in res [:test-evaluation :task-outcomes])
        all-seed-runs (mapcat :seed-runs task-outcomes)
        nd-sizes (mapv #(count (:archive %)) all-seed-runs)
        mean-nd (if (seq nd-sizes)
                  (/ (reduce + 0.0 nd-sizes) (double (count nd-sizes)))
                  0.0)
        slots (:slots res)
        batch-trajectory
        (when (seq slots)
          (mapv (fn [b]
                  (let [upto (subvec slots 0 (min (count slots) (* (inc b) 32)))
                        b-slice (subvec slots (* b 32) (min (count slots) (* (inc b) 32)))]
                    {:batch b
                     :best-train-hv-so-far (apply max (map :training-mean-hv upto))
                     :batch-mean-train-hv (/ (reduce + 0.0 (map :training-mean-hv b-slice))
                                             (double (count b-slice)))
                     :batch-mean-node-count (/ (reduce + 0.0 (map :node-count b-slice))
                                               (double (count b-slice)))}))
                (range 8)))]
    {:arm (:arm res)
     :generator-prior prior
     :run-seed (:run-seed res)
     :elapsed-seconds elapsed-s
     :slot-ledger (:slot-ledger res)
     :selected (:selected res)
     :gen0-best-train-hv (:best-train-hv-so-far (first batch-trajectory))
     :final-best-train-hv (:best-train-hv-so-far (peek batch-trajectory))
     :batch-trajectory batch-trajectory
     :test-mean-normalized-hv (get-in res [:test-evaluation :mean-normalized-hv])
     :test-mean-nd-size mean-nd
     :test-task-means (mapv #(select-keys % [:task-id :mean-normalized-hv]) task-outcomes)
     :test-totals (get-in res [:test-evaluation :totals])}))

(defn seed-checkpoints-from-two-rep! []
  (let [two-rep-file (io/file "docs/evidence/two-replication-ablation-results.edn")]
    (when (.exists two-rep-file)
      (let [data (edn/read-string (slurp two-rep-file))]
        (doseq [[domain dmap] (:domains data)
                s (:arm-summaries dmap)
                rep (:replications s)
                :let [f (checkpoint-file domain (:run-seed rep) (:arm rep) (:generator-prior rep))]]
          (when-not (.exists f)
            (io/make-parents f)
            (spit f (str (pr-str rep) "\n") :encoding "UTF-8")))))))

(defn -main [& args]
  (let [start-seed (if (seq args) (Long/parseLong (first args)) 0)
        end-seed   (if (second args) (Long/parseLong (second args)) 29)
        started-ns (System/nanoTime)
        _ (println (format "=== G8 Confirmatory Runner [REAL DATA: NASDAQ-50 & OR-Library wt40] (seeds %d..%d) ===" start-seed end-seed))
        port-raw (port-data/load-nasdaq-suite)
        sched-raw (scheduling/load-orlib-wt-suite)
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
        primary-arms [:gp-mixed :gp-mutation-only :random-dsl :formula-only :configured-conventional]
        v1-arms [:gp-mixed :gp-mutation-only :random-dsl]

        ;; Build flat list of all pending (domain, rseed, arm, prior) jobs
        all-jobs
        (vec
         (concat
          (for [rseed (range start-seed (inc end-seed))
                domain [:portfolio :scheduling]
                arm primary-arms]
            {:domain domain :rseed rseed :arm arm :prior :budget-aware-v2})
          (for [rseed (range start-seed (inc end-seed))
                :when (<= rseed 1)
                domain [:portfolio :scheduling]
                arm v1-arms]
            {:domain domain :rseed rseed :arm arm :prior :development-only-v1})))
        pending-jobs
        (filterv (fn [{:keys [domain rseed arm prior]}]
                   (not (.exists (checkpoint-file domain rseed arm prior))))
                 all-jobs)
        total-pending (count pending-jobs)
        completed-counter (atom 0)]

    (println (format "  Total jobs in range %d..%d: %d (%d cached, %d pending)"
                     start-seed end-seed (count all-jobs) (- (count all-jobs) total-pending) total-pending))

    (when (pos? total-pending)
      (doall
       (pmap
        (fn [{:keys [domain rseed arm prior]}]
          (let [splits (get domains domain)
                t0 (System/nanoTime)
                res (search/run-arm
                     {:arm arm
                      :domain domain
                      :run-seed rseed
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
                elapsed-s (/ (double (- (System/nanoTime) t0)) 1.0e9)
                summary (compact-run-summary res elapsed-s prior)
                f (checkpoint-file domain rseed arm prior)
                done-n (swap! completed-counter inc)]
            (io/make-parents f)
            (spit f (str (pr-str summary) "\n") :encoding "UTF-8")
            (println (format "  [%3d/%3d] Saved %-10s | %-24s | %-19s | seed=%02d in %5.1fs (Test HV=%.5f, |ND|=%.1f)"
                             done-n total-pending (name domain) (name arm) (name prior) rseed elapsed-s
                             (double (:test-mean-normalized-hv summary))
                             (double (:test-mean-nd-size summary))))
            (flush)))
        pending-jobs)))

    ;; Aggregate all completed checkpoints in 0..end-seed
    (let [seeds-completed (vec (range 0 (inc end-seed)))
          domain-results
          (into {}
                (for [[domain _] domains]
                  (let [v2-runs
                        (vec
                         (for [rseed seeds-completed
                               arm primary-arms
                               :let [f (checkpoint-file domain rseed arm :budget-aware-v2)]
                               :when (.exists f)]
                           (edn/read-string (slurp f))))
                        v1-runs
                        (vec
                         (for [rseed [0 1]
                               arm v1-arms
                               :let [f (checkpoint-file domain rseed arm :development-only-v1)]
                               :when (.exists f)]
                           (edn/read-string (slurp f))))
                        all-runs (into v2-runs v1-runs)
                        by-key (group-by (juxt :arm :generator-prior) all-runs)
                        all-configs
                        [[:gp-mixed :budget-aware-v2]
                         [:gp-mutation-only :budget-aware-v2]
                         [:random-dsl :budget-aware-v2]
                         [:formula-only :budget-aware-v2]
                         [:configured-conventional :budget-aware-v2]
                         [:gp-mixed :development-only-v1]
                         [:gp-mutation-only :development-only-v1]
                         [:random-dsl :development-only-v1]]
                        arm-summaries
                        (mapv (fn [[arm prior]]
                                (let [rs (vec (sort-by :run-seed (get by-key [arm prior])))
                                      hvs (mapv :test-mean-normalized-hv rs)
                                      val-hvs (mapv #(get-in % [:selected :validation-mean-hv]) rs)
                                      distincts (mapv #(get-in % [:slot-ledger :distinct-candidates]) rs)
                                      proposals (mapv #(/ (double (get-in % [:test-totals :proposal-attempts])) 240.0) rs)]
                                  {:arm arm
                                   :generator-prior prior
                                   :num-replications (count rs)
                                   :replication-test-hvs hvs
                                   :mean-test-hv (/ (reduce + 0.0 hvs) (double (max 1 (count hvs))))
                                   :mean-validation-hv (/ (reduce + 0.0 val-hvs) (double (max 1 (count val-hvs))))
                                   :mean-distinct-candidates (/ (reduce + 0.0 distincts) (double (max 1 (count distincts))))
                                   :mean-proposals-used-per-run (/ (reduce + 0.0 proposals) (double (max 1 (count proposals))))
                                   :replications rs}))
                              all-configs)
                        run-lookup (into {} (map (fn [r] [[(:arm r) (:generator-prior r) (:run-seed r)] r]) all-runs))
                        paired-outer-runs
                        (vec
                         (for [rseed seeds-completed]
                           {:domain domain
                            :run-seed rseed
                            :outcomes
                            (into {}
                                  (for [arm primary-arms]
                                    [arm (:test-mean-normalized-hv (run-lookup [arm :budget-aware-v2 rseed]))]))}))
                        outer-stats
                        (when (>= (count paired-outer-runs) 2)
                          (stats/analyze-paired-runs
                           paired-outer-runs
                           {:resamples 10000 :alpha 0.05 :practical-margin 0.01 :seed 8001
                            :contrasts stats/primary-contrasts}))
                        v1-seeds-ready (filterv (fn [s]
                                                  (every? #(some? (run-lookup [% :development-only-v1 s])) v1-arms))
                                                [0 1])
                        ablation-v2-vs-v1
                        (when (seq v1-seeds-ready)
                          (let [task-runs
                                (vec
                                 (for [rseed v1-seeds-ready
                                       t-idx (range 24)]
                                   {:domain domain
                                    :run-seed (+ (* rseed 100) t-idx)
                                    :outcomes
                                    (into {}
                                          (concat
                                           (for [arm v1-arms]
                                             [arm (get-in (run-lookup [arm :budget-aware-v2 rseed])
                                                          [:test-task-means t-idx :mean-normalized-hv])])
                                           (for [arm v1-arms]
                                             [(keyword (str (name arm) "-v1"))
                                              (get-in (run-lookup [arm :development-only-v1 rseed])
                                                      [:test-task-means t-idx :mean-normalized-hv])])))}))]
                            (stats/analyze-paired-runs
                             task-runs
                             {:resamples 2000 :alpha 0.05 :practical-margin 0.01 :seed 9001
                              :contrasts
                              [{:id :A-gp-mixed :hypothesis :A-gp-mixed
                                :description "GP-Mixed: :budget-aware-v2 vs :development-only-v1"
                                :focal :gp-mixed :comparator :gp-mixed-v1}
                               {:id :A-gp-mutation :hypothesis :A-gp-mutation
                                :description "GP-Mutation-Only: :budget-aware-v2 vs :development-only-v1"
                                :focal :gp-mutation-only :comparator :gp-mutation-only-v1}
                               {:id :A-random-dsl :hypothesis :A-random-dsl
                                :description "Random-DSL: :budget-aware-v2 vs :development-only-v1"
                                :focal :random-dsl :comparator :random-dsl-v1}]})))]
                    [domain {:arm-summaries arm-summaries
                             :primary-hypotheses-v2-outer outer-stats
                             :ablation-v2-vs-v1 ablation-v2-vs-v1}])))]
      (let [total-seconds (/ (double (- (System/nanoTime) started-ns)) 1.0e9)
            out-path "docs/evidence/g8-thirty-replications-results.edn"
            out-map {:experiment :g8-confirmatory-outer-replications
                     :datasets {:portfolio "data/nasdaq50_2012_2026.edn"
                                :scheduling "data/orlib_wt40.txt"}
                     :run-seeds seeds-completed
                     :executed-at (str (Instant/now))
                     :total-elapsed-seconds total-seconds
                     :outer-spec base-outer
                     :domains domain-results}]
        (io/make-parents out-path)
        (spit out-path (str (pr-str out-map) "\n") :encoding "UTF-8")
        (println "\nWrote G8 results to:" out-path)
        (when (>= (count seeds-completed) 2)
          (sfig/generate-fig3-arms-and-prior! out-map "docs/figures/springer/fig3_five_arms_and_prior_ablation.svg"))
        (shutdown-agents)))))

(apply -main *command-line-args*)
