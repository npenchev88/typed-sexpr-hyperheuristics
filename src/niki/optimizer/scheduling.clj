(ns niki.optimizer.scheduling
  "Single-machine non-preemptive scheduling domain (permutation representation).
  Objectives (both minimized):
    f1 = mean completion time = (1/J) * sum_j C_j
    f2 = total tardiness      = sum_j max(0, C_j - d_j)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [niki.optimizer.pareto :as pareto])
  (:import [java.util Random]))

(def ^:private integral-classes
  #{Byte Short Integer Long clojure.lang.BigInt java.math.BigInteger})

(defn- ensure! [condition code message data]
  (when-not condition
    (throw (ex-info message (assoc data :error code)))))

(defn- valid-id? [x]
  (or (keyword? x)
      (and (string? x) (not (str/blank? x)))))

(defn- clean-zero ^double [^double x]
  (if (zero? x) 0.0 x))

(defrecord PreparedScheduling
  [instance-id jobs job-ids durations due-dates total-duration max-tardiness-bound])

(defn validate-instance
  "Validate a single-machine scheduling instance map without realizing lazy non-vector shapes."
  [panel]
  (ensure! (map? panel) :invalid-instance "Scheduling instance must be a map" {})
  (let [instance-id (:instance-id panel)
        jobs (:jobs panel)]
    (ensure! (valid-id? instance-id) :invalid-instance-id "Instance requires a non-blank :instance-id" {})
    (ensure! (and (vector? jobs) (seq jobs) (<= (count jobs) 10000))
             :invalid-jobs "Instance :jobs must be a non-empty vector" {})
    (loop [i 0
           seen-ids #{}
           ids []
           durations (long-array (count jobs))
           due-dates (long-array (count jobs))
           total-p 0]
      (if (= i (count jobs))
        (let [max-tardiness (loop [j 0 acc 0]
                              (if (= j (count jobs))
                                acc
                                (recur (inc j)
                                       (Math/addExact acc (max 0 (- total-p (aget due-dates j)))))))]
          {:instance-id instance-id
           :jobs jobs
           :job-ids ids
           :durations (vec durations)
           :due-dates (vec due-dates)
           :total-duration total-p
           :max-tardiness-bound max-tardiness})
        (let [job (nth jobs i)]
          (ensure! (map? job) :invalid-job "Each job must be a map" {:index i})
          (let [id (:id job)
                p (:duration job)
                d (:due-date job)]
            (ensure! (valid-id? id) :invalid-job-id "Job requires a non-blank :id" {:index i})
            (ensure! (not (contains? seen-ids id)) :duplicate-job-id "Duplicate job :id" {:index i :id id})
            (ensure! (and (contains? integral-classes (class p))
                          (<= 1 p 1000000))
                     :invalid-duration "Job :duration must be an integer in [1, 1000000]" {:index i :id id})
            (ensure! (and (contains? integral-classes (class d))
                          (<= 1 d 1000000000))
                     :invalid-due-date "Job :due-date must be an integer in [1, 1000000000]" {:index i :id id})
            (let [pl (long p)
                  dl (long d)]
              (aset durations i pl)
              (aset due-dates i dl)
              (recur (inc i)
                     (conj seen-ids id)
                     (conj ids id)
                     durations
                     due-dates
                     (Math/addExact total-p pl)))))))))

(defn prepare-instance
  "Validate and prepare a scheduling instance for fast evaluation and fixed G2 normalization."
  [panel]
  (let [{:keys [instance-id jobs job-ids durations due-dates total-duration max-tardiness-bound]}
        (validate-instance panel)]
    (->PreparedScheduling instance-id jobs job-ids durations due-dates total-duration max-tardiness-bound)))

(defn validate-schedule
  "Validate that schedule is a canonical permutation vector of [0 .. J-1]."
  [instance schedule]
  (ensure! (instance? PreparedScheduling instance)
           :unprepared-instance "Call scheduling/prepare-instance first" {})
  (let [n (count (:durations instance))]
    (ensure! (and (vector? schedule) (= n (count schedule)))
             :invalid-schedule "Schedule must be a vector of length J" {:expected-length n})
    (let [seen (boolean-array n)
          out (object-array n)]
      (dotimes [i n]
        (let [x (nth schedule i)]
          (ensure! (and (contains? integral-classes (class x))
                        (<= 0 x (dec n)))
                   :invalid-job-index "Schedule index out of bounds" {:position i})
          (let [idx (int x)]
            (ensure! (not (aget seen idx))
                     :duplicate-job-index "Schedule contains duplicate job index" {:position i :job idx})
            (aset seen idx true)
            (aset out i (long idx)))))
      (vec out))))

(defn evaluate
  "Evaluate a permutation schedule on a PreparedScheduling instance.
  Returns {:schedule canonical-schedule
           :mean-completion-time f1
           :total-tardiness f2
           :objectives [f1 f2]}."
  [instance schedule]
  (let [canonical (validate-schedule instance schedule)
        durations (:durations instance)
        due-dates (:due-dates instance)
        n (count durations)]
    (loop [i 0
           time 0
           sum-completion 0
           sum-tardiness 0]
      (if (= i n)
        (let [mean-c (clean-zero (/ (double sum-completion) (double n)))
              tardiness (clean-zero (double sum-tardiness))]
          {:schedule canonical
           :mean-completion-time mean-c
           :total-tardiness tardiness
           :objectives [mean-c tardiness]})
        (let [job-idx (int (nth canonical i))
              p (long (nth durations job-idx))
              d (long (nth due-dates job-idx))
              c (+ time p)
              tard (max 0 (- c d))]
          (recur (inc i)
                 c
                 (+ sum-completion c)
                 (+ sum-tardiness tard)))))))

(defn scheduling-normalization
  "Construct fixed instance-derived analytical G2 normalization bounds for a PreparedScheduling:
    mean completion time in [0.0, P]
    total tardiness      in [0.0, sum_j max(0, P - d_j)]."
  [instance]
  (ensure! (instance? PreparedScheduling instance)
           :unprepared-instance "Call scheduling/prepare-instance first" {})
  (pareto/prepare-normalization
   [[0.0 (double (:total-duration instance))]
    [0.0 (double (:max-tardiness-bound instance))]]))

(def default-strata
  "Six deadline-tightness / due-date-range strata from FINAL_EXPERIMENT_PLAN.md §5.2."
  (vec (for [tau [0.2 0.5 0.8]
             r [0.2 0.6]]
         {:tau tau :r r})))

(defn generate-instance
  "Generate a deterministic single-machine scheduling instance per FINAL_EXPERIMENT_PLAN.md §5.2:
   integer durations in [1, 100], total duration P, deadlines max(1, round(P * u))
   with u uniform on [1 - tau - R/2, 1 - tau + R/2]."
  [{:keys [instance-id jobs tau r seed]
    :or {jobs 30 tau 0.5 r 0.2 seed 0}}]
  (ensure! (valid-id? instance-id) :invalid-instance-id "Requires :instance-id" {})
  (ensure! (and (contains? integral-classes (class jobs)) (<= 1 jobs 1000))
           :invalid-jobs-count "Jobs count must be in [1, 1000]" {})
  (ensure! (and (number? tau) (Double/isFinite (double tau)) (<= 0.0 (double tau) 1.0))
           :invalid-tau "tau must be in [0.0, 1.0]" {})
  (ensure! (and (number? r) (Double/isFinite (double r)) (<= 0.0 (double r) 2.0))
           :invalid-r "r must be in [0.0, 2.0]" {})
  (ensure! (and (contains? integral-classes (class seed))
                (<= Long/MIN_VALUE seed Long/MAX_VALUE))
           :invalid-seed "Invalid seed" {})
  (let [rng (Random. (long seed))
        n (long jobs)
        durations (mapv (fn [_] (inc (.nextInt rng 100))) (range n))
        total-p (reduce + 0 durations)
        lo (- 1.0 (double tau) (/ (double r) 2.0))
        hi (+ (- 1.0 (double tau)) (/ (double r) 2.0))
        job-maps (mapv (fn [idx p]
                         (let [u (+ lo (* (- hi lo) (.nextDouble rng)))
                               due (max 1 (long (Math/round (* (double total-p) u))))]
                           {:id (str "job-" idx)
                            :duration (long p)
                            :due-date due}))
                       (range n) durations)]
    {:instance-id instance-id
     :source :synthetic-stratified-generator
     :stratum {:tau (double tau) :r (double r)}
     :seed (long seed)
     :jobs job-maps}))

(defn generate-benchmark-suite
  "Generate disjoint train (6), validation (6), and test (24) stratified scheduling instances."
  [{:keys [jobs base-seed] :or {jobs 30 base-seed 20261108}}]
  (let [train (vec (map-indexed
                    (fn [idx {:keys [tau r]}]
                      (generate-instance {:instance-id (str "sched-train-" idx)
                                          :jobs jobs :tau tau :r r
                                          :seed (+ base-seed 100 idx)}))
                    default-strata))
        validation (vec (map-indexed
                         (fn [idx {:keys [tau r]}]
                           (generate-instance {:instance-id (str "sched-val-" idx)
                                               :jobs jobs :tau tau :r r
                                               :seed (+ base-seed 200 idx)}))
                         default-strata))
        test (vec (for [[s-idx {:keys [tau r]}] (map-indexed vector default-strata)
                        rep (range 4)
                        :let [idx (+ (* s-idx 4) rep)]]
                    (generate-instance {:instance-id (str "sched-test-" idx)
                                        :jobs jobs :tau tau :r r
                                        :seed (+ base-seed 1000 idx)})))]
    {:train train :validation validation :test test}))

(defn load-orlib-wt-suite
  "Load the official J. E. Beasley OR-Library single-machine tardiness dataset (data/orlib_wt40.txt,
  125 instances of 40 jobs across 25 RDD x TF strata) and select 36 disjoint benchmark instances:
  6 training, 6 validation, and 24 held-out test instances."
  ([] (load-orlib-wt-suite {}))
  ([{:keys [path jobs]
     :or {path "data/orlib_wt40.txt" jobs 40}}]
   (let [f (io/file path)]
     (ensure! (.exists f) :missing-dataset-file "OR-Library wt40 dataset file not found" {:path path})
     (let [tokens (mapv #(Long/parseLong %) (re-seq #"-?\d+" (slurp f)))
           n (long jobs)
           block-size (* 3 n)
           total-instances (quot (count tokens) block-size)
           _ (ensure! (>= total-instances 125) :invalid-orlib-file "Expected 125 instances in wt40.txt"
                      {:found total-instances})
           parse-inst (fn [inst-idx id-str]
                        (let [offset (* inst-idx block-size)
                              ps (subvec tokens offset (+ offset n))
                              ws (subvec tokens (+ offset n) (+ offset (* 2 n)))
                              ds (subvec tokens (+ offset (* 2 n)) (+ offset (* 3 n)))
                              rds [0.2 0.4 0.6 0.8 1.0]
                              tfs [0.2 0.4 0.6 0.8 1.0]
                              stratum-idx (quot inst-idx 5)
                              r-val (nth rds (quot stratum-idx 5))
                              tau-val (nth tfs (mod stratum-idx 5))
                              job-maps (mapv (fn [j p w d]
                                               {:id (str "job-" j)
                                                :duration (max 1 (long p))
                                                :weight (long w)
                                                :due-date (max 1 (long d))})
                                             (range n) ps ws ds)]
                          {:instance-id id-str
                           :orlib-index inst-idx
                           :source :or-library-wt40
                           :stratum {:tau (double tau-val) :r (double r-val)}
                           :jobs job-maps}))
           ;; Select 6 representative hardness groups across the 125 OR-Library instances
           ;; (e.g. start offsets 0, 20, 40, 60, 80, 100, taking 6 consecutive instances from each:
           ;;  1st -> train (6), 2nd -> validation (6), 3rd..6th -> test (24))
           group-offsets [0 20 40 60 80 100]
           train (vec (map-indexed (fn [s-idx base]
                                     (parse-inst base (str "wt40-train-" s-idx "-id" base)))
                                   group-offsets))
           validation (vec (map-indexed (fn [s-idx base]
                                          (parse-inst (inc base) (str "wt40-val-" s-idx "-id" (inc base))))
                                        group-offsets))
           test (vec (for [[s-idx base] (map-indexed vector group-offsets)
                           rep (range 4)
                           :let [inst-idx (+ base 2 rep)
                                 t-idx (+ (* s-idx 4) rep)]]
                       (parse-inst inst-idx (str "wt40-test-" t-idx "-id" inst-idx))))]
       {:train train :validation validation :test test}))))
