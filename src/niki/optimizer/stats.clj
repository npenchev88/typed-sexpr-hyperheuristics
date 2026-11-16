(ns niki.optimizer.stats
  "Preregistered statistical analysis procedures for G7/G8:
   - Paired outer-run bootstrap resampling (preserving all arm outcomes per run)
   - Holm-Bonferroni step-down multiplicity adjustment across the 4-contrast (single-domain)
     or 8-contrast (two-domain) confirmatory family
   - Practical threshold classification (+/- 0.01 normalized-HV margin)
   - Moving temporal block bootstrap for task/period uncertainty."
  (:import [java.util Random]))

(def primary-contrasts
  "The four primary Full-GP contrasts per domain from FINAL_EXPERIMENT_PLAN.md §4."
  [{:hypothesis :H1 :focal :gp-mixed :comparator :random-dsl
    :description "Full GP vs Random DSL Search"}
   {:hypothesis :H2 :focal :gp-mixed :comparator :formula-only
    :description "Full GP vs Formula-only in Fixed Scaffold"}
   {:hypothesis :H3 :focal :gp-mixed :comparator :gp-mutation-only
    :description "Mutation+Crossover vs Mutation-only GP"}
   {:hypothesis :H4 :focal :gp-mixed :comparator :configured-conventional
    :description "Full GP vs Configured Conventional Families"}])

(defn- ensure! [condition code message data]
  (when-not condition
    (throw (ex-info message (assoc data :error code)))))

(defn mean ^double [xs]
  (ensure! (seq xs) :empty-sample "Cannot compute mean of empty collection" {})
  (/ (reduce + 0.0 xs) (double (count xs))))

(defn sample-sd ^double [xs]
  (let [n (count xs)]
    (if (<= n 1)
      0.0
      (let [m (mean xs)
            ss (reduce (fn [^double acc x]
                         (let [d (- (double x) m)]
                           (+ acc (* d d))))
                       0.0
                       xs)]
        (Math/sqrt (/ ss (double (dec n))))))))

(defn quantile ^double [sorted-doubles ^double q]
  (let [n (count sorted-doubles)]
    (ensure! (pos? n) :empty-sample "Cannot compute quantile of empty vector" {})
    (cond
      (<= q 0.0) (double (first sorted-doubles))
      (>= q 1.0) (double (peek sorted-doubles))
      :else
      (let [pos (* q (double (dec n)))
            lo (int (Math/floor pos))
            hi (int (Math/ceil pos))
            frac (- pos (double lo))
            v-lo (double (nth sorted-doubles lo))
            v-hi (double (nth sorted-doubles hi))]
        (+ v-lo (* frac (- v-hi v-lo)))))))

(defn classify-interval
  "Classify a confidence interval [lo, hi] against practical margin m (default 0.01)
   per FINAL_EXPERIMENT_PLAN.md §§4, 11."
  [^double lo ^double hi ^double margin]
  (cond
    (> lo margin) :practically-superior
    (< hi (- margin)) :practically-inferior
    (and (> lo (- margin)) (< hi margin)) :practically-equivalent
    :else :inconclusive))

(defn holm-bonferroni
  "Apply Holm-Bonferroni step-down multiplicity adjustment to a vector of contrast maps
   each containing :id, :p-value, :bootstrap-means (sorted vector of bootstrap means),
   and :practical-margin."
  [contrasts {:keys [alpha practical-margin]
              :or {alpha 0.05 practical-margin 0.01}}]
  (let [m (count contrasts)
        sorted (vec (sort-by (fn [c] [(double (:p-value c)) (str (:id c))]) contrasts))
        adjusted
        (loop [k 0
               max-p 0.0
               out []]
          (if (= k m)
            out
            (let [c (nth sorted k)
                  divisor (double (- m k))
                  raw-p (double (:p-value c))
                  adj-p (min 1.0 (max max-p (* divisor raw-p)))
                  ;; Simultaneous family-wise interval level 1 - alpha / m
                  sim-alpha (/ (double alpha) (double m))
                  boots (:bootstrap-means c)
                  sim-lo (if (seq boots) (quantile boots (/ sim-alpha 2.0)) (:ci-low c))
                  sim-hi (if (seq boots) (quantile boots (- 1.0 (/ sim-alpha 2.0))) (:ci-high c))]
              (recur (inc k)
                     adj-p
                     (conj out
                           (-> (dissoc c :bootstrap-means)
                               (assoc :holm-rank (inc k)
                                      :holm-adjusted-p adj-p
                                      :null-rejected? (< adj-p (double alpha))
                                      :simultaneous-ci [sim-lo sim-hi]
                                      :decision (classify-interval sim-lo sim-hi (double practical-margin)))))))))]
    (vec (sort-by :original-index adjusted))))

(defn analyze-paired-runs
  "Analyze paired outer-run outcomes.
   `paired-runs` is a vector of maps (one per outer run seed), where each map contains
   {:domain ... :run-seed ... :outcomes {:gp-mixed hv ...}}.
   Resamples whole outer-run vectors to preserve arm pairing across all contrasts."
  [paired-runs {:keys [resamples alpha practical-margin seed contrasts]
                :or {resamples 10000 alpha 0.05 practical-margin 0.01 seed 20261108
                     contrasts primary-contrasts}}]
  (ensure! (and (vector? paired-runs) (seq paired-runs))
           :invalid-runs "paired-runs must be a non-empty vector" {})
  (let [n (count paired-runs)
        rng (Random. (long seed))
        B (long resamples)
        ;; Draw B bootstrap index vectors of length n once so all contrasts share identical resamples
        boot-indices (mapv (fn [_]
                             (long-array (repeatedly n #(.nextInt rng n))))
                           (range B))
        raw-contrasts
        (vec
         (map-indexed
          (fn [idx {:keys [hypothesis focal comparator description] :as spec}]
            (let [id (or (:id spec) hypothesis)
                  diffs (mapv (fn [run]
                                (let [a (get-in run [:outcomes focal])
                                      b (get-in run [:outcomes comparator])]
                                  (ensure! (and (number? a) (number? b))
                                           :missing-arm-outcome "Missing arm outcome in paired run"
                                           {:focal focal :comparator comparator :run run})
                                  (- (double a) (double b))))
                              paired-runs)
                  obs-mean (mean diffs)
                  obs-sd (sample-sd diffs)
                  boot-means
                  (vec
                   (sort
                    (map (fn [^longs idxs]
                           (loop [i 0 sum 0.0]
                             (if (= i n)
                               (/ sum (double n))
                               (recur (inc i) (+ sum (double (nth diffs (aget idxs i))))))))
                         boot-indices)))
                  ci-lo (quantile boot-means (/ (double alpha) 2.0))
                  ci-hi (quantile boot-means (- 1.0 (/ (double alpha) 2.0)))
                  ;; Two-sided shifted bootstrap / sign-proportion p-value around 0.0
                  le-zero (/ (double (count (filter #(<= (double %) 0.0) boot-means))) (double B))
                  ge-zero (/ (double (count (filter #(>= (double %) 0.0) boot-means))) (double B))
                  p-val (min 1.0 (* 2.0 (min le-zero ge-zero)))]
              {:original-index idx
               :id id
               :hypothesis hypothesis
               :focal focal
               :comparator comparator
               :description description
               :n-runs n
               :mean-difference obs-mean
               :sd-difference obs-sd
               :unadjusted-ci [ci-lo ci-hi]
               :ci-low ci-lo
               :ci-high ci-hi
               :p-value p-val
               :unadjusted-decision (classify-interval ci-lo ci-hi (double practical-margin))
               :bootstrap-means boot-means}))
          contrasts))]
    {:n-runs n
     :resamples B
     :alpha (double alpha)
     :practical-margin (double practical-margin)
     :contrasts (holm-bonferroni raw-contrasts {:alpha alpha :practical-margin practical-margin})}))

(defn temporal-block-bootstrap
  "Crossed outer-run x consecutive-task-block bootstrap for task/period uncertainty.
   `task-matrix` is a vector of outer runs, where each element is a vector of per-task
   paired differences ordered chronologically along the task axis."
  [task-matrix {:keys [block-length resamples alpha seed]
                :or {block-length 3 resamples 2000 alpha 0.05 seed 20261108}}]
  (ensure! (and (vector? task-matrix) (seq task-matrix) (every? #(and (vector? %) (seq %)) task-matrix))
           :invalid-matrix "task-matrix must be a non-empty vector of per-run task vectors" {})
  (let [n-runs (count task-matrix)
        n-tasks (count (first task-matrix))
        _ (ensure! (every? #(= n-tasks (count %)) task-matrix)
                   :ragged-matrix "All runs must have identical task counts" {})
        blk (max 1 (min n-tasks (long block-length)))
        n-blocks (int (Math/ceil (/ (double n-tasks) (double blk))))
        max-start (max 1 (inc (- n-tasks blk)))
        rng (Random. (long seed))
        B (long resamples)
        point-est (mean (mapcat identity task-matrix))
        boot-means
        (vec
         (sort
          (repeatedly
           B
           (fn []
             (let [sampled-tasks
                   (vec
                    (take n-tasks
                          (mapcat (fn [_]
                                    (let [start (.nextInt rng max-start)]
                                      (range start (+ start blk))))
                                  (range n-blocks))))]
               (loop [r 0 acc 0.0]
                 (if (= r n-runs)
                   (/ acc (double (* n-runs n-tasks)))
                   (let [run-idx (.nextInt rng n-runs)
                         row (nth task-matrix run-idx)
                         row-sum (reduce (fn [^double s t-idx]
                                           (+ s (double (nth row t-idx))))
                                         0.0
                                         sampled-tasks)]
                     (recur (inc r) (+ acc row-sum))))))))))]
    {:n-runs n-runs
     :n-tasks n-tasks
     :block-length blk
     :mean-difference point-est
     :ci [(quantile boot-means (/ (double alpha) 2.0))
          (quantile boot-means (- 1.0 (/ (double alpha) 2.0)))]}))
