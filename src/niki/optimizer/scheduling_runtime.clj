(ns niki.optimizer.scheduling-runtime
  "Trusted permutation adapter for single-machine scheduling. Plugs directly into
  the unchanged G3/G4 niki.optimizer.runtime and niki.optimizer.dsl core."
  (:require [niki.optimizer.scheduling :as scheduling])
  (:import [java.util Random]))

(defn- random-permutation [n ^Random rng]
  ;; Full Fisher-Yates shuffle: exactly n bounded draws, zero rejection loops.
  (loop [pool (vec (range n)) i 0]
    (if (= i n)
      pool
      (let [j (+ i (.nextInt rng (- n i)))
            a (nth pool i)
            b (nth pool j)]
        (recur (assoc pool i b j a) (inc i))))))

(defn- pick-two-distinct [n ^Random rng]
  (let [i (.nextInt rng n)
        raw (.nextInt rng (dec n))
        j (if (>= raw i) (inc raw) raw)]
    [i j]))

(defn- insert-at [perm i j]
  (let [elem (nth perm i)
        without (into (subvec perm 0 i) (subvec perm (inc i)))]
    (into (conj (subvec without 0 j) elem) (subvec without j))))

(defn- reverse-segment [perm i j]
  (let [lo (min i j)
        hi (max i j)]
    (into (into (subvec perm 0 lo)
                (rseq (subvec perm lo (inc hi))))
          (subvec perm (inc hi)))))

(defn spt-schedule
  "Deterministic Shortest Processing Time (SPT) schedule with due-date and index tie-breaking."
  [instance]
  (when-not (instance? niki.optimizer.scheduling.PreparedScheduling instance)
    (throw (ex-info "Call scheduling/prepare-instance first" {:error :unprepared-instance})))
  (let [durations (:durations instance)
        due-dates (:due-dates instance)
        n (count durations)]
    (vec (sort-by (fn [idx] [(nth durations idx) (nth due-dates idx) idx])
                  (range n)))))

(defn edd-schedule
  "Deterministic Earliest Due Date (EDD) schedule with duration and index tie-breaking."
  [instance]
  (when-not (instance? niki.optimizer.scheduling.PreparedScheduling instance)
    (throw (ex-info "Call scheduling/prepare-instance first" {:error :unprepared-instance})))
  (let [durations (:durations instance)
        due-dates (:due-dates instance)
        n (count durations)]
    (vec (sort-by (fn [idx] [(nth due-dates idx) (nth durations idx) idx])
                  (range n)))))

(defn make-adapter
  "Build a bounded runtime adapter from a PreparedScheduling instance.
  Supports :first and :random initializers and :swap, :restart, :insert, :reverse
  operators using the unchanged G3/G4 core."
  [instance]
  (when-not (instance? niki.optimizer.scheduling.PreparedScheduling instance)
    (throw (ex-info "Call scheduling/prepare-instance first" {:error :unprepared-instance})))
  (let [started (System/nanoTime)
        n (count (:durations instance))
        normalization (scheduling/scheduling-normalization instance)
        canonicalize #(scheduling/validate-schedule instance %)
        initialize (fn [kind ^Random rng]
                     (case kind
                       :first (vec (range n))
                       :random (random-permutation n rng)
                       (throw (ex-info "Unsupported initializer" {:error :invalid-initializer :kind kind}))))
        propose (fn [kind current ^Random rng]
                  (case kind
                    :restart (random-permutation n rng)
                    (:swap :insert :reverse)
                    (if (nil? current)
                      (random-permutation n rng)
                      (let [current (canonicalize current)]
                        (if (<= n 1)
                          current
                          (let [[i j] (pick-two-distinct n rng)]
                            (case kind
                              :swap (assoc current i (nth current j) j (nth current i))
                              :insert (insert-at current i j)
                              :reverse (reverse-segment current i j))))))
                    (throw (ex-info "Unsupported operator" {:error :invalid-operator :kind kind}))))]
    {:domain :scheduling
      :id "scheduling-runtime-v1"
      :normalization normalization
      :initializers #{:random :first}
      :operators #{:swap :restart :insert :reverse}
      :initialize initialize
      :propose propose
      :canonicalize canonicalize
      :valid? (fn [solution]
                (try (= solution (canonicalize solution))
                     (catch Exception _ false)))
      :evaluate #(:objectives (scheduling/evaluate instance %))
      :preparation-timing {:elapsed-ns (- (System/nanoTime) started)}}))
