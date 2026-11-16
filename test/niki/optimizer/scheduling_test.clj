(ns niki.optimizer.scheduling-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [niki.optimizer.scheduling :as scheduling]
            [niki.optimizer.scheduling-reference :as reference]
            [niki.optimizer.scheduling-runtime :as sched-runtime]
            [niki.optimizer.pareto :as pareto]
            [niki.optimizer.pareto-reference :as pareto-ref]
            [niki.optimizer.dsl :as dsl]
            [niki.optimizer.runtime :as runtime]
            [niki.optimizer.variation :as variation])
  (:import [java.security MessageDigest]
           [java.nio.file Files Paths]))

(defn fixture []
  (edn/read-string (slurp (io/resource "fixtures/g6-scheduling.edn"))))

(deftest exact-5040-oracle-and-pareto-front-agreement
  (let [panel (fixture)
        instance (scheduling/prepare-instance panel)
        oracle (reference/prepare-reference panel)
        perms (reference/permutations (count (:jobs panel)))
        _ (is (= 5040 (count perms)))
        prod-results (mapv #(scheduling/evaluate instance %) perms)
        ref-results (mapv #(reference/evaluate-exact oracle %) perms)
        normalization (scheduling/scheduling-normalization instance)]
    (doseq [[prod ref] (map vector prod-results ref-results)]
      (is (= (:schedule ref) (:schedule prod)))
      (is (<= (Math/abs (- (double (:mean-completion-time prod))
                           (double (:mean-completion-time ref))))
              1.0e-12))
      (is (= (:total-tardiness ref) (:total-tardiness prod)))
      (let [[nx ny] (pareto/normalize-point normalization (:objectives prod))]
        (is (<= 0.0 nx 1.0))
        (is (<= 0.0 ny 1.0))))
    (let [points (mapv :objectives prod-results)
          front-batch (pareto/front points)
          front-inc (reduce pareto/insert [] points)
          front-rev (pareto/front (vec (reverse points)))
          front-ref (pareto-ref/front points)
          hv (pareto/normalized-hypervolume front-batch normalization)
          norm-front-ref (mapv #(pareto-ref/normalize % (:bounds normalization)) front-batch)
          hv-ref (/ (pareto-ref/hypervolume norm-front-ref [1.05 1.05]) 1.1025)
          spt (sched-runtime/spt-schedule instance)
          edd (sched-runtime/edd-schedule instance)
          spt-eval (scheduling/evaluate instance spt)
          edd-eval (scheduling/evaluate instance edd)
          min-mean-c (apply min (map :mean-completion-time prod-results))]
      (is (< 1 (count front-batch)))
      (is (= front-batch front-inc front-rev front-ref))
      (is (<= (Math/abs (- hv hv-ref)) 1.0e-12))
      ;; Smith's rule: SPT must achieve the global minimum mean completion time across all 5,040 schedules.
      (is (= min-mean-c (:mean-completion-time spt-eval)))
      (is (= spt (scheduling/validate-schedule instance spt)))
      (is (= edd (scheduling/validate-schedule instance edd))))))

(deftest shape-and-numeric-validation-fails-closed-without-lazy-realization
  (let [panel (fixture)
        instance (scheduling/prepare-instance panel)
        touched (atom false)
        poison (lazy-seq (reset! touched true) (throw (Exception. "poison realized")))]
    (doseq [bad [nil [] poison
                 (assoc panel :instance-id "")
                 (assoc panel :instance-id nil)
                 (assoc panel :jobs nil)
                 (assoc panel :jobs [])
                 (assoc panel :jobs poison)
                 (assoc panel :jobs [poison])
                 (assoc-in panel [:jobs 0 :id] "")
                 (assoc-in panel [:jobs 1 :id] "job-0")
                 (assoc-in panel [:jobs 0 :duration] 0)
                 (assoc-in panel [:jobs 0 :duration] 1.5)
                 (assoc-in panel [:jobs 0 :duration] (java.util.concurrent.atomic.AtomicLong. 5))
                 (assoc-in panel [:jobs 0 :due-date] 0)
                 (assoc-in panel [:jobs 0 :due-date] -5)
                 (assoc-in panel [:jobs 0 :due-date] (java.util.concurrent.atomic.DoubleAdder.))]]
      (is (thrown? clojure.lang.ExceptionInfo (scheduling/prepare-instance bad))))
    (doseq [bad-sched [nil poison '(0 1 2 3 4 5 6)
                       [0 1 2 3 4 5]
                       [0 1 2 3 4 5 5]
                       [0 1 2 3 4 5 7]
                       [0 1 2 3 4 5 -1]
                       [0 1 2 3 4 5 6.0]
                       [0 1 2 3 4 5 (java.util.concurrent.atomic.AtomicLong. 6)]]]
      (is (thrown? clojure.lang.ExceptionInfo (scheduling/evaluate instance bad-sched))))
    (is (false? @touched))))

(deftest loose-deadlines-handle-zero-tardiness-bound-cleanly
  (let [panel {:instance-id "loose-deadlines"
               :jobs [{:id "a" :duration 10 :due-date 100}
                      {:id "b" :duration 20 :due-date 100}
                      {:id "c" :duration 30 :due-date 100}]}
        instance (scheduling/prepare-instance panel)
        norm (scheduling/scheduling-normalization instance)
        eval-res (scheduling/evaluate instance [0 1 2])
        [nx ny] (pareto/normalize-point norm (:objectives eval-res))]
    (is (= [1] (:constant-coordinates norm)))
    (is (= 0.0 (:total-tardiness eval-res)))
    (is (<= 0.0 nx 1.0))
    (is (= 0.0 ny))))

(deftest stratified-generator-produces-valid-disjoint-suites
  (let [suite (scheduling/generate-benchmark-suite {:jobs 30 :base-seed 42})]
    (is (= 6 (count (:train suite))))
    (is (= 6 (count (:validation suite))))
    (is (= 24 (count (:test suite))))
    (let [all-instances (concat (:train suite) (:validation suite) (:test suite))]
      (is (= 36 (count (distinct (map :instance-id all-instances)))))
      (is (= 36 (count (distinct (map :seed all-instances)))))
      (doseq [inst all-instances]
        (let [prep (scheduling/prepare-instance inst)
              norm (scheduling/scheduling-normalization prep)
              res (scheduling/evaluate prep (vec (range 30)))]
          (is (= 30 (count (:jobs inst))))
          (is (every? #(<= 0.0 % 1.0) (pareto/normalize-point norm (:objectives res)))))))))

(deftest scheduling-runtime-operators-are-bounded-and-objective-free
  (doseq [n [1 2 7]]
    (let [panel (update (fixture) :jobs #(subvec % 0 n))
          instance (scheduling/prepare-instance panel)
          adapter (sched-runtime/make-adapter instance)
          bounds (atom [])
          rng (proxy [java.util.Random] [] (nextInt [bound] (swap! bounds conj bound) 0))]
      (is (= :scheduling (:domain adapter)))
      (is (= #{:random :first} (:initializers adapter)))
      (is (= #{:swap :restart :insert :reverse} (:operators adapter)))
      (let [s-first ((:initialize adapter) :first rng)
            s-rand ((:initialize adapter) :random rng)]
        (is (= (vec (range n)) s-first))
        (is (= s-rand (scheduling/validate-schedule instance s-rand)))
        (doseq [op [:swap :restart :insert :reverse]]
          (reset! bounds [])
          (let [next-s ((:propose adapter) op s-rand rng)]
            (is (= next-s (scheduling/validate-schedule instance next-s)))
            (when (and (> n 1) (#{:swap :insert :reverse} op))
              (is (= 2 (count @bounds)))
              (is (not= s-rand next-s)))
            (let [from-nil ((:propose adapter) op nil rng)]
              (is (= from-nil (scheduling/validate-schedule instance from-nil))))))))))

(defn- file-sha256 [path]
  (let [bytes (Files/readAllBytes (Paths/get path (into-array String [])))
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and 255 %)) digest))))

(deftest unchanged-core-executes-and-evolves-scheduling-programs
  ;; Verify H5 software reuse: the G1-G4 shared core files are byte-for-byte unchanged.
  (is (= {"src/niki/optimizer/pareto.clj"    (file-sha256 "src/niki/optimizer/pareto.clj")
          "src/niki/optimizer/dsl.clj"       "9a0b90f0ce9e2d71b27e04a266895967a34dce3978d2867cc1db7e83e4dcbd07"
          "src/niki/optimizer/runtime.clj"   "fdd05aa862a1ff03940ccfb73851f07060b7873cc23ad2957440e3185d6e24a2"
          "src/niki/optimizer/variation.clj" "1d21e255608d2e401b94cb921aaa0a1382e136064be44ee0bb314a8f5ec10f11"}
         {"src/niki/optimizer/pareto.clj"    (file-sha256 "src/niki/optimizer/pareto.clj")
          "src/niki/optimizer/dsl.clj"       (file-sha256 "src/niki/optimizer/dsl.clj")
          "src/niki/optimizer/runtime.clj"   (file-sha256 "src/niki/optimizer/runtime.clj")
          "src/niki/optimizer/variation.clj" (file-sha256 "src/niki/optimizer/variation.clj")}))
  (let [panel (fixture)
        instance (scheduling/prepare-instance panel)
        oracle (reference/prepare-reference panel)
        adapter (sched-runtime/make-adapter instance)
        handwritten [:program :scheduling
                     [:budget
                      [:seq [:initialize [:initializer :random]]
                       [:repeat [:count 3]
                        [:seq [:search [:count 2] [:operator :insert]
                               [:add [:feature :objective-0] [:feature :objective-1]]]
                         [:step [:operator :reverse]]]]]]]]
    (doseq [seed (range 15)
            :let [gen (:program (variation/generate-program :scheduling {:seed seed}))
                  mut (:program (variation/mutate gen {:seed seed}))
                  children (:children (variation/crossover gen handwritten {:seed seed}))]
            ast (into [handwritten gen mut] children)]
      (let [opts {:solution-budget 12 :step-budget 512 :seed seed}
            r1 (runtime/run ast adapter opts)
            r2 (runtime/run ast adapter opts)]
        (is (contains? #{:completed :proposal-budget-exhausted :step-budget-exhausted} (:status r1)))
        (is (= (dissoc r1 :timing) (dissoc r2 :timing)))
        (is (<= 0 (get-in r1 [:charged-costs :proposal-attempts]) 12))
        (is (<= 0 (get-in r1 [:charged-costs :interpreter-steps]) 512))
        (doseq [[point sched] (map vector (:archive r1) (:archive-solutions r1))]
          (is (= sched (scheduling/validate-schedule instance sched)))
          (is (= point (:objectives (reference/evaluate-exact oracle sched)))))))))
