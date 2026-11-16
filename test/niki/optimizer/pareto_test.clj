(ns niki.optimizer.pareto-test
  (:require [clojure.test :refer [deftest is testing]]
            [niki.optimizer.pareto :as p]
            [niki.optimizer.portfolio :as portfolio]
            [niki.optimizer.portfolio-reference :as portfolio-reference]
            [niki.optimizer.pareto-reference :as reference]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(deftest custom-and-mutable-coordinates-are-rejected
  (let [mutable (doto (java.util.concurrent.atomic.DoubleAdder.) (.add 0.5))
        custom (proxy [java.math.BigDecimal] ["0.5"])]
    (doseq [value [mutable custom]]
      (is (thrown? clojure.lang.ExceptionInfo (p/dominates? [value 0] [1 1])))
      (is (thrown? clojure.lang.ExceptionInfo (p/front [[value 0]]))))))

(deftest archive-removes-dominated-points-and-duplicates
  (let [front (ns-resolve 'niki.optimizer.pareto 'front)
        insert (ns-resolve 'niki.optimizer.pareto 'insert)]
    (is (some? front) "Batch Pareto archive must exist")
    (is (some? insert) "Incremental archive insertion must exist")
    (when (and front insert)
      (let [points [[3 4] [1 5] [2 3] [4 1] [2 3] [2 4]]
            expected [[1.0 5.0] [2.0 3.0] [4.0 1.0]]]
        (is (= expected (front points)))
        (is (= expected (front (vec (reverse points)))))
        (is (= expected (reduce insert [] points)))
        (is (= expected (reduce insert [] (reverse points))))
        (is (= expected (insert expected [9 9])))
        (is (= [[0.0 0.0]] (front [[0 -0.0] [-0.0 0]])))
        (is (= [] (front [])))
        (doseq [bad [nil '([1 2]) [[1]] [[1 ##NaN]] [[1 2 3]]]]
          (is (thrown? clojure.lang.ExceptionInfo (front bad))))))))

(defn close? [a b] (<= (abs (- (double a) (double b))) 1.0e-12))

(deftest normalization-is-fixed-bounded-and-flags-constants
  (let [prepare (ns-resolve 'niki.optimizer.pareto 'prepare-normalization)
        normalize (ns-resolve 'niki.optimizer.pareto 'normalize-point)]
    (is (some? prepare))
    (is (some? normalize))
    (when (and prepare normalize)
      (let [box (prepare [[-2 2] [10 30]])
            flat (prepare [[1 1] [0 2]])]
        (is (= [] (:constant-coordinates box)))
        (is (= [0] (:constant-coordinates flat)))
        (is (= [0.25 0.5] (normalize box [-1 20])))
        (is (= [0.0 0.0] (normalize box [-2 10])))
        (is (= [1.0 1.0] (normalize box [2 30])))
        (is (= [0.0 0.5] (normalize flat [1 1])))
        (is (thrown? clojure.lang.ExceptionInfo (normalize flat [1.01 1])))
        (is (thrown? clojure.lang.ExceptionInfo (normalize box [-3 20])))
        (is (thrown? clojure.lang.ExceptionInfo (normalize box [0 31])))
        (is (thrown? clojure.lang.ExceptionInfo (normalize {} [0 0])))
        (let [unit (prepare [[0 1] [0 1]])]
          (is (= [0.0 1.0] (normalize unit [-5.0e-13 (+ 1.0 5.0e-13)])))
          (is (thrown? clojure.lang.ExceptionInfo (normalize unit [-1.0e-6 0]))))
        (is (= [0.5 0.5]
               (normalize (prepare [[(- Double/MAX_VALUE) Double/MAX_VALUE] [0 1]]) [0 0.5])))
        (is (= [1.0 0.5]
               (normalize (prepare [[1.0e16 10000000000000002.0] [0 1]])
                          [10000000000000002.0 0.5])))
        (is (close? 0.5 (first (normalize (prepare [[0 1.0e-300] [0 1]]) [5.0e-301 0])))))
      (doseq [bad [nil [] [[0 1]] [[1 0] [0 1]] [[0 ##Inf] [0 1]] [[0 1] [0 ##NaN]]
                   '([0 1] [0 1])]]
        (is (thrown? clojure.lang.ExceptionInfo (prepare bad)))))))

(deftest hypervolume-known-areas-and-reference-contract
  (let [hv (ns-resolve 'niki.optimizer.pareto 'hypervolume-2d)
        score (ns-resolve 'niki.optimizer.pareto 'normalized-hypervolume)]
    (is (some? hv))
    (is (some? score))
    (when (and hv score)
      (is (= 0.0 (hv [] [5 5])))
      (is (= 8.0 (hv [[1 4] [3 2]] [5 5])))
      (is (= 8.0 (hv [[3 2] [1 4] [4 4] [1 4]] [5 5])))
      (is (= 2.0 (hv [[-2 -1]] [0 0])))
      (is (= 0.0 (hv [[5 5]] [5 5])))
      (is (thrown? clojure.lang.ExceptionInfo (hv [[1 1] [6 6]] [5 5])))
      (is (thrown? clojure.lang.ExceptionInfo (hv [[0 6]] [5 5])))
      (is (thrown? clojure.lang.ExceptionInfo (hv [[0 0]] [##Inf 1])))
      (is (thrown? clojure.lang.ExceptionInfo (hv '([0 0]) [1 1])))
      (is (thrown? clojure.lang.ExceptionInfo (hv [[0 0]] [1.0e308 1.0e308])))
      (let [width (rationalize (java.math.BigDecimal. Double/MAX_VALUE))
            height (rationalize (java.math.BigDecimal. 1.0e-308))
            expected (double (* 2 width height))]
        (is (close? expected (hv [[(- Double/MAX_VALUE) 0]] [Double/MAX_VALUE 1.0e-308]))))
      (let [box (p/prepare-normalization [[0 10] [0 10]])]
        (is (= 0.0 (score [] box)))
        (is (= 1.0 (score [[0 0]] box)))
        (is (close? (/ (* 0.05 0.05) 1.1025) (score [[10 10]] box)))
        (is (thrown? clojure.lang.ExceptionInfo (score [[11 0]] box))))
      (is (= 1.0 (score [[2 3]] (p/prepare-normalization [[2 2] [3 3]]))))
      (is (thrown? clojure.lang.ExceptionInfo (score [] {}))))))

(deftest exhaustive-small-grid-agrees-with-independent-oracle
  (let [grid (vec (for [x [0.0 0.5 1.0] y [0.0 0.5 1.0]] [x y]))
        masks (range (bit-shift-left 1 (count grid)))
        ref [1.25 1.25]]
    (is (= 512 (count masks)))
    (doseq [mask masks]
      (let [points (vec (keep-indexed (fn [i point] (when (bit-test mask i) point)) grid))
            expected (reference/front points)
            area (p/hypervolume-2d points ref)
            trace (mapv #(p/hypervolume-2d % ref) (reductions p/insert [] points))]
        (is (= expected (p/front points)))
        (is (= expected (reduce p/insert [] points)))
        (is (close? (reference/hypervolume points ref) area))
        (is (= area (p/hypervolume-2d (vec (reverse points)) ref)))
        (is (= area (p/hypervolume-2d (into points points) ref)))
        (is (every? true? (map <= trace (rest trace))))))))

(deftest seeded-normalization-and-area-crosschecks
  (let [rng (java.util.Random. 7102026)
        bounds [[-2 3] [-5 7]]
        box (p/prepare-normalization bounds)]
    (dotimes [_ 100]
      (let [points (mapv (fn [_] [(- (* 5.0 (.nextDouble rng)) 2.0)
                                 (- (* 12.0 (.nextDouble rng)) 5.0)])
                         (range (inc (.nextInt rng 10))))
            normalized (mapv #(p/normalize-point box %) points)
            independent (mapv #(reference/normalize % bounds) points)]
        (is (every? true? (map close? (mapcat identity normalized) (mapcat identity independent))))
        (is (close? (/ (reference/hypervolume independent [1.05 1.05]) 1.1025)
                    (p/normalized-hypervolume points box)))
        (is (= (reference/front points) (p/front points)))))))

(deftest portfolio-bounds-use-feasible-k-subset-means
  (let [prepare (ns-resolve 'niki.optimizer.pareto 'portfolio-normalization)]
    (is (some? prepare) "Portfolio normalization must derive bounds from the instance")
    (when prepare
      (let [panel {:k 2 :assets (mapv (fn [i value]
                                       {:id (str i) :dates ["2000-01-01" "2000-01-02"]
                                        :returns [value value]})
                                     (range) [0.1 0.2 0.5])}
            instance (portfolio/prepare-instance panel)
            box (prepare instance)
            choices (vec (portfolio-reference/selections 3 2))]
        (is (close? -0.35 (get-in box [:bounds 0 0])))
        (is (close? -0.15 (get-in box [:bounds 0 1])))
        (is (= [1] (:constant-coordinates box)))
        (doseq [choice choices]
          (is (every? #(<= 0.0 % 1.0)
                      (p/normalize-point box (:objectives (portfolio/evaluate instance choice))))))
        (is (= [0 1] (:constant-coordinates (prepare (portfolio/prepare-instance (assoc panel :k 3)))))))
      (is (thrown? clojure.lang.ExceptionInfo (prepare {}))))))

(deftest full-tiny-portfolio-pipeline
  (let [prepare (ns-resolve 'niki.optimizer.pareto 'portfolio-normalization)]
    (is (some? prepare))
    (when prepare
      (let [panel (edn/read-string (slurp (io/resource "fixtures/g1-portfolio.edn")))
            instance (portfolio/prepare-instance panel)
            choices (vec (portfolio-reference/selections 15 5))
            points (mapv #(:objectives (portfolio/evaluate instance %)) choices)
            box (prepare instance)
            expected-front (reference/front points)
            normalized (mapv #(p/normalize-point box %) points)
            independent-front (reference/front (mapv #(reference/normalize % (:bounds box)) points))
            expected-hv (/ (reference/hypervolume independent-front [1.05 1.05]) 1.1025)]
        (is (= 3003 (count choices)))
        (is (= expected-front (p/front points)))
        (is (close? expected-hv (p/normalized-hypervolume points box)))
        (is (<= 0.0 (p/normalized-hypervolume points box) 1.0))
        (doseq [point normalized]
          (is (every? #(<= 0.0 % 1.0) point)))))))

(deftest strict-minimization-dominance
  (let [dominates? (try (requiring-resolve 'niki.optimizer.pareto/dominates?)
                       (catch java.io.FileNotFoundException _ nil))]
    (is (some? dominates?) "G2 dominance implementation must exist")
    (when dominates?
      (is (true? (dominates? [1 2] [2 3])))
      (is (true? (dominates? [1 2] [1 3])))
      (is (false? (dominates? [1 2] [1 2])))
      (is (false? (dominates? [1 3] [2 2])))
      (is (false? (dominates? [2 3] [1 2])))
      (doseq [bad [nil [] [1] [1 2 3] '(1 2) [##NaN 1] [0 ##Inf] [nil 0] ["0" 1]]]
        (is (thrown? clojure.lang.ExceptionInfo (dominates? bad [0 0])))))))
