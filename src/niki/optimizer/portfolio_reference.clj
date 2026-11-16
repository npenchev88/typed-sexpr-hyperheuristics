(ns niki.optimizer.portfolio-reference
  "Development-only independent reference using rational sample covariance.
  Shares input/selection validation with the evaluator, but no numerical path.
  Do not use this slow reference as an uncharged optimizer primitive."
  (:require [niki.optimizer.portfolio :as portfolio])
  (:import [java.math BigDecimal MathContext]))

(defn selections
  "Lazily enumerate every K-of-N subset in lexicographic order, without repeats.
  This exact reference is intended for small correctness cases only."
  [n k]
  (when-not (and (integer? n) (integer? k) (<= 0 k n))
    (throw (ex-info "Expected integer dimensions with 0 <= K <= N"
                    {:error :invalid-subset-dimensions})))
  (letfn [(walk [start left prefix]
            (lazy-seq
              (if (zero? left)
                (list prefix)
                (mapcat #(walk (inc %) (dec left) (conj prefix %))
                        (range start (inc (- n left)))))))]
    (walk 0 k [])))

(defn prepare-reference
  "Calculate independent rational asset means and sample covariance.
  Default :normalized-binary64 uses the prepared production inputs, preserving
  each double's exact binary value via BigDecimal(double) before rationalizing.
  :exact-input retains original ratios/integers and decimal values (floating
  numbers use their decimal spelling) for input-quantization diagnostics.
  Both modes share validation only, not production objective arithmetic."
  ([panel] (prepare-reference panel :normalized-binary64))
  ([panel input-mode]
   (when-not (#{:normalized-binary64 :exact-input} input-mode)
     (throw (ex-info "Unknown reference input mode"
                     {:error :invalid-reference-input-mode :input-mode input-mode})))
   (let [instance (portfolio/prepare-instance panel)
         rows (case input-mode
                :normalized-binary64
                (mapv #(mapv (fn [x] (rationalize (BigDecimal. (double x)))) %)
                      (:returns instance))
                :exact-input
                (mapv #(mapv rationalize (:returns %)) (:assets panel)))
         t (count (:dates instance))
         means (mapv #(/ (reduce +' %) t) rows)
         deviations (mapv (fn [xs mu] (mapv #(-' % mu) xs)) rows means)
         covariance (mapv (fn [a]
                            (mapv (fn [b] (/ (reduce +' (map *' a b)) (dec t)))
                                  deviations))
                          deviations)]
     {:instance instance :input-mode input-mode
      :exact-means means :exact-covariance covariance})))

(defn- rational-sqrt [value]
  ;; Take the root before conversion to binary64. Converting the squared
  ;; quantity first would overflow/underflow for otherwise representable risks.
  (let [^BigDecimal decimal (if (ratio? value)
                             (.divide (bigdec (numerator value))
                                      (bigdec (denominator value)) MathContext/DECIMAL128)
                             (bigdec value))]
    (double (.sqrt decimal MathContext/DECIMAL128))))

(defn evaluate
  "Calculate mu^T w and w^T Sigma w without constructing a portfolio series.
  The exact rational variance is retained; standard deviation is binary64."
  [{:keys [instance exact-means exact-covariance]} selection]
  (let [selection (portfolio/validate-selection instance selection)
        k (:k instance)
        mean (/ (reduce +' (map exact-means selection)) k)
        variance (/ (reduce +' (for [i selection j selection]
                                 (get-in exact-covariance [i j]))) (*' k k))
        risk (rational-sqrt variance)]
    {:selection selection :exact-mean mean :exact-variance variance
     :mean-return (double mean) :sample-standard-deviation risk
     :objectives [(- (double mean)) risk]}))
