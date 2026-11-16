(ns summarize-g8-thirty-runs
  "Enrich and summarize the 30-outer-replication (G8) confirmatory results (`run-seed = 0..29`),
   including:
   - 30-seed mean, SD, and 95% CI for Held-Out Test HV, Validation HV, |ND| archive size,
     outer distinct candidates (/256), inner proposals (/256), and selected AST node count.
   - Paired bootstrap + Holm-Bonferroni at both the outer-seed level (N=30 per domain, 60 total)
     and the task-seed level (N=720 per domain, 1,440 total), plus moving temporal block bootstrap
     on the 24 rolling Portfolio test windows.
   - Syntactic uniqueness & operator/primitive frequency tally across the 30 final selected programs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [niki.optimizer.stats :as stats]
            [niki.optimizer.springer-figures :as sfig]))

(defn mean [xs]
  (let [xs (keep identity xs)]
    (if (seq xs)
      (/ (reduce + 0.0 xs) (double (count xs)))
      0.0)))

(defn sd [xs]
  (let [xs (vec (keep identity xs))
        n (count xs)]
    (if (<= n 1)
      0.0
      (let [m (mean xs)
            ss (reduce + 0.0 (map #(let [d (- (double %) m)] (* d d)) xs))]
        (Math/sqrt (/ ss (double (dec n))))))))

(defn tally-ast-features [programs]
  (let [unique-progs ( count (distinct programs))
        has-kw? (fn [prog kw]
                  (boolean (some #(= kw %) (tree-seq coll? seq prog))))]
    {:total-programs (count programs)
     :syntactically-unique unique-progs
     :feature-counts
     (into (sorted-map)
           (for [kw [:first :random :repeat :if :seq :search :step
                     :swap :restart :insert :reverse
                     :objective-0 :objective-1 :progress :archive-fill
                     :add :subtract :multiply :protected-div :min :max :negate]]
             [kw (count (filter #(has-kw? % kw) programs))]))}))

(defn -main [& _args]
  (let [g8-path "docs/evidence/g8-thirty-replications-results.edn"
        g8-data (edn/read-string (slurp g8-path))
        primary-arms [:gp-mixed :gp-mutation-only :random-dsl :formula-only :configured-conventional]
        ;; Build cross-domain outer-seed paired vector (60 observations: 2 domains x 30 seeds)
        all-outer-paired
        (vec
         (for [domain [:portfolio :scheduling]
               :let [summaries (get-in g8-data [:domains domain :arm-summaries])
                     by-arm-seed (into {}
                                       (for [s summaries
                                             :when (= :budget-aware-v2 (:generator-prior s))
                                             r (:replications s)]
                                         [[(:arm r) (:run-seed r)] r]))]
               rseed (range 30)]
           {:domain domain
            :run-seed rseed
            :outcomes (into {}
                            (for [arm primary-arms]
                              [arm (:test-mean-normalized-hv (by-arm-seed [arm rseed]))]))}))
        ;; Build cross-domain task-seed paired vector (1,440 observations: 2 domains x 30 seeds x 24 test tasks)
        all-task-paired
        (vec
         (for [domain [:portfolio :scheduling]
               :let [summaries (get-in g8-data [:domains domain :arm-summaries])
                     by-arm-seed (into {}
                                       (for [s summaries
                                             :when (= :budget-aware-v2 (:generator-prior s))
                                             r (:replications s)]
                                         [[(:arm r) (:run-seed r)] r]))]
               rseed (range 30)
               t-idx (range 24)]
           {:domain domain
            :run-seed rseed
            :task-index t-idx
            :outcomes (into {}
                            (for [arm primary-arms]
                              [arm (get-in (by-arm-seed [arm rseed])
                                           [:test-task-means t-idx :mean-normalized-hv])]))}))
        global-outer-stats
        (stats/analyze-paired-runs
         all-outer-paired
         {:resamples 10000 :alpha 0.05 :practical-margin 0.01 :seed 9001
          :contrasts stats/primary-contrasts})
        global-task-stats
        (stats/analyze-paired-runs
         all-task-paired
         {:resamples 10000 :alpha 0.05 :practical-margin 0.01 :seed 9002
          :contrasts stats/primary-contrasts})
        enriched-domains
        (into {}
              (for [[domain dmap] (:domains g8-data)]
                (let [summaries (:arm-summaries dmap)
                      enriched-summaries
                      (mapv (fn [s]
                              (let [rs (:replications s)
                                    hvs (:replication-test-hvs s)
                                    s-sd (sd hvs)
                                    ci95 (* 1.96 (/ s-sd (Math/sqrt (double (max 1 (count hvs))))))
                                    nds (keep :test-mean-nd-size rs)
                                    nodes (map #(get-in % [:selected :node-count]) rs)
                                    elaps (keep :elapsed-seconds rs)
                                    gen0s (keep :gen0-best-train-hv rs)
                                    finals (keep :final-best-train-hv rs)
                                    progs (map #(get-in % [:selected :candidate]) rs)]
                                (assoc s
                                       :sd-test-hv s-sd
                                       :ci95-halfwidth ci95
                                       :mean-nd-size (mean nds)
                                       :sd-nd-size (sd nds)
                                       :mean-selected-node-count (mean nodes)
                                       :mean-outer-elapsed-seconds (mean elaps)
                                       :mean-gen0-best-train-hv (mean gen0s)
                                       :mean-final-best-train-hv (mean finals)
                                       :ast-tally (when (not= :configured-conventional (:arm s))
                                                    (tally-ast-features progs)))))
                            summaries)
                      dom-outer (filterv #(= domain (:domain %)) all-outer-paired)
                      dom-task  (filterv #(= domain (:domain %)) all-task-paired)
                      dom-outer-stats
                      (stats/analyze-paired-runs
                       dom-outer
                       {:resamples 10000 :alpha 0.05 :practical-margin 0.01 :seed 9011
                        :contrasts stats/primary-contrasts})
                      dom-task-stats
                      (stats/analyze-paired-runs
                       dom-task
                       {:resamples 10000 :alpha 0.05 :practical-margin 0.01 :seed 9012
                        :contrasts stats/primary-contrasts})]
                  [domain (assoc dmap
                                 :arm-summaries enriched-summaries
                                 :primary-hypotheses-v2-outer dom-outer-stats
                                 :primary-hypotheses-v2-task dom-task-stats)])))
        enriched-g8 (assoc g8-data
                           :domains enriched-domains
                           :global-outer-hypotheses global-outer-stats
                           :global-task-hypotheses global-task-stats)]
    (spit g8-path (str (pr-str enriched-g8) "\n") :encoding "UTF-8")
    (sfig/generate-fig3-arms-and-prior! enriched-g8 "docs/figures/springer/fig3_five_arms_and_prior_ablation.svg")

    (doseq [[domain dmap] enriched-domains]
      (println "\n================================================================================")
      (println "DOMAIN:" domain "(30 Outer Replications, run-seed = 0..29)")
      (println "================================================================================")
      (doseq [s (:arm-summaries dmap)]
        (println (format "  %-24s [%-20s] n=%2d | Test HV: %.5f +/- %.5f (SD=%.5f) | Val HV: %.5f | |ND|: %4.1f | Distinct/256: %5.1f | Nodes: %4.1f | Time: %5.1fs"
                         (name (:arm s))
                         (name (:generator-prior s))
                         (long (:num-replications s))
                         (double (:mean-test-hv s))
                         (double (:ci95-halfwidth s))
                         (double (:sd-test-hv s))
                         (double (:mean-validation-hv s))
                         (double (:mean-nd-size s))
                         (double (:mean-distinct-candidates s))
                         (double (:mean-selected-node-count s))
                         (double (:mean-outer-elapsed-seconds s)))))
      (println "\n  --- Outer-Seed Paired Bootstrap (N=30 seeds, 10,000 resamples) ---")
      (doseq [c (get-in dmap [:primary-hypotheses-v2-outer :contrasts])]
        (println (format "  %-18s | Diff: %+.5f | 95%% CI: [%+.5f, %+.5f] | p=%.4f | Holm p=%.4f | %s"
                         (name (:id c))
                         (double (:mean-difference c))
                         (double (:ci-low c))
                         (double (:ci-high c))
                         (double (:p-value c))
                         (double (:holm-adjusted-p c))
                         (name (:decision c)))))
      (println "\n  --- Task-Seed Paired Bootstrap (N=720 = 30 seeds x 24 tasks, 10,000 resamples) ---")
      (doseq [c (get-in dmap [:primary-hypotheses-v2-task :contrasts])]
        (println (format "  %-18s | Diff: %+.5f | 95%% CI: [%+.5f, %+.5f] | p=%.4f | Holm p=%.4f | %s"
                         (name (:id c))
                         (double (:mean-difference c))
                         (double (:ci-low c))
                         (double (:ci-high c))
                         (double (:p-value c))
                         (double (:holm-adjusted-p c))
                         (name (:decision c)))))
      (println "\n  --- AST Uniqueness & Feature Tally (30 Final Programs, :budget-aware-v2) ---")
      (doseq [s (:arm-summaries dmap)
              :when (and (= :budget-aware-v2 (:generator-prior s)) (:ast-tally s))]
        (println "   " (:arm s) "-> Unique:" (get-in s [:ast-tally :syntactically-unique]) "/" (get-in s [:ast-tally :total-programs])
                 "| Gen0->Final Train HV:" (format "%.5f -> %.5f" (double (:mean-gen0-best-train-hv s)) (double (:mean-final-best-train-hv s)))
                 "| Features:" (pr-str (get-in s [:ast-tally :feature-counts])))))))

(apply -main *command-line-args*)
