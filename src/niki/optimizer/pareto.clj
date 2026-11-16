(ns niki.optimizer.pareto
  "Two-objective minimization utilities. Exact comparisons over finite binary64
  coordinates; no approximate dominance, archive truncation or search runtime."
  (:require [niki.optimizer.portfolio :as portfolio])
  (:import [java.math BigDecimal MathContext]))

(defn- ensure! [condition code message]
  (when-not condition (throw (ex-info message {:error code}))))

(defn- coordinate! [value]
  (ensure! (contains? #{Byte Short Integer Long clojure.lang.BigInt
                       java.math.BigInteger clojure.lang.Ratio BigDecimal Float Double}
                     (class value))
           :invalid-coordinate "Expected a standard immutable numeric coordinate")
  (let [d (double value)]
    (ensure! (Double/isFinite d) :invalid-coordinate "Coordinates must be finite binary64 values")
    (if (zero? d) 0.0 d)))

(defn- point! [point]
  (ensure! (and (vector? point) (= 2 (count point))) :invalid-point
           "Expected a two-coordinate vector")
  (mapv coordinate! point))

(defn dominates?
  "True iff a is no worse in either minimized coordinate and strictly better in one."
  [a b]
  (let [[ax ay] (point! a) [bx by] (point! b)]
    (and (<= ax bx) (<= ay by) (or (< ax bx) (< ay by)))))

(defn front
  "Return the unique nondominated objective vectors, lexicographically sorted.
  Input is a vector of two-objective vectors. This archive is uncapped and uses
  exact comparisons over canonicalized binary64 coordinates, with no epsilon."
  [points]
  (ensure! (vector? points) :invalid-archive "Expected a vector of objective vectors")
  (:points
    (reduce (fn [{:keys [best-y] :as state} [_ y :as point]]
              (if (< y best-y)
                {:best-y y :points (conj (:points state) point)}
                state))
            {:best-y Double/POSITIVE_INFINITY :points []}
            (sort (mapv point! points)))))

(defn insert
  "Insert an objective vector, discard only dominated/duplicate vectors."
  [archive point]
  (ensure! (vector? archive) :invalid-archive "Expected an archive vector")
  (front (conj archive point)))

(defrecord Normalization [bounds constant-coordinates])

(def boundary-tolerance
  "Only normalized-coordinate overshoot up to this amount is rounded to an endpoint."
  1.0e-12)

(defn prepare-normalization
  "Prepare fixed [[min-x max-x] [min-y max-y]] bounds before comparing solvers.
  Constant-coordinate indices are exposed for degenerate-instance reporting."
  [bounds]
  (ensure! (and (vector? bounds) (= 2 (count bounds))) :invalid-bounds
           "Expected bounds for exactly two objectives")
  (let [bounds (mapv point! bounds)]
    (ensure! (every? (fn [[lo hi]] (<= lo hi)) bounds) :invalid-bounds
             "Lower bounds must not exceed upper bounds")
    (->Normalization bounds (vec (keep-indexed (fn [i [lo hi]] (when (== lo hi) i)) bounds)))))

(defn normalize-point
  "Map a point into a fixed unit box. Reject material excursions, not silently
  clipped. A constant objective maps to zero only when its value equals the bound.
  Normalization records returned by prepare-normalization are trusted values."
  [normalization point]
  (ensure! (instance? Normalization normalization) :invalid-normalization
           "Call prepare-normalization first")
  (mapv (fn [value [lo hi]]
          (if (== lo hi)
            (do (ensure! (== value lo) :outside-bounds "A constant objective differs from its bound")
                0.0)
            (let [v (BigDecimal. (double value))
                  lower (BigDecimal. (double lo))
                  upper (BigDecimal. (double hi))
                  z (.doubleValue (.divide (.subtract v lower) (.subtract upper lower)
                                           MathContext/DECIMAL128))]
              (ensure! (and (Double/isFinite z)
                            (<= (- boundary-tolerance) z (+ 1.0 boundary-tolerance)))
                       :outside-bounds "Objective lies outside its fixed bounds")
              (max 0.0 (min 1.0 z)))))
        (point! point) (:bounds normalization)))

(defn hypervolume-2d
  "Dominated union area for two minimized objectives and a fixed reference point.
  Reject any input outside the reference rectangle, including dominated points.
  Areas accumulate exactly from binary64 coordinates before final conversion."
  [points reference]
  (ensure! (vector? points) :invalid-archive "Expected an objective-vector collection")
  (let [[rx ry] (point! reference)
        points (mapv point! points)
        _ (ensure! (every? (fn [[x y]] (and (<= x rx) (<= y ry))) points)
                   :invalid-reference "Reference must be weakly worse than every point")
        right (BigDecimal. (double rx))
        {:keys [area]}
        (reduce (fn [{:keys [^BigDecimal top ^BigDecimal area]} [x y]]
                  (let [bottom (BigDecimal. (double y))
                        width (.subtract right (BigDecimal. (double x)))
                        height (.subtract top bottom)]
                    {:top bottom :area (.add area (.multiply width height))}))
                {:top (BigDecimal. (double ry)) :area BigDecimal/ZERO}
                (front points))
        result (.doubleValue ^BigDecimal area)]
    (ensure! (Double/isFinite result) :nonfinite-hypervolume
             "Hypervolume is not representable as a finite binary64 value")
    result))

(defn normalized-hypervolume
  "Normalize with the supplied fixed box; use reference [1.05 1.05], divisor 1.1025.
  Empty archives score zero. Degenerate axes follow the normalization's zero rule."
  [points normalization]
  (ensure! (vector? points) :invalid-archive "Expected an objective-vector collection")
  (ensure! (instance? Normalization normalization) :invalid-normalization
           "Call prepare-normalization first")
  (/ (hypervolume-2d (mapv #(normalize-point normalization %) points) [1.05 1.05])
     1.1025))

(defn portfolio-normalization
  "Instance-only box: exact-K extrema of mean return; conservative [0,max asset
  sample SD] risk range. Compute once per task and reuse across all comparisons.
  Uses no competitor outcomes. Prepared portfolio instances are trusted values."
  [instance]
  (ensure! (instance? niki.optimizer.portfolio.PreparedPortfolio instance)
           :unprepared-instance "Call portfolio/prepare-instance first")
  (let [rows (:returns instance)
        n (count rows) k (:k instance)
        totals (mapv (fn [xs]
                       (reduce (fn [^BigDecimal sum value]
                                 (.add sum (BigDecimal. (double value))))
                               BigDecimal/ZERO xs))
                     rows)
        ordered (vec (sort-by totals (range n)))
        worst-mean (:mean-return (portfolio/evaluate instance (subvec ordered 0 k)))
        best-mean (:mean-return (portfolio/evaluate instance (subvec ordered (- n k) n)))
        one-asset (assoc instance :k 1)
        max-risk (reduce max 0.0 (map #(:sample-standard-deviation
                                        (portfolio/evaluate one-asset [%]))
                                     (range n)))]
    (prepare-normalization [[(- best-mean) (- worst-mean)] [0.0 max-risk]])))
