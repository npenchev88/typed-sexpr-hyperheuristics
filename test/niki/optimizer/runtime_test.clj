(ns niki.optimizer.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [niki.optimizer.pareto :as pareto]
            [niki.optimizer.portfolio :as portfolio]))

(defn run-runtime [program adapter options]
  (when-let [f (try (requiring-resolve 'niki.optimizer.runtime/run)
                   (catch java.io.FileNotFoundException _ nil))]
    (f program adapter options)))

(defn program [body] [:program :portfolio [:budget body]])
(def opts {:solution-budget 10 :step-budget 1000 :seed 17})
(def adapter
  {:domain :portfolio :id "test-v1"
   :normalization (pareto/prepare-normalization [[0 10] [0 10]])
   :initializers #{:first :random} :operators #{:swap :restart}
   :initialize (fn [_ _] [5]) :propose (fn [_ current _] [(dec (or (first current) 5))])
   :canonicalize identity :valid? #(and (vector? %) (= 1 (count %)))
   :evaluate (fn [[x]] [(double x) (double (- 10 x))])})
(def zero-costs {:proposal-attempts 0 :valid-candidates 0 :unique-candidates 0
                 :cache-hits 0 :objective-calculations 0 :interpreter-steps 3})

(deftest noop-visits-both-protected-wrappers
  (let [r (run-runtime (program [:noop]) adapter opts)]
    (is (= :completed (:status r)))
    (is (= zero-costs (:charged-costs r)))
    (is (= [] (:archive r) (:archive-solutions r)))
    (is (nil? (:current r)))
    (is (= 17 (:seed r)))
    (is (= "test-v1" (:adapter-id r)))
    (is (re-matches #"[0-9a-f]{64}" (or (:program-id r) "")))))

(defn thrown-error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))

(deftest preflight-fails-closed-before-launch
  (doseq [bad [nil {} (assoc opts :extra 1) (dissoc opts :seed)
               (assoc opts :solution-budget -1) (assoc opts :step-budget 1.0)
               (assoc opts :solution-budget 1000001) (assoc opts :step-budget 10000001)
               (assoc opts :seed 9223372036854775808N) (assoc opts :cache? nil)
               (assoc opts :trace? 1)]]
    (is (= :invalid-options
           (thrown-error #(run-runtime (program [:noop]) adapter bad)))))
  (doseq [bad [nil (dissoc adapter :evaluate) (assoc adapter :evaluate {})
               (assoc adapter :domain :scheduling) (assoc adapter :id "")
               (assoc adapter :initializers [:first])
               (assoc adapter :operators #{:unbounded})
               (assoc adapter :normalization {:bounds [[0 10] [0 10]]})
               (assoc adapter :normalization
                      (assoc (:normalization adapter) :bounds [[0 ##Inf] [0 10]]))
               (assoc adapter :normalization
                      (assoc (:normalization adapter) :constant-coordinates [0]))]]
    (is (= :invalid-adapter
           (thrown-error #(run-runtime (program [:noop]) bad opts)))))
  (is (= :invalid-adapter
         (thrown-error #(run-runtime (program [:step [:operator :swap]])
                                      (assoc adapter :operators #{:restart}) opts))))
  (is (= :invalid-adapter
         (thrown-error #(run-runtime (program [:initialize [:initializer :first]])
                                      (assoc adapter :initializers #{:random}) opts)))))

(deftest every-node-and-loop-transition-consumes-fuel
  (doseq [[body required] [[[:noop] 3]
                            [[:seq [:noop] [:noop]] 5]
                            [[:repeat [:count 0] [:noop]] 4]
                            [[:repeat [:count 2] [:noop]] 8]
                            [[:repeat [:count 2] [:repeat [:count 2] [:noop]]] 18]]
          fuel (range (inc required))]
    (let [r (run-runtime (program body) adapter (assoc opts :step-budget fuel))]
      (is (= fuel (get-in r [:charged-costs :interpreter-steps])))
      (is (= (if (= fuel required) :completed :step-budget-exhausted) (:status r)))
      (is (zero? (get-in r [:charged-costs :proposal-attempts]))))))

(def init-first [:initialize [:initializer :first]])
(def step-swap [:step [:operator :swap]])

(deftest charged-requests-adopt-and-archive-representative-solutions
  (doseq [body [init-first step-swap]]
    (let [r (run-runtime (program body) adapter (assoc opts :solution-budget 1))
          expected (if (= body init-first) [5] [4])]
      (is (= :completed (:status r)))
      (is (= expected (:current r)))
      (is (= [expected] (:archive-solutions r)))
      (is (= [(mapv double [(first expected) (- 10 (first expected))])] (:archive r)))
      (is (= {:proposal-attempts 1 :valid-candidates 1 :unique-candidates 1
              :cache-hits 0 :objective-calculations 1 :interpreter-steps 4}
             (:charged-costs r)))))
  (let [r (run-runtime (program [:seq init-first step-swap]) adapter opts)]
    (is (= :completed (:status r)))
    (is (= [4] (:current r)))
    (is (= [[4.0 6.0] [5.0 5.0]] (:archive r)))
    (is (= [[4] [5]] (:archive-solutions r)))
    (is (= 7 (get-in r [:charged-costs :interpreter-steps])))))

(deftest proposal-charge-precedes-construction-and-fuel-blocks-primitives
  (let [poison (assoc adapter :initialize (fn [& _] (throw (ex-info "called" {}))))]
    (doseq [fuel (range 4)]
      (let [r (run-runtime (program init-first) poison (assoc opts :step-budget fuel))]
        (is (= :step-budget-exhausted (:status r)))
        (is (zero? (get-in r [:charged-costs :proposal-attempts])))
        (is (= [] (:diagnostics r)))))
    (let [r (run-runtime (program init-first) poison (assoc opts :solution-budget 0))]
      (is (= :proposal-budget-exhausted (:status r)))
      (is (zero? (get-in r [:charged-costs :proposal-attempts])))
      (is (= [] (:diagnostics r))))
    (let [r (run-runtime (program init-first) poison opts)]
      (is (= :method-failure (:status r)))
      (is (= 1 (get-in r [:charged-costs :proposal-attempts])))
      (is (zero? (get-in r [:charged-costs :objective-calculations])))
      (is (= :primitive-error (:kind (first (:diagnostics r)))))
      (is (= :initialize (:stage (first (:diagnostics r)))))))
  (doseq [allowance [0 1 3 4]]
    (let [r (run-runtime (program [:repeat [:count 4] init-first]) adapter
                         (assoc opts :solution-budget allowance))]
      (is (= allowance (get-in r [:charged-costs :proposal-attempts])))
      (is (= (if (= 4 allowance) :completed :proposal-budget-exhausted) (:status r))))))

(deftest canonical-duplicates-never-replenish-proposal-allowance
  (doseq [cache? [true false]]
    (let [r (run-runtime (program [:repeat [:count 4] init-first]) adapter
                         (assoc opts :cache? cache?))]
      (is (= {:proposal-attempts 4 :valid-candidates 4 :unique-candidates 1
              :cache-hits (if cache? 3 0) :objective-calculations (if cache? 1 4)
              :interpreter-steps 16} (:charged-costs r)))
      (is (= [[5.0 5.0]] (:archive r)))
      (is (= [[5]] (:archive-solutions r)))))
  (let [r (run-runtime (program [:seq init-first step-swap])
                       (assoc adapter :propose (fn [& _] [5 5])
                                      :canonicalize #(vec (distinct %))) opts)]
    (is (= 1 (get-in r [:charged-costs :unique-candidates])))
    (is (= 1 (get-in r [:charged-costs :cache-hits])))))

(deftest failed-requests-retain-actual-charges-and-diagnostics
  (doseq [[stage kind replacement objective-calls valid-count unique-count]
          [[:propose :primitive-error (fn [& _] (throw (RuntimeException. "proposal"))) 0 0 0]
           [:canonicalize :primitive-error (fn [_] (throw (RuntimeException. "canonical"))) 0 0 0]
           [:valid? :primitive-error (fn [_] (throw (RuntimeException. "validation"))) 0 0 0]
           [:valid? :invalid-candidate (constantly false) 0 0 0]
           [:evaluate :evaluation-error (fn [_] (throw (RuntimeException. "objective"))) 1 1 1]
           [:evaluate :evaluation-error (constantly [##NaN 0]) 1 1 1]
           [:evaluate :evaluation-error (constantly [11 0]) 1 1 1]
           [:evaluate :evaluation-error (constantly [1]) 1 1 1]]]
    (let [r (run-runtime (program step-swap) (assoc adapter stage replacement) opts)]
      (is (= :method-failure (:status r)))
      (is (= {:proposal-attempts 1 :valid-candidates valid-count :unique-candidates unique-count
              :cache-hits 0 :objective-calculations objective-calls :interpreter-steps 4}
             (:charged-costs r)))
      (is (= [] (:archive r)))
      (is (= kind (:kind (first (:diagnostics r)))))))
  (let [r (run-runtime (program [:seq init-first step-swap])
                       (assoc adapter :propose (fn [& _] (throw (RuntimeException. "failure")))) opts)]
    (is (= :method-failure (:status r)))
    (is (= [[5.0 5.0]] (:archive r)))
    (is (= [5] (:current r)))
    (is (= 2 (get-in r [:charged-costs :proposal-attempts])))))

(def objective-0 [:feature :objective-0])
(def objective-1 [:feature :objective-1])

(deftest local-search-charges-every-neighbor-and-all-scoring-work
  (let [body [:seq init-first [:search [:count 3] [:operator :swap] objective-0]]
        r (run-runtime (program body) adapter opts)]
    (is (= :completed (:status r)))
    (is (= [2] (:current r)))
    (is (= [[2] [3] [4] [5]] (:archive-solutions r)))
    (is (= {:proposal-attempts 4 :valid-candidates 4 :unique-candidates 4
            :cache-hits 0 :objective-calculations 4 :interpreter-steps 19} (:charged-costs r)))
    (doseq [allowance [0 1 2 3]]
      (let [partial (run-runtime (program body) adapter (assoc opts :solution-budget allowance))]
        (is (= :proposal-budget-exhausted (:status partial)))
        (is (= allowance (get-in partial [:charged-costs :proposal-attempts]))))))
  (let [r (run-runtime (program [:search [:count 2] [:operator :swap] [:constant 0]]) adapter opts)]
    (is (= [3] (:current r)))
    (is (= 2 (get-in r [:charged-costs :proposal-attempts])))
    (is (= 10 (get-in r [:charged-costs :interpreter-steps]))))
  (let [r (run-runtime (program [:search [:count 0] [:operator :swap] objective-0]) adapter
                       (assoc opts :solution-budget 0 :step-budget 4))]
    (is (= :completed (:status r)))
    (is (= 4 (get-in r [:charged-costs :interpreter-steps])))
    (is (zero? (get-in r [:charged-costs :proposal-attempts])))))

(deftest search-scores-use-fixed-normalization-not-raw-objectives
  (let [scaled (assoc adapter :normalization (pareto/prepare-normalization [[0 100] [0 10]])
                             :evaluate (fn [[x]] [(* 10.0 x) (- 10.0 x)]))
        score [:add objective-0 [:multiply [:constant 2] objective-1]]
        body [:seq init-first [:search [:count 3] [:operator :swap] score]]
        r (run-runtime (program body) scaled opts)]
    (is (= :completed (:status r)))
    (is (= [5] (:current r)))
    (is (= [[40.0 6.0] [50.0 5.0]] (:archive r)))
    (is (= 4 (get-in r [:charged-costs :proposal-attempts])))
    (is (= 2 (get-in r [:charged-costs :objective-calculations])))
    (is (= 2 (get-in r [:charged-costs :cache-hits])))
    (is (= 43 (get-in r [:charged-costs :interpreter-steps])))))

(deftest fuel-can-stop-after-archiving-without-adopting
  (let [body [:seq init-first [:search [:count 1] [:operator :swap] objective-0]]]
    (doseq [[fuel archive current] [[7 [[5.0 5.0]] [5]]
                                  [8 [[5.0 5.0]] [5]]
                                  [9 [[4.0 6.0] [5.0 5.0]] [5]]
                                  [10 [[4.0 6.0] [5.0 5.0]] [5]]
                                  [11 [[4.0 6.0] [5.0 5.0]] [4]]]]
      (let [r (run-runtime (program body) adapter (assoc opts :step-budget fuel))]
        (is (= (if (= fuel 11) :completed :step-budget-exhausted) (:status r)))
        (is (= fuel (get-in r [:charged-costs :interpreter-steps])))
        (is (= archive (:archive r)))
        (is (= current (:current r)))))))

(defn choose [predicate] [:if predicate init-first [:noop]])

(deftest predicates-branch-and-short-circuit-with-exact-node-fuel
  (doseq [[predicate budget expected-current steps]
          [[[:predicate :budget-left] 1 [5] 6]
           [[:predicate :budget-left] 0 nil 5]
           [[:not [:predicate :archive-changed]] 1 [5] 7]
           [[:and [:predicate :budget-left] [:not [:predicate :archive-changed]]] 1 [5] 9]
           [[:and [:predicate :budget-left] [:not [:predicate :coin]]] 0 nil 6]
           [[:less objective-0 [:constant 0.1]] 1 [5] 8]
           [[:less objective-1 [:constant 0]] 1 nil 7]
           [[:less [:feature :progress] [:constant 0]] 0 nil 7]]]
    (let [r (run-runtime (program (choose predicate)) adapter
                         (assoc opts :solution-budget budget :step-budget steps))]
      (is (= (if (and (= budget 0) expected-current) :proposal-budget-exhausted :completed)
             (:status r)))
      (is (= expected-current (:current r)))
      (is (= steps (get-in r [:charged-costs :interpreter-steps])))))
  (let [body [:seq init-first [:if [:predicate :archive-changed] step-swap [:noop]]]
        r (run-runtime (program body) adapter opts)]
    (is (= [4] (:current r))))
  (let [body [:seq init-first [:seq init-first (choose [:not [:predicate :archive-changed]])]]
        r (run-runtime (program body) adapter opts)]
    (is (= 3 (get-in r [:charged-costs :proposal-attempts])))))

(deftest progress-is-charged-proposals-over-host-budget
  (doseq [[budget expected] [[1 1] [2 2] [10 2]]]
    (let [body [:seq init-first (choose [:less [:feature :progress] [:constant 0.75]])]
          r (run-runtime (program body) adapter (assoc opts :solution-budget budget))]
      (is (= :completed (:status r)))
      (is (= expected (get-in r [:charged-costs :proposal-attempts]))))))

(deftest arithmetic-clamps-both-signs-and-features-read-normalized-state
  (doseq [predicate [[:less [:constant 999999] [:add [:constant 1000000] [:constant 1000000]]]
                     [:less [:multiply [:constant -1000000] [:constant 1000000]] [:constant -999999]]
                     [:less [:multiply [:constant 1000000] [:constant 1000000]]
                      [:add [:constant 1000000] [:constant 1]]]]
          :let [r (run-runtime (program (choose predicate)) adapter opts)]]
    (is (= :completed (:status r)))
    (is (= (if (= :multiply (first (second predicate)))
             (when (= -1000000 (second (second (second predicate)))) [5]) [5])
           (:current r))))
  (let [body [:seq init-first (choose [:less objective-0 [:constant 0.6]])]
        r (run-runtime (program body) adapter opts)]
    (is (= 2 (get-in r [:charged-costs :proposal-attempts])))))

(deftest seeded-coins-and-host-randomness-replay
  (let [random-adapter (assoc adapter :initialize (fn [_ rng] [(.nextInt ^java.util.Random rng 11)])
                                     :propose (fn [_ _ rng] [(.nextInt ^java.util.Random rng 11)]))
        ast (program [:repeat [:count 8] [:if [:predicate :coin]
                                        [:initialize [:initializer :random]] step-swap]])
        deterministic #(dissoc % :timing)
        a (run-runtime ast random-adapter opts)
        b (run-runtime ast random-adapter opts)]
    (is (= (deterministic a) (deterministic b)))
    (is (= :completed (:status a)))
    (is (= 8 (get-in a [:charged-costs :proposal-attempts])))
    (is (< 1 (count (set (map #(select-keys (run-runtime ast random-adapter (assoc opts :seed %))
                                           [:current :archive]) (range 8))))))))

(deftest bounded-trace-records-visits-branches-lineage-and-cache-outcomes
  (let [body [:seq init-first [:if [:predicate :archive-changed] init-first [:noop]]]
        r (run-runtime (program body) adapter opts)
        events (:trace r)
        visits (filter #(= :visit (:event %)) events)
        proposals (filter #(= :proposal (:event %)) events)
        candidates (filter #(= :candidate (:event %)) events)]
    (is (= [:program :budget :seq :initialize :initializer :if :predicate :initialize :initializer]
           (mapv #(first (:form %)) visits)))
    (is (= [1 2 3 4 5 6 7 8 9] (mapv :step visits)))
    (is (= [:then] (mapv :taken (filter #(= :branch (:event %)) events))))
    (is (= [nil [5]] (mapv :parent proposals)))
    (is (= [:first :first] (mapv :operator proposals)))
    (is (= [false true] (mapv :duplicate? candidates)))
    (is (= [false true] (mapv :cache-hit? candidates)))
    (is (= [true false] (mapv :archive-changed? candidates)))
    (is (false? (:trace-truncated? r)))
    (is (= 0 (:trace-dropped r))))
  (let [ast (program [:repeat [:count 8] [:repeat [:count 256] [:noop]]])
        options (assoc opts :step-budget 10000)
        traced (run-runtime ast adapter options)
        quiet (run-runtime ast adapter (assoc options :trace? false))]
    (is (= 2048 (count (:trace traced))))
    (is (true? (:trace-truncated? traced)))
    (is (pos? (or (:trace-dropped traced) 0)))
    (is (= [] (:trace quiet)))
    (is (false? (:trace-truncated? quiet)))
    (is (= (dissoc traced :timing :trace :trace-truncated? :trace-dropped)
           (dissoc quiet :timing :trace :trace-truncated? :trace-dropped)))))

(deftest timing-is-measured-and-is-the-only-replay-exclusion
  (let [a (run-runtime (program init-first) adapter opts)
        b (run-runtime (program init-first) adapter opts)]
    (is (= (dissoc a :timing) (dissoc b :timing)))
    (is (and (integer? (get-in a [:timing :elapsed-ns]))
             (<= 0 (get-in a [:timing :elapsed-ns] -1))))
    (is (contains? (:timing a) :thread-cpu-ns))
    (is (or (nil? (get-in a [:timing :thread-cpu-ns]))
            (and (integer? (get-in a [:timing :thread-cpu-ns]))
                 (<= 0 (get-in a [:timing :thread-cpu-ns])))))))

(defn make-portfolio-adapter [instance]
  (when-let [f (try (requiring-resolve 'niki.optimizer.portfolio-runtime/make-adapter)
                   (catch java.io.FileNotFoundException _ nil))]
    (f instance)))

(defn fixture []
  (edn/read-string (slurp (io/resource "fixtures/g1-portfolio.edn"))))

(deftest portfolio-adapter-integrates-existing-synthetic-g1-fixture
  (let [instance (portfolio/prepare-instance (fixture))
        a (make-portfolio-adapter instance)]
    (is (map? a))
    (when a
      (is (= :portfolio (:domain a)))
      (is (= #{:random :first} (:initializers a)))
      (is (= #{:swap :restart} (:operators a)))
      (is (= (pareto/portfolio-normalization instance) (:normalization a)))
      (is (and (integer? (get-in a [:preparation-timing :elapsed-ns]))
               (<= 0 (get-in a [:preparation-timing :elapsed-ns]))))
      (let [r (run-runtime (program [:seq init-first [:repeat [:count 5] step-swap]]) a opts)]
        (is (= :completed (:status r)))
        (is (= 6 (get-in r [:charged-costs :proposal-attempts])))
        (is (= (:preparation-timing a) (get-in r [:timing :preparation])))
        (is (= (count (:archive r)) (count (:archive-solutions r))))
        (doseq [[point solution] (map vector (:archive r) (:archive-solutions r))]
          (is (= solution (portfolio/validate-selection instance solution)))
          (is (= point (:objectives (portfolio/evaluate instance solution))))))
      (doseq [seed (range 8)]
        (let [rng (java.util.Random. seed)
              first-selection ((:initialize a) :first rng)
              random-selection ((:initialize a) :random rng)
              swapped ((:propose a) :swap random-selection rng)
              restarted ((:propose a) :restart swapped rng)
              from-nil ((:propose a) :swap nil rng)]
          (is (= [0 1 2 3 4] first-selection))
          (doseq [solution [first-selection random-selection swapped restarted from-nil]]
            (is (= solution (portfolio/validate-selection instance solution)))
            (is (true? ((:valid? a) solution)))
            (is (= (:objectives (portfolio/evaluate instance solution)) ((:evaluate a) solution))))
          (is (= 2 (count (set/union (set/difference (set swapped) (set random-selection))
                                    (set/difference (set random-selection) (set swapped)))))))))))

(deftest portfolio-sampling-is-bounded-even-with-constant-random-draws
  (doseq [[n k] [[1 1] [5 5] [7 3]]]
    (let [panel (fixture)
          instance (portfolio/prepare-instance (assoc panel :k k :assets (subvec (:assets panel) 0 n)))
          a (make-portfolio-adapter instance)
          bounds (atom [])
          rng (proxy [java.util.Random] [] (nextInt [bound] (swap! bounds conj bound) 0))]
      (is (map? a))
      (when a
        (let [solution ((:initialize a) :random rng)]
          (is (= (vec (range k)) solution))
          (is (= (vec (range n (- n k) -1)) @bounds))
          (reset! bounds [])
          (let [neighbor ((:propose a) :swap solution rng)]
            (is (= neighbor (portfolio/validate-selection instance neighbor)))
            (is (= (if (= n k) 0 2) (count @bounds)))
            (when (= n k) (is (= solution neighbor))))
          (let [r (run-runtime (program [:seq init-first [:repeat [:count 3] step-swap]]) a opts)]
            (is (= :completed (:status r)))
            (is (= 4 (get-in r [:charged-costs :proposal-attempts])))
            (when (= n k)
              (is (= 1 (get-in r [:charged-costs :objective-calculations])))
              (is (= 3 (get-in r [:charged-costs :cache-hits]))))))))))

(deftest portfolio-primitives-are-objective-free-after-fixed-preparation
  (let [instance (portfolio/prepare-instance (fixture))
        actual-evaluate portfolio/evaluate
        calls (atom 0)]
    (with-redefs [portfolio/evaluate (fn [& args] (swap! calls inc) (apply actual-evaluate args))]
      (let [a (make-portfolio-adapter instance)]
        (is (map? a))
        (when a
          (let [prepared-calls @calls rng (java.util.Random. 0)
                solution ((:initialize a) :random rng)]
            (is (pos? prepared-calls))
            ((:initialize a) :first rng)
            ((:propose a) :swap solution rng)
            ((:propose a) :restart solution rng)
            ((:propose a) :swap nil rng)
            ((:canonicalize a) solution)
            ((:valid? a) solution)
            (is (= prepared-calls @calls))
            ((:evaluate a) solution)
            (is (= (inc prepared-calls) @calls)))))))
  (is (= :unprepared-instance (thrown-error #(make-portfolio-adapter (fixture))))))

(deftest objective-representatives-use-g2-canonical-numeric-keys
  (doseq [point [[0 1] [-0.0 1.0] [0M 1M] [0N 1N]]]
    (let [r (run-runtime (program [:seq init-first step-swap])
                         (assoc adapter :evaluate (constantly point)) opts)
          outcomes (filter #(= :candidate (:event %)) (:trace r))]
      (is (= :completed (:status r)))
      (is (= [[0.0 1.0]] (:archive r)))
      (is (= [[5]] (:archive-solutions r)))
      (is (= [4] (:current r)))
      (doseq [outcome outcomes]
        (is (= 0 (Double/doubleToRawLongBits (first (:objectives outcome)))))
        (is (every? #(instance? Double %) (:objectives outcome)))))))

(deftest canonicalization-and-validation-run-before-duplicate-cache-lookup
  (doseq [stage [:canonicalize :valid?]]
    (let [calls (atom 0)
          f (fn [solution]
              (when (= 2 (swap! calls inc)) (throw (ex-info "duplicate check fails" {})))
              (if (= stage :canonicalize) solution true))
          r (run-runtime (program [:repeat [:count 2] init-first]) (assoc adapter stage f) opts)]
      (is (= :method-failure (:status r)))
      (is (= 2 @calls))
      (is (= 2 (get-in r [:charged-costs :proposal-attempts])))
      (is (= 1 (get-in r [:charged-costs :valid-candidates])))
      (is (= 0 (get-in r [:charged-costs :cache-hits])))
      (is (= 1 (get-in r [:charged-costs :objective-calculations]))))))

(deftest objective-ledger-equals-real-invocations-including-thrown-calls
  (doseq [[cache? throw-at] [[true nil] [false nil] [true 1] [false 2]]]
    (let [calls (atom 0)
          a (assoc adapter :evaluate (fn [solution]
                                     (when (= throw-at (swap! calls inc))
                                       (throw (RuntimeException. "actual evaluator call")))
                                     ((:evaluate adapter) solution)))
          r (run-runtime (program [:repeat [:count 3] init-first]) a (assoc opts :cache? cache?))]
      (is (= @calls (get-in r [:charged-costs :objective-calculations])))
      (is (= (if throw-at :method-failure :completed) (:status r)))
      (is (= (or throw-at (if cache? 1 3)) @calls)))))

(deftest search-trace-exposes-adoption-with-normalized-scores
  (let [r (run-runtime (program [:search [:count 2] [:operator :swap] objective-0]) adapter opts)
        decisions (filter #(= :search-choice (:event %)) (:trace r))]
    (is (= [true true] (mapv :accepted? decisions)))
    (is (= [:no-current :score] (mapv :reason decisions)))
    (is (= [nil 0.4] (mapv :current-score decisions)))
    (is (= [nil 0.3] (mapv :candidate-score decisions)))))

(deftest preflight-checks-programs-with-default-execution-limits
  (let [calls (atom 0)
        a (assoc adapter :initialize (fn [& _] (swap! calls inc) [5]))]
    (doseq [ast [[:program :portfolio [:budget [:unknown]]]
                 (program [:repeat [:count 257] init-first])
                 (program [:if [:constant 1] init-first [:noop]])]]
      (is (thrown? clojure.lang.ExceptionInfo (run-runtime ast a opts))))
    (is (zero? @calls))))
