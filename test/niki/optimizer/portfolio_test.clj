(ns niki.optimizer.portfolio-test
  (:require [clojure.test :refer [deftest is testing]]
            [niki.optimizer.portfolio :as portfolio]))

(defn close? [expected actual]
  (<= (abs (- (double expected) (double actual)))
      (+ 1.0e-12 (* 1.0e-10 (abs (double expected))))))

(defn fixture [k rows]
  {:k k
   :assets (mapv (fn [i values]
                   {:id (keyword (str "asset-" i))
                    :dates (mapv #(str "2000-01-" (format "%02d" (inc %)))
                                 (range (count values)))
                    :returns values})
                 (range) rows)})

(deftest equal-weight-return-and-sample-risk
  (let [instance (portfolio/prepare-instance
                  (fixture 2 [[1/10 0 -1/10] [0 1/5 1/10]]))
        result (portfolio/evaluate instance [0 1])]
    (is (= [0 1] (:selection result)))
    (is (= 3 (count (:portfolio-returns result))))
    (is (every? true? (map close? [1/20 1/10 0] (:portfolio-returns result))))
    (is (close? 1/20 (:mean-return result)))
    (is (close? 1/20 (:sample-standard-deviation result)))
    (is (= 2 (count (:objectives result))))
    (is (every? true? (map close? [-1/20 1/20] (:objectives result))))))

(deftest stable-objectives-for-extreme-finite-magnitudes
  (doseq [[rows expected-mean expected-risk]
          [[[[1.0e308 1.0e308] [1.0e308 1.0e308]] 1.0e308 0.0]
           [[[0.0 1.0e200]] 5.0e199 (/ 1.0e200 (Math/sqrt 2.0))]
           [[[0.0 1.0e-200]] 5.0e-201 (/ 1.0e-200 (Math/sqrt 2.0))]]]
    (let [result (portfolio/evaluate (portfolio/prepare-instance (fixture (count rows) rows))
                                     (vec (range (count rows))))]
      (is (Double/isFinite (:mean-return result)))
      (is (Double/isFinite (:sample-standard-deviation result)))
      (is (< (abs (- 1.0 (/ (:mean-return result) expected-mean))) 1.0e-12))
      (if (zero? expected-risk)
        (is (zero? (:sample-standard-deviation result)))
        (is (< (abs (- 1.0 (/ (:sample-standard-deviation result) expected-risk))) 1.0e-12))))))

(deftest direct-risk-retains-intermediate-cancellation-residuals
  ;; Exact selected sums differ by one, although both displayed equal-weight
  ;; returns round to 5e15. First-observation binary64 shifts erase that residual.
  (let [instance (portfolio/prepare-instance
                  (fixture 2 [[10000000000000000.0 1.0]
                              [0.0 10000000000000000.0]]))
        result (portfolio/evaluate instance [0 1])
        expected-risk (Math/sqrt (double 1/8))]
    (is (= [5.0e15 5.0e15] (:portfolio-returns result)))
    (is (= 5.0e15 (:mean-return result)))
    (is (close? expected-risk (:sample-standard-deviation result)))
    (is (close? expected-risk (second (:objectives result))))
    (is (= result (portfolio/evaluate instance [1 0])))))

(deftest degenerate-panels-and-sample-denominator
  (doseq [[k rows expected-mean expected-risk]
          [[2 [[0 0 0] [0 0 0]] 0 0]
           [2 [[1/10 -1/10] [1/10 -1/10]] 0 (Math/sqrt 1/50)]
           [2 [[1/10 -1/10] [-1/10 1/10]] 0 0]
           [2 [[1/10 1/10] [1/10 1/10]] 1/10 0]
           [1 [[0 1/5]] 1/10 (Math/sqrt 1/50)]
           [1 [[-1 -1]] -1 0]
           [3 [[1/10 -1/10 0] [1/10 -1/10 0] [1/10 -1/10 0]] 0 1/10]]]
    (let [instance (portfolio/prepare-instance (fixture k rows))
          result (portfolio/evaluate instance (vec (range k)))]
      (is (close? expected-mean (:mean-return result)))
      (is (close? expected-risk (:sample-standard-deviation result)))
      (is (every? #(Double/isFinite (double %)) (:objectives result))))))

(deftest selections-enforce-exact-cardinality-and-feasibility
  (let [instance (portfolio/prepare-instance (fixture 2 [[0 1/10] [1/10 0] [0 0]]))]
    (is (= (portfolio/evaluate instance [0 1]) (portfolio/evaluate instance [1 0])))
    (doseq [selection [nil [] [0] [0 1 2] [0 0] [-1 1] [0 3] [0 1.0] [0 nil]
                       #{0 1} "01"]]
      (testing (pr-str selection)
        (is (thrown? clojure.lang.ExceptionInfo (portfolio/evaluate instance selection)))))
    (is (thrown? clojure.lang.ExceptionInfo (portfolio/evaluate {} [0 1])))))

(deftest root-panel-must-be-a-map-without-realizing-sequences
  (let [valid (fixture 1 [[0 0]])
        realized? (atom false)
        poison (lazy-seq
                 (reset! realized? true)
                 (throw (ex-info "Bounded lazy-root poison" {:probe :realized})))
        error-code (fn [panel]
                     (try (portfolio/prepare-instance panel) :accepted
                          (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))]
    (is (= :invalid-panel (error-code (list :k 1 :assets (:assets valid)))))
    (is (= :invalid-panel (error-code poison)))
    (is (false? @realized?))))

(deftest assets-must-be-maps-before-field-access
  (let [valid (fixture 1 [[0 0]])
        asset (first (:assets valid))]
    (doseq [invalid [nil [] '(:id :asset-0) (java.util.HashMap. asset)]]
      (let [panel (assoc valid :assets [asset invalid])]
        (is (= :invalid-assets
               (try (portfolio/prepare-instance panel) :accepted
                    (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))))

(deftest returns-accept-only-standard-immutable-numbers
  (doseq [value [(doto (java.util.concurrent.atomic.DoubleAdder.) (.add 0.5))
                 (doto (java.util.concurrent.atomic.DoubleAdder.) (.add -1.5))
                 (java.util.concurrent.atomic.AtomicLong. 1)
                 (proxy [Number] []
                   (intValue [] 1) (longValue [] 1)
                   (floatValue [] 1.0) (doubleValue [] 1.0))]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (portfolio/prepare-instance (fixture 1 [[value 0]])))))
  (doseq [value [(byte 1) (short 1) (int 1) (long 1) (bigint 1) (biginteger 1)
                 1/3 0.3M (float 0.3) (double 0.3) -1]]
    (let [instance (portfolio/prepare-instance (fixture 1 [[value 0]]))]
      (is (= [[(double value) 0.0]] (:returns instance)))
      (is (every? #(instance? Double %) (first (:returns instance))))))
  ;; Exact inputs below -1 must not become legal by rounding to binary64 -1.
  (doseq [value [-1.00000000000000000000000000001M
                 -100000000000000000000000000001/100000000000000000000000000000]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (portfolio/prepare-instance (fixture 1 [[value 0]]))))))

(deftest malformed-return-panels-fail-closed
  (let [valid (fixture 1 [[0 1/10] [1/10 0]])
        invalid-panels
        [nil {} 42 "panel" []
         (assoc valid :assets [])
         (assoc valid :assets {})
         (assoc valid :assets [nil])
         (assoc-in valid [:assets 0 :returns] nil)
         (assoc-in valid [:assets 0 :returns] '(0 1/10))
         (assoc-in valid [:assets 0 :returns 0] false)
         (assoc-in valid [:assets 0 :returns 0] 1.0e1000M)
         (assoc-in valid [:assets 0 :dates] nil)
         (assoc valid :k 0) (assoc valid :k 3) (assoc valid :k 1.5)
         (assoc-in valid [:assets 0 :id] nil)
         (assoc-in valid [:assets 0 :id] " ")
         (assoc-in valid [:assets 1 :id] :asset-0)
         (assoc-in valid [:assets 0 :returns] [0])
         (assoc-in valid [:assets 1 :dates] ["2000-01-01" "2000-01-03"])
         (update valid :assets #(mapv (fn [a] (assoc a :dates ["2000-01-01" "2000-01-01"])) %))
         (update valid :assets #(mapv (fn [a] (assoc a :dates ["2000-01-02" "2000-01-01"])) %))
         (assoc-in valid [:assets 0 :dates 0] "not-a-date")
         (assoc-in valid [:assets 0 :dates 0] "2000-02-30")
         (fixture 1 [[0]]) (fixture 1 [[]])
         (assoc-in valid [:assets 0 :returns 0] ##NaN)
         (assoc-in valid [:assets 0 :returns 0] ##Inf)
         (assoc-in valid [:assets 0 :returns 0] ##-Inf)
         (assoc-in valid [:assets 0 :returns 0] nil)
         (assoc-in valid [:assets 0 :returns 0] "0.1")
         (assoc-in valid [:assets 0 :returns 0] -1.01)]]
    (doseq [panel invalid-panels]
      (testing (pr-str panel)
        (is (thrown? clojure.lang.ExceptionInfo (portfolio/prepare-instance panel)))))))
