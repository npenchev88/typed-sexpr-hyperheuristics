(ns niki.optimizer.portfolio-reference-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [niki.optimizer.portfolio-test :refer [fixture close?]]
            [niki.optimizer.portfolio :as portfolio]
            [niki.optimizer.portfolio-reference :as reference]))

(deftest default-reference-compares-normalized-binary64-inputs
  (doseq [rows [[[1.0e23 0.0] [0.0 2.0e22] [0.0 4.0e22] [0.0 4.0e22]]
               [[0.3 0.1] [0.0 0.2]]
               [[9223372036854775807 9223372036854775000]]]]
    (let [k (count rows)
          panel (fixture k rows)
          selection (vec (range k))
          direct (portfolio/evaluate (portfolio/prepare-instance panel) selection)
          prepared (reference/prepare-reference panel)
          independent (reference/evaluate prepared selection)]
      (is (= :normalized-binary64 (:input-mode prepared)))
      ;; Strict equality here: broad absolute tolerances hide cancellation risk.
      (is (= (:mean-return direct) (:mean-return independent)))
      (is (= (:sample-standard-deviation direct)
             (:sample-standard-deviation independent)))
      (is (= (:objectives direct) (:objectives independent))))))

(deftest explicit-reference-input-modes
  (doseq [rows [[[1.0e23 0.0] [0.0 2.0e22] [0.0 4.0e22] [0.0 4.0e22]]
               [[0.3 0.1] [0.0 0.2]]
               [[9223372036854775807 9223372036854775000]]
               [[0.1M 0.2M]]
               [[1/3 2/3]]]]
    (let [panel (fixture (count rows) rows)
          selection (vec (range (count rows)))
          normalized (reference/prepare-reference panel)
          original (reference/prepare-reference panel :exact-input)]
      (is (= normalized (reference/prepare-reference panel :normalized-binary64)))
      (is (= :exact-input (:input-mode original)))
      (is (not= (:exact-means normalized) (:exact-means original)))
      (is (not= (:exact-variance (reference/evaluate normalized selection))
                (:exact-variance (reference/evaluate original selection)))))))

(deftest reference-rejects-unknown-input-modes
  (doseq [mode [nil :unknown "normalized-binary64" {}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (reference/prepare-reference (fixture 1 [[0 0]]) mode)))))

(deftest independent-covariance-reference
  (let [prepared (reference/prepare-reference (fixture 2 [[1/10 0 -1/10] [0 1/5 1/10]])
                                            :exact-input)
        result (reference/evaluate prepared [0 1])]
    (is (= [0 1/10] (:exact-means prepared)))
    (is (= [[1/100 -1/200] [-1/200 1/100]] (:exact-covariance prepared)))
    (is (= 1/20 (:exact-mean result)))
    (is (= 1/400 (:exact-variance result)))
    (is (close? 1/20 (:mean-return result)))
    (is (close? 1/20 (:sample-standard-deviation result)))))

(deftest direct-risk-preserves-near-constant-asset-dispersion
  (doseq [[k rows selection expected-variance]
          [[1 [[10000000000000000.0 10000000000000002.0]] [0] 2]
           [2 [[10000000000000000.0 10000000000000002.0]
               [10000000000000000.0 10000000000000000.0]] [0 1] 1/2]]]
    (let [panel (fixture k rows)
          direct (portfolio/evaluate (portfolio/prepare-instance panel) selection)
          independent (reference/evaluate (reference/prepare-reference panel) selection)
          expected-risk (Math/sqrt (double expected-variance))]
      (is (= expected-variance (:exact-variance independent)))
      (is (close? expected-risk (:sample-standard-deviation independent)))
      (is (close? (:sample-standard-deviation independent)
                  (:sample-standard-deviation direct)))
      (is (close? expected-risk (second (:objectives direct))))
      (is (= (- (:mean-return direct)) (first (:objectives direct)))))))

(deftest covariance-reference-does-not-square-into-overflow-or-underflow
  (doseq [magnitude [1.0e200 1.0e-200]]
    (let [prepared (reference/prepare-reference (fixture 1 [[0.0 magnitude]]))
          result (reference/evaluate prepared [0])
          expected (/ magnitude (Math/sqrt 2.0))]
      (is (pos? (:exact-variance result)))
      (is (Double/isFinite (:sample-standard-deviation result)))
      (is (< (abs (- 1.0 (/ (:sample-standard-deviation result) expected))) 1.0e-12)))))

(deftest exact-reference-promotes-integral-arithmetic
  (doseq [[k rows expected-means expected-covariance expected-mean expected-variance]
          [[1 [[9223372036854775807 9223372036854775807]]
            [9223372036854775807] [[0]] 9223372036854775807 0]
           ;; Mean fits Long, but each squared deviation does not.
           [1 [[0 8000000000]]
            [4000000000] [[32000000000000000000N]] 4000000000 32000000000000000000N]
           ;; Per-asset means fit Long; their selected sum does not.
           [3 (vec (repeat 3 [4000000000000000000 4000000000000000000]))
            (vec (repeat 3 4000000000000000000)) (vec (repeat 3 [0 0 0]))
            4000000000000000000 0]
           ;; Individual covariances fit Long; the selected covariance sum does not.
           [2 [[0 4000000000] [0 4000000000]]
            [2000000000 2000000000]
            [[8000000000000000000 8000000000000000000]
             [8000000000000000000 8000000000000000000]]
            2000000000 8000000000000000000]]]
    (is (= {:means expected-means :covariance expected-covariance
            :mean expected-mean :variance expected-variance
            :risk-correct? true :objectives-correct? true}
           (let [prepared (reference/prepare-reference (fixture k rows) :exact-input)
                 result (reference/evaluate prepared (vec (range k)))
                 expected-risk (Math/sqrt (double expected-variance))]
             {:means (:exact-means prepared) :covariance (:exact-covariance prepared)
              :mean (:exact-mean result) :variance (:exact-variance result)
              :risk-correct? (close? expected-risk (:sample-standard-deviation result))
              :objectives-correct? (every? true? (map close?
                                                    [(- (double expected-mean)) expected-risk]
                                                    (:objectives result)))})))))

(deftest exhaustive-direct-versus-covariance
  (let [rows (mapv (fn [i]
                     (mapv (fn [t]
                             (/ (- (mod (+ (* i i) (* t t) (* 7 i t) (* 3 i) (* 11 t)) 97)
                                   48)
                                1000))
                           (range 12)))
                   (range 15))
        panel (edn/read-string (slurp (io/resource "fixtures/g1-portfolio.edn")))
        instance (portfolio/prepare-instance panel)
        prepared (reference/prepare-reference panel)
        choices (vec (reference/selections 15 5))]
    (is (= (fixture 5 rows) (select-keys panel [:k :assets])))
    (is (= :synthetic-development-only (:source panel)))
    (is (= 3003 (count choices)))
    (doseq [selection choices]
      (let [direct (portfolio/evaluate instance selection)
            independent (reference/evaluate prepared selection)]
        (is (close? (:mean-return independent) (:mean-return direct)) (str selection " mean"))
        (is (close? (:sample-standard-deviation independent)
                    (:sample-standard-deviation direct)) (str selection " risk"))
        (is (every? #(Double/isFinite (double %)) (:objectives direct)))
        (is (= direct (portfolio/evaluate instance (vec (reverse selection)))))))))

(deftest singular-and-tied-covariance-cases
  (doseq [rows [[[0 0 0] [0 0 0]]
               [[1/10 -1/10] [1/10 -1/10]]
               [[1/10 -1/10] [-1/10 1/10]]
               [[1/10 1/10] [1/10 1/10]]
               [[1/10 -1/10 0] [1/10 -1/10 0] [1/10 -1/10 0]]]]
    (let [k (count rows)
          panel (fixture k rows)
          direct (portfolio/evaluate (portfolio/prepare-instance panel) (vec (range k)))
          independent (reference/evaluate (reference/prepare-reference panel) (vec (range k)))]
      (is (close? (:mean-return independent) (:mean-return direct)))
      (is (close? (:sample-standard-deviation independent)
                  (:sample-standard-deviation direct))))))

(deftest exact-subset-enumeration
  (let [selections (ns-resolve 'niki.optimizer.portfolio-reference 'selections)]
    (is (some? selections) "The exact subset enumerator must exist")
    (when selections
      (let [actual (vec (selections 15 5))
            bitmask-reference (set (for [mask (range (bit-shift-left 1 15))
                                        :when (= 5 (Long/bitCount mask))]
                                    (vec (filter #(bit-test mask %) (range 15)))))]
        (is (= 3003 (count actual)))
        (is (= 3003 (count (set actual))))
        (is (= bitmask-reference (set actual)))
        (is (= (vec (sort actual)) actual))
        (is (= [0 1 2 3 4] (first actual)))
        (is (= [10 11 12 13 14] (last actual)))
        (is (= [[]] (vec (selections 0 0))))
        (is (= [[]] (vec (selections 3 0))))
        (is (= [[0 1 2]] (vec (selections 3 3))))
        (doseq [[n k] [[-1 0] [3 4] [3 -1] [3 1.5]]]
          (is (thrown? clojure.lang.ExceptionInfo (doall (selections n k)))))))))

