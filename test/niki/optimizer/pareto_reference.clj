(ns niki.optimizer.pareto-reference
  "Test-only independent quadratic dominance and rational cell-grid union oracle.
  Deliberately does not require production Pareto/normalization helpers."
  (:import [java.math BigDecimal]))

(defn front [points]
  (let [points (vec (distinct (mapv #(mapv double %) points)))]
    (vec (sort (filter (fn [[x y]]
                         (not-any? (fn [[u v]]
                                     (and (<= u x) (<= v y) (or (< u x) (< v y))))
                                   points))
                       points)))))

(defn- exact [x] (rationalize (BigDecimal. (double x))))

(defn hypervolume [points reference]
  (let [points (mapv #(mapv exact %) points)
        [rx ry] (mapv exact reference)
        xs (sort (distinct (conj (mapv first points) rx)))
        ys (sort (distinct (conj (mapv second points) ry)))]
    (double (reduce + 0 (for [[x0 x1] (partition 2 1 xs)
                             [y0 y1] (partition 2 1 ys)
                             :when (some (fn [[x y]] (and (<= x x0) (<= y y0))) points)]
                         (* (- x1 x0) (- y1 y0)))))))

(defn normalize [point bounds]
  (mapv (fn [x [lo hi]]
          (let [x (exact x) lo (exact lo) hi (exact hi)]
            (if (= lo hi) 0.0 (double (/ (- x lo) (- hi lo))))))
        point bounds))
