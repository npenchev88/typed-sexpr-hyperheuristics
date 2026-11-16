(ns niki.optimizer.portfolio
  "Equal-weight, fixed-cardinality portfolio objectives from aligned simple returns.
  This namespace does not ingest prices, trade assets, or annualize results."
  (:require [clojure.string :as str])
  (:import [java.math BigDecimal MathContext]
           [java.time LocalDate]
           [java.time.format DateTimeParseException]))

(defrecord PreparedPortfolio [k asset-ids dates returns])

(defn- ensure! [condition code message]
  (when-not condition (throw (ex-info message {:error code}))))

(defn- finite-number? [value]
  (and (number? value)
       (try (Double/isFinite (double value))
            (catch ArithmeticException _ false))))

(defn- valid-date? [value]
  (and (string? value)
       (boolean (re-matches #"\d{4}-\d{2}-\d{2}" value))
       (try (LocalDate/parse value) true
            (catch DateTimeParseException _ false))))

(defn- normalize-return [value]
  ;; Exact classes exclude mutable/custom Number subclasses, including subclasses
  ;; of otherwise supported Java numeric types.
  (ensure! (contains? #{Byte Short Integer Long clojure.lang.BigInt
                       java.math.BigInteger clojure.lang.Ratio BigDecimal Float Double}
                     (class value))
           :invalid-return "Simple returns must be standard immutable numbers")
  (let [normalized (try (double value) (catch ArithmeticException _ ##NaN))]
    (ensure! (and (<= -1 value) (Double/isFinite normalized) (<= -1.0 normalized))
             :invalid-return "Simple returns must be finite numbers >= -1")
    normalized))

(defn prepare-instance
  "Validate an asset-major simple-return panel with :k and an :assets vector.
  Each asset needs a unique keyword/nonblank-string :id, a strictly increasing
  ISO-date vector, and a finite :returns vector of the same length (T >= 2).
  All assets must share identical dates. Simple returns below -1 are invalid.
  Standard immutable numbers are converted once to binary64; missing values
  are never filled implicitly."
  [panel]
  (ensure! (map? panel) :invalid-panel "Expected a panel map")
  (let [{:keys [k assets]} panel]
    (ensure! (and (vector? assets) (seq assets) (every? map? assets)) :invalid-assets
             "Expected a nonempty vector of asset maps")
    (ensure! (and (integer? k) (<= 1 k (count assets))) :invalid-k
             "K must be an integer between one and the asset count")
    (let [ids (mapv :id assets)
          dates (:dates (first assets))]
      (ensure! (every? #(or (keyword? %) (and (string? %) (not (str/blank? %)))) ids)
               :invalid-asset-id "Asset IDs must be keywords or nonblank strings")
      (ensure! (= (count ids) (count (set ids))) :duplicate-assets
               "Asset IDs must be unique")
      (ensure! (and (vector? dates) (<= 2 (count dates))
                    (every? valid-date? dates)
                    (every? neg? (map compare dates (rest dates))))
               :invalid-dates "Expected at least two unique increasing ISO dates")
      (let [returns (mapv (fn [asset]
                            (ensure! (= dates (:dates asset)) :unaligned-dates
                                     "Every asset must use the identical date vector")
                            (let [xs (:returns asset)]
                              (ensure! (and (vector? xs) (= (count dates) (count xs)))
                                       :invalid-return-shape "Returns must match the date vector")
                              (mapv normalize-return xs)))
                          assets)]
        (->PreparedPortfolio k ids dates returns)))))

(defn validate-selection
  "Return sorted unique asset indices; require exactly K distinct legal members.
  Only vectors are accepted, so accidental sets/maps and unbounded lazy inputs
  cannot silently change representation. Prepared instances are trusted values
  returned by prepare-instance, not a serialization or security boundary."
  [instance selection]
  (ensure! (instance? PreparedPortfolio instance) :unprepared-instance
           "Call prepare-instance before evaluating a portfolio")
  (let [k (:k instance) n (count (:returns instance))]
    (ensure! (and (vector? selection) (= k (count selection))
                  (every? #(and (integer? %) (<= 0 %) (< % n)) selection)
                  (= k (count (set selection))))
             :invalid-selection "Expected exactly K distinct valid asset indices")
    (vec (sort selection))))

(defn evaluate
  "Return daily mean and sample standard deviation; both objectives minimized.
  Accumulates the direct time series exactly from normalized binary64 inputs;
  only final divisions and square root round to DECIMAL128, then binary64.
  Risk retains dispersion even when displayed portfolio returns round flat.
  Equal weights are reset analytically for every observation; this is not a
  buy-and-hold or transaction-cost simulation."
  [{:keys [returns] :as instance} selection]
  (let [selection (validate-selection instance selection)
        selected (mapv returns selection)
        k (count selection)
        t (count (first selected))
        k-decimal (bigdec k)
        t-decimal (bigdec t)
        ;; BigDecimal(double), not valueOf, preserves the actual binary64 input.
        ;; S_t is the exact selected sum at observation t; Q is their exact total.
        sums (apply mapv (fn [& xs]
                           (reduce (fn [^BigDecimal total x]
                                     (.add total (BigDecimal. (double x))))
                                   BigDecimal/ZERO xs))
                    selected)
        total (reduce (fn [^BigDecimal sum ^BigDecimal value] (.add sum value))
                      BigDecimal/ZERO sums)
        series (mapv (fn [^BigDecimal value]
                       (.doubleValue (.divide value k-decimal MathContext/DECIMAL128)))
                     sums)
        mean (.doubleValue (.divide total (bigdec (*' k t)) MathContext/DECIMAL128))
        ;; d_t = T*S_t-Q; variance = sum(d_t^2)/(K^2*T^2*(T-1)).
        ;; Keep subtraction, weighting residuals, and squared magnitudes exact.
        variance-numerator
        (reduce (fn [^BigDecimal numerator ^BigDecimal value]
                  (let [deviation (.subtract (.multiply t-decimal value) total)]
                    (.add numerator (.multiply deviation deviation))))
                BigDecimal/ZERO sums)
        variance-denominator (bigdec (*' k k t t (dec t)))
        risk (.doubleValue
               (.sqrt (.divide variance-numerator variance-denominator MathContext/DECIMAL128)
                      MathContext/DECIMAL128))]
    (ensure! (and (finite-number? mean) (finite-number? risk)
                  (every? finite-number? series))
             :nonfinite-objective "Numerical calculation produced nonfinite values")
    {:selection selection :portfolio-returns series
     :mean-return mean :sample-standard-deviation risk
     :objectives [(- mean) risk]}))
