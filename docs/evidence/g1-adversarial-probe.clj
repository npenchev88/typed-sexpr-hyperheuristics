;; Supplemental bounded differential correctness probe, not optimizer evolution.
(require '[niki.optimizer.portfolio :as p]
         '[niki.optimizer.portfolio-reference :as r]
         '[clojure.java.io :as io])
(import '[java.math BigDecimal] '[java.util Random])
(let [rng (Random. 7102026)
      palette [-1.0 -0.9999999999999999 -1.0e-200 0.0 Double/MIN_VALUE
               1.0e-200 1.0 1.0e16 (Math/nextUp 1.0e16) 1.0e200 1.0e308 Double/MAX_VALUE]
      failures (atom [])
      count-panels 200]
  (dotimes [case-id count-panels]
    (let [k (inc (.nextInt rng 4))
          t (+ 2 (.nextInt rng 7))
          dates (mapv #(str "2000-01-" (format "%02d" (inc %))) (range t))
          panel {:k k :assets (mapv (fn [i]
                                     {:id (str "a" i) :dates dates
                                      :returns (mapv (fn [_] (nth palette (.nextInt rng (count palette))))
                                                     (range t))})
                                   (range k))}
          ;; Reference the EXACT normalized binary64 values, not their shortest
          ;; decimal spellings, to isolate intermediate arithmetic from coercion.
          ref-panel (update panel :assets
                            (fn [assets] (mapv #(update % :returns
                                                        (fn [xs] (mapv (fn [x] (BigDecimal. (double x))) xs)))
                                               assets)))
          selection (vec (range k))
          direct (p/evaluate (p/prepare-instance panel) selection)
          reference (r/evaluate (r/prepare-reference ref-panel) selection)]
      (doseq [key [:mean-return :sample-standard-deviation]]
        (let [actual (double (key direct)) expected (double (key reference))
              tolerance (max (* 1.0e-10 (abs expected)) (* 8.0 (Math/ulp expected)))
              passed (and (Double/isFinite actual) (Double/isFinite expected)
                          (if (zero? expected) (zero? actual)
                            (and (or (not= key :sample-standard-deviation) (pos? actual))
                                 (<= (abs (- actual expected)) tolerance))))]
          (when-not passed
            (swap! failures conj {:case case-id :key key :actual actual :expected expected
                                  :tolerance tolerance :panel panel}))))))
  (let [report {:status (if (empty? @failures) :passed :failed)
                :scope :bounded-differential-correctness-only :experiment-launched? false
                :seed 7102026 :panels-checked count-panels :objectives-checked (* 2 count-panels)
                :reference :covariance-of-exact-normalized-binary64-inputs
                :palette-size (count palette) :failures @failures}]
    (spit "docs/evidence/g1-adversarial-probe.edn" (str (pr-str report) "\n"))
    (prn report)
    (when (seq @failures) (throw (ex-info "Adversarial correctness probe failed" report)))))
