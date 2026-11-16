(ns niki.optimizer.search
  "Outer assessment-slot runner implementing the five search arms:
    A1 :gp-mixed
    A2 :gp-mutation-only
    A3 :random-dsl
    A4 :formula-only
    A5 :configured-conventional
  Enforces equal assessment-slot budgets, shared GP initial populations,
  duplicate/fallback slot accounting, validation finalist selection, and
  held-out evaluation without cross-seed archive pooling."
  (:require [niki.optimizer.dsl :as dsl]
            [niki.optimizer.runtime :as runtime]
            [niki.optimizer.variation :as variation]
            [niki.optimizer.pareto :as pareto]
            [niki.optimizer.baselines :as baselines])
  (:import [java.util Random]))

(def valid-arms
  #{:gp-mixed :gp-mutation-only :random-dsl :formula-only :configured-conventional})

(defn- ensure! [condition code message data]
  (when-not condition
    (throw (ex-info message (assoc data :error code)))))

(defn- mix-seed [& parts]
  (long
   (reduce (fn [^long acc x]
             (let [h (long (hash x))]
               (unchecked-add (unchecked-multiply acc 6364136223846793005)
                              (bit-xor h 1442695040888963407))))
           20261108
           parts)))

(defn formula-scaffold
  "Fixed canonical optimizer control scaffold for Arm A4 (:formula-only).
  Only the :score expression at path [2 1 2 2 3] is evolved."
  [domain score-expr]
  [:program domain
   [:budget
    [:seq [:initialize [:initializer :random]]
     [:repeat [:count 16]
      [:search [:count 16] [:operator :swap] score-expr]]]]])

(def formula-score-root-path [2 1 2 2 3])

(defn- pick [^Random rng xs]
  (nth xs (.nextInt rng (count xs))))

(defn- between [^Random rng lo hi]
  (+ lo (.nextInt rng (inc (- hi lo)))))

(defn- grow-score [^Random rng nodes depth]
  (let [leaf? (or (<= nodes 2) (<= depth 1) (< (.nextDouble rng) 0.45))]
    (if leaf?
      (if (zero? (.nextInt rng 2))
        [:constant (double (between rng -10 10))]
        [:feature (pick rng [:objective-0 :objective-1 :progress])])
      (let [op (pick rng [:add :multiply])
            left-cap (between rng 1 (dec (dec nodes)))
            right-cap (- (dec nodes) left-cap)]
        [op
         (grow-score rng left-cap (dec depth))
         (grow-score rng right-cap (dec depth))]))))

(def ^:private v2-counts [2 4 8 12 16 24 32 64])
(def ^:private v2-constants [-4.0 -2.0 -1.0 -0.5 0.25 0.5 1.0 1.5 2.0 4.0])

(defn- grow-score-v2 [^Random rng nodes depth]
  (cond
    (or (<= nodes 2) (<= depth 1))
    (if (< (.nextDouble rng) 0.75)
      [:feature (pick rng [:objective-0 :objective-1 :progress])]
      [:constant (double (pick rng v2-constants))])

    ;; With >= 5 nodes and depth >= 3, often build a weighted sum of both objectives or progress
    (and (>= nodes 5) (>= depth 3) (< (.nextDouble rng) 0.6))
    [:add
     [:feature :objective-0]
     [:multiply
      (if (< (.nextDouble rng) 0.5)
        [:constant (double (pick rng [0.25 0.5 1.0 1.5 2.0]))]
        [:feature :progress])
      [:feature :objective-1]]]

    :else
    (let [op (if (< (.nextDouble rng) 0.7) :add :multiply)
          left-cap (between rng 1 (dec (dec nodes)))
          right-cap (- (dec nodes) left-cap)]
      [op
       (grow-score-v2 rng left-cap (dec depth))
       (grow-score-v2 rng right-cap (dec depth))])))

(defn- leaf-v2 [^Random rng domain op limits]
  (let [valid-counts (filterv #(<= % (:max-count limits)) v2-counts)
        count-pool (if (seq valid-counts) valid-counts [1])]
    (case op
      :noop [:noop]
      :count [:count (long (pick rng count-pool))]
      :initializer [:initializer (pick rng [:random :first])]
      :operator [:operator (pick rng (if (= :portfolio domain)
                                       [:swap :restart]
                                       [:swap :restart :insert :reverse]))]
      :predicate [:predicate (pick rng [:archive-changed :budget-left :coin])]
      :constant [:constant (double (pick rng v2-constants))]
      :feature [:feature (pick rng [:objective-0 :objective-1 :progress])])))

(defn- grow-v2 [^Random rng domain role nodes depth limits]
  (case role
    (:count :initializer :operator)
    (leaf-v2 rng domain role limits)

    :score
    (grow-score-v2 rng nodes depth)

    :predicate
    (if (or (<= nodes 1) (<= depth 1) (< (.nextDouble rng) 0.45))
      (leaf-v2 rng domain :predicate limits)
      (let [candidates (cond-> [[:not [:predicate]]]
                         (>= nodes 3) (conj [:and [:predicate :predicate]]
                                            [:less [:score :score]]))
            [op children] (pick rng candidates)]
        (if (= 1 (count children))
          [op (grow-v2 rng domain :predicate (dec nodes) (dec depth) limits)]
          (let [c1 (between rng 1 (- (dec nodes) 1))
                c2 (- (dec nodes) c1)]
            [op
             (grow-v2 rng domain (first children) c1 (dec depth) limits)
             (grow-v2 rng domain (second children) c2 (dec depth) limits)]))))

    :operation
    (cond
      (or (<= nodes 1) (<= depth 1))
      [:noop]

      (== nodes 2)
      (let [op (pick rng [:initialize :step])]
        [op (leaf-v2 rng domain (if (= op :initialize) :initializer :operator) limits)])

      (== nodes 3)
      [:repeat (leaf-v2 rng domain :count limits)
       [:step (leaf-v2 rng domain :operator limits)]]

      :else
      ;; nodes >= 4 and depth >= 2: reserve exact 1-node caps for :count/:operator leaves!
      (let [choices (cond-> [[:search [:count :operator :score]]
                             [:repeat [:count :operation]]
                             [:seq [:operation :operation]]]
                      (and (>= nodes 6) (>= depth 3))
                      (conj [:if [:predicate :operation :operation]]))
            ;; Bias toward :search and :repeat/:seq so programs actively explore
            weighted (into choices [[:search [:count :operator :score]]
                                    [:repeat [:count :operation]]])
            [op children] (pick rng weighted)]
        (case op
          :search
          ;; :count and :operator each use 1 node; give ALL remaining capacity to :score!
          (let [score-nodes (max 1 (- nodes 3))]
            [:search
             (leaf-v2 rng domain :count limits)
             (leaf-v2 rng domain :operator limits)
             (grow-score-v2 rng score-nodes (dec depth))])

          :repeat
          ;; :count uses 1 node; give remaining capacity to inner :operation
          (let [body-nodes (max 2 (- nodes 2))]
            [:repeat
             (leaf-v2 rng domain :count limits)
             (grow-v2 rng domain :operation body-nodes (dec depth) limits)])

          :seq
          (let [left-cap (between rng 2 (max 2 (- (dec nodes) 2)))
                right-cap (max 1 (- (dec nodes) left-cap))]
            [:seq
             (grow-v2 rng domain :operation left-cap (dec depth) limits)
             (grow-v2 rng domain :operation right-cap (dec depth) limits)])

          :if
          (let [pred-cap (min 3 (max 1 (- nodes 5)))
                rem-nodes (- (dec nodes) pred-cap)
                then-cap (max 2 (quot rem-nodes 2))
                else-cap (max 1 (- rem-nodes then-cap))]
            [:if
             (grow-v2 rng domain :predicate pred-cap (dec depth) limits)
             (grow-v2 rng domain :operation then-cap (dec depth) limits)
             (grow-v2 rng domain :operation else-cap (dec depth) limits)]))))))

(defn generate-program-v2
  "Budget-aware, capacity-preserving typed AST generator (:budget-aware-v2).
   Never starves :score children by over-allocating node caps to 1-node :count/:operator
   leaves, and avoids degenerate top-level [:noop] or 1-proposal programs."
  [domain {:keys [seed limits] :or {seed 0 limits {}}}]
  (let [eff-limits (dsl/effective-limits limits)
        rng (Random. (long seed))
        max-body-nodes (max 4 (min 26 (- (:max-nodes eff-limits) 2)))
        max-body-depth (max 3 (min 6 (- (:max-depth eff-limits) 2)))
        nodes (between rng (min 9 max-body-nodes) max-body-nodes)
        depth (between rng (min 4 max-body-depth) max-body-depth)
        body (if (and (>= nodes 9) (>= depth 4) (< (.nextDouble rng) 0.75))
               ;; Structured initialization followed by iterative search/control body
               [:seq
                [:initialize (leaf-v2 rng domain :initializer eff-limits)]
                (grow-v2 rng domain :operation (- nodes 3) (dec depth) eff-limits)]
               (grow-v2 rng domain :operation nodes depth eff-limits))
        checked (dsl/check-program [:program domain [:budget body]] eff-limits)
        p (:program checked)]
    {:program p
     :child-id (dsl/program-id p eff-limits)
     :parent-ids []
     :paths []
     :changed? true
     :attempts 1
     :fallback? false
     :rejections []
     :seed (long seed)
     :limits eff-limits
     :distribution :budget-aware-v2}))

(defn mutate-program-v2
  "Typed subtree mutation using the capacity-preserving grow-v2 subtree generator."
  [program {:keys [seed limits max-attempts] :or {seed 0 limits {} max-attempts 10}}]
  (let [eff-limits (dsl/effective-limits limits)
        checked (dsl/check-program program eff-limits)
        parent (:program checked)
        parent-id (dsl/program-id parent eff-limits)
        rng (Random. (long seed))
        sites (:sites checked)
        ;; Prefer mutating score, operator, count, or inner operation sites over replacing the whole root
        non-root (filterv #(not= [2 1] (:path %)) sites)
        candidate-sites (if (and (seq non-root) (< (.nextDouble rng) 0.85)) non-root sites)]
    (loop [attempt 1 rejections [] last-path nil]
      (if (> attempt max-attempts)
        {:program parent
         :child-id parent-id
         :parent-ids [parent-id]
         :attempts max-attempts
         :fallback? true
         :fallback-reason :unchanged-parent
         :changed? false
         :path last-path
         :paths (if last-path [last-path] [])
         :rejections rejections
         :seed (long seed)
         :limits eff-limits}
        (let [{:keys [path type]} (pick rng candidate-sites)
              role (first type)
              old (get-in parent path)
              old-nodes (count (tree-seq vector? #(filterv vector? %) old))
              node-cap (max 1 (min 18 (- (:max-nodes eff-limits) (- (:node-count checked) old-nodes))))
              depth-cap (max 1 (min 5 (- (:max-depth eff-limits) (count path))))
              sub-nodes (between rng (min (if (#{:operation :score} role) 3 1) node-cap) node-cap)
              sub-depth (between rng (min (if (#{:operation :score} role) 2 1) depth-cap) depth-cap)
              replacement (grow-v2 rng (:domain checked) role sub-nodes sub-depth eff-limits)
              attempt-res (try
                            {:program (dsl/replace-subtree parent path replacement eff-limits)}
                            (catch clojure.lang.ExceptionInfo e
                              {:rejection {:reason :structural-or-type-rejection
                                           :message (.getMessage e)}}))]
          (cond
            (:rejection attempt-res)
            (recur (inc attempt)
                   (conj rejections (assoc (:rejection attempt-res) :attempt attempt :path path))
                   path)
            (= old replacement)
            (recur (inc attempt)
                   (conj rejections {:attempt attempt :path path :reason :identical-subtree})
                   path)
            :else
            (let [child (:program attempt-res)]
              {:program child
               :child-id (dsl/program-id child eff-limits)
               :parent-ids [parent-id]
               :attempts attempt
               :fallback? false
               :changed? (not= parent child)
               :path path
               :paths [path]
               :rejections rejections
               :seed (long seed)
               :limits eff-limits})))))))

(defn generate-formula-program
  "Generate a seeded :formula-only program in the fixed control scaffold."
  [domain {:keys [seed limits] :or {seed 0 limits {}}}]
  (let [eff-limits (dsl/effective-limits limits)
        rng (Random. (long seed))
        max-score-nodes (max 1 (min 15 (- (:max-nodes eff-limits) 10)))
        max-score-depth (max 1 (min 5 (- (:max-depth eff-limits) 6)))
        nodes (between rng 1 max-score-nodes)
        depth (between rng 1 max-score-depth)
        score-ast (grow-score rng nodes depth)
        program (:program (dsl/check-program (formula-scaffold domain score-ast) eff-limits))]
    {:program program
     :child-id (dsl/program-id program eff-limits)
     :parent-ids []
     :paths [formula-score-root-path]
     :changed? true
     :attempts 1
     :fallback? false
     :rejections []
     :seed (long seed)
     :limits eff-limits}))

(defn mutate-formula-program
  "Mutate only a :score site inside the fixed :formula-only scaffold (up to 10 attempts)."
  [program {:keys [seed limits max-attempts] :or {seed 0 limits {} max-attempts 10}}]
  (let [eff-limits (dsl/effective-limits limits)
        checked (dsl/check-program program eff-limits)
        parent (:program checked)
        parent-id (dsl/program-id parent eff-limits)
        score-sites (filterv (fn [{:keys [path type]}]
                               (and (= :score (first type))
                                    (>= (count path) (count formula-score-root-path))
                                    (= formula-score-root-path
                                       (subvec path 0 (count formula-score-root-path)))))
                             (:sites checked))
        _ (ensure! (seq score-sites) :invalid-formula-program "Program lacks editable :score sites in formula scaffold" {})
        rng (Random. (long seed))]
    (loop [attempt 1 rejections [] last-path nil]
      (if (> attempt max-attempts)
        {:program parent
         :child-id parent-id
         :parent-ids [parent-id]
         :attempts max-attempts
         :fallback? true
         :fallback-reason :unchanged-parent
         :changed? false
         :path last-path
         :paths (if last-path [last-path] [])
         :rejections rejections
         :seed (long seed)
         :limits eff-limits}
        (let [{:keys [path]} (pick rng score-sites)
              old (get-in parent path)
              old-nodes (count (tree-seq vector? #(filterv vector? %) old))
              node-cap (max 1 (min 15 (- (:max-nodes eff-limits) (- (:node-count checked) old-nodes))))
              depth-cap (max 1 (min 5 (- (:max-depth eff-limits) (count path))))
              replacement (grow-score rng (between rng 1 node-cap) (between rng 1 depth-cap))
              attempt-res (try
                            {:program (dsl/replace-subtree parent path replacement eff-limits)}
                            (catch clojure.lang.ExceptionInfo e
                              {:rejection {:reason :structural-or-type-rejection
                                           :message (.getMessage e)}}))]
          (cond
            (:rejection attempt-res)
            (recur (inc attempt)
                   (conj rejections (assoc (:rejection attempt-res) :attempt attempt :path path))
                   path)
            (= old replacement)
            (recur (inc attempt)
                   (conj rejections {:attempt attempt :path path :reason :identical-subtree})
                   path)
            :else
            (let [child (:program attempt-res)]
              {:program child
               :child-id (dsl/program-id child eff-limits)
               :parent-ids [parent-id]
               :attempts attempt
               :fallback? false
               :changed? (not= parent child)
               :path path
               :paths [path]
               :rejections rejections
               :seed (long seed)
               :limits eff-limits})))))))

(defn- ast-node-count [program]
  (count (tree-seq vector? #(filterv vector? %) program)))

(defn assess-candidate
  "Evaluate a program or conventional configuration across tasks x inner-seeds
  without pooling archives across seeds."
  [arm candidate candidate-id tasks inner-seeds {:keys [solution-budget step-budget failure-score cache?]
                                                 :or {solution-budget 256 step-budget 4096 failure-score 0.0 cache? true}}]
  (let [use-cache? (boolean cache?)
        task-outcomes
        (mapv (fn [task]
                (let [adapter (:adapter task)
                      task-id (:task-id task (:id adapter))
                      seed-runs
                      (mapv (fn [s]
                              (let [res (if (= arm :configured-conventional)
                                          (baselines/run-conventional candidate adapter
                                                                      {:solution-budget solution-budget
                                                                       :seed (long s)
                                                                       :cache? use-cache?})
                                          (runtime/run candidate adapter
                                                       {:solution-budget solution-budget
                                                        :step-budget step-budget
                                                        :seed (long s)
                                                        :cache? use-cache?
                                                        :trace? false}))
                                    failed? (= :method-failure (:status res))
                                    hv (if failed?
                                         (double failure-score)
                                         (pareto/normalized-hypervolume (:archive res) (:normalization adapter)))]
                                {:seed (long s)
                                 :status (:status res)
                                 :failed? failed?
                                 :normalized-hv hv
                                 :archive (:archive res)
                                 :archive-solutions (:archive-solutions res)
                                 :charged-costs (:charged-costs res)}))
                            inner-seeds)
                      task-mean-hv (/ (reduce + 0.0 (map :normalized-hv seed-runs))
                                      (double (count seed-runs)))]
                  {:task-id task-id
                   :mean-normalized-hv task-mean-hv
                   :seed-runs seed-runs}))
              tasks)
        overall-mean-hv (/ (reduce + 0.0 (map :mean-normalized-hv task-outcomes))
                           (double (count task-outcomes)))
        all-runs (mapcat :seed-runs task-outcomes)
        node-count (if (= arm :configured-conventional)
                     (count (keys candidate))
                     (ast-node-count candidate))
        sum-cost (fn [k] (reduce + 0 (map #(get-in % [:charged-costs k] 0) all-runs)))]
    {:candidate candidate
     :candidate-id candidate-id
     :mean-normalized-hv overall-mean-hv
     :node-count node-count
     :selection-key [(- overall-mean-hv) node-count candidate-id]
     :task-outcomes task-outcomes
     :totals {:executions (count all-runs)
              :failures (count (filter :failed? all-runs))
              :proposal-attempts (sum-cost :proposal-attempts)
              :valid-candidates (sum-cost :valid-candidates)
              :unique-candidates (sum-cost :unique-candidates)
              :cache-hits (sum-cost :cache-hits)
              :objective-calculations (sum-cost :objective-calculations)
              :interpreter-steps (sum-cost :interpreter-steps)}}))

(defn- tournament-select [assessed-pool ^Random rng]
  (let [a (pick rng assessed-pool)
        b (pick rng assessed-pool)]
    (if (<= (compare (:selection-key a) (:selection-key b)) 0) a b)))

(defn run-arm
  "Run one complete outer replication for `arm` on `domain`:
   1. Equal-slot training assessment (initial-population + offspring-batches * offspring-per-batch).
      Duplicate candidates and fallback constructions still consume their slot.
   2. Deterministic finalist selection (up to :max-finalists) evaluated on :validation-tasks.
   3. Single representative selection by maximum validation mean normalized HV
      (tie-broken by :node-count and :candidate-id).
   4. Held-out evaluation on :test-tasks x :test-seeds (when provided) with no cross-seed pooling."
  [{:keys [arm domain run-seed outer train validation test]
    :or {run-seed 0}}]
  (ensure! (contains? valid-arms arm) :invalid-arm "Unknown search arm" {:arm arm})
  (ensure! (contains? #{:portfolio :scheduling} domain) :invalid-domain "Unknown domain" {:domain domain})
  (let [init-pop (long (:initial-population outer 8))
        batches (long (:offspring-batches outer 3))
        batch-size (long (:offspring-per-batch outer 8))
        total-slots (long (:assessment-slots outer (+ init-pop (* batches batch-size))))
        _ (ensure! (= total-slots (+ init-pop (* batches batch-size)))
                   :slot-mismatch "assessment-slots must equal initial-population + offspring-batches * offspring-per-batch"
                   {:assessment-slots total-slots :initial-population init-pop
                    :offspring-batches batches :offspring-per-batch batch-size})
        crossover-prob (double (:mixed-crossover-probability outer 0.5))
        prior (:generator-prior outer :development-only-v1)
        _ (ensure! (contains? #{:development-only-v1 :budget-aware-v2} prior)
                   :invalid-generator-prior "Unknown :generator-prior" {:generator-prior prior})
        gen-fn (if (= prior :budget-aware-v2) generate-program-v2 variation/generate-program)
        mut-fn (if (= prior :budget-aware-v2) mutate-program-v2 variation/mutate)
        limits (dsl/effective-limits (:limits outer {}))
        train-tasks (:tasks train)
        train-seeds (:inner-seeds train [0])
        train-budget {:solution-budget (long (:solution-proposals train 64))
                      :step-budget (long (:step-budget train 4096))
                      :failure-score (double (:failure-score train 0.0))}
        _ (ensure! (and (vector? train-tasks) (seq train-tasks))
                   :invalid-train-tasks "Requires non-empty :train :tasks vector" {})
        construct-slot
        (fn [slot-idx pool]
          (if (< slot-idx init-pop)
            ;; Shared initial population seed schedule for :gp-mixed, :gp-mutation-only, and :random-dsl
            (let [init-seed (mix-seed domain run-seed :init slot-idx)]
              (case arm
                (:gp-mixed :gp-mutation-only :random-dsl)
                (let [g (gen-fn domain {:seed init-seed :limits limits})]
                  {:candidate (:program g) :candidate-id (:child-id g)
                   :operator :generate-init :attempts (:attempts g)
                   :fallback? (:fallback? g) :parent-ids []})
                :formula-only
                (let [g (generate-formula-program domain {:seed init-seed :limits limits})]
                  {:candidate (:program g) :candidate-id (:child-id g)
                   :operator :generate-formula-init :attempts (:attempts g)
                   :fallback? (:fallback? g) :parent-ids []})
                :configured-conventional
                (let [g (baselines/sample-conventional-config domain {:seed init-seed})]
                  {:candidate (:config g) :candidate-id (:config-id g)
                   :operator :sample-config :attempts 1
                   :fallback? false :parent-ids []})))
            ;; Offspring / continuation slot
            (let [slot-seed (mix-seed domain run-seed arm :slot slot-idx)
                  slot-rng (Random. slot-seed)]
              (case arm
                :random-dsl
                (let [g (gen-fn domain {:seed slot-seed :limits limits})]
                  {:candidate (:program g) :candidate-id (:child-id g)
                   :operator :generate-random :attempts (:attempts g)
                   :fallback? (:fallback? g) :parent-ids []})

                :configured-conventional
                (let [g (baselines/sample-conventional-config domain {:seed slot-seed})]
                  {:candidate (:config g) :candidate-id (:config-id g)
                   :operator :sample-config :attempts 1
                   :fallback? false :parent-ids []})

                :formula-only
                (let [parent (tournament-select pool slot-rng)
                      m (mutate-formula-program (:candidate parent) {:seed slot-seed :limits limits})]
                  {:candidate (:program m) :candidate-id (:child-id m)
                   :operator :mutate-formula :attempts (:attempts m)
                   :fallback? (:fallback? m) :parent-ids (:parent-ids m)})

                :gp-mutation-only
                (let [parent (tournament-select pool slot-rng)
                      m (mut-fn (:candidate parent) {:seed slot-seed :limits limits})]
                  {:candidate (:program m) :candidate-id (:child-id m)
                   :operator :mutate :attempts (:attempts m)
                   :fallback? (:fallback? m) :parent-ids (:parent-ids m)})

                :gp-mixed
                (let [use-crossover? (< (.nextDouble slot-rng) crossover-prob)]
                  (if use-crossover?
                    ;; Crossover replaces mutation in this single slot; never grants a second free slot.
                    (let [p1 (tournament-select pool slot-rng)
                          p2 (tournament-select pool slot-rng)
                          c (variation/crossover (:candidate p1) (:candidate p2)
                                                 {:seed slot-seed :limits limits})
                          child (first (:children c))
                          child-id (first (:child-ids c))]
                      {:candidate child :candidate-id child-id
                       :operator :crossover :attempts (:attempts c)
                       :fallback? (:fallback? c) :parent-ids (:parent-ids c)})
                    (let [p1 (tournament-select pool slot-rng)
                          m (mut-fn (:candidate p1) {:seed slot-seed :limits limits})]
                      {:candidate (:program m) :candidate-id (:child-id m)
                       :operator :mutate :attempts (:attempts m)
                       :fallback? (:fallback? m) :parent-ids (:parent-ids m)})))))))
        ;; Run outer loop batch-by-batch so selection within batch b sees assessments up to batch b-1
        {:keys [slots assessed-by-id]}
        (loop [slot-idx 0
               batch-pool []
               current-batch []
               slots []
               assessed-by-id {}]
          (if (= slot-idx total-slots)
            {:slots slots :assessed-by-id assessed-by-id}
            (let [boundary? (and (>= slot-idx init-pop)
                                 (zero? (mod (- slot-idx init-pop) batch-size)))
                  active-pool (if boundary?
                                (into batch-pool current-batch)
                                (if (= slot-idx init-pop) current-batch batch-pool))
                  active-batch (if (or boundary? (= slot-idx init-pop)) [] current-batch)
                  constructed (construct-slot slot-idx active-pool)
                  cid (:candidate-id constructed)
                  duplicate? (contains? assessed-by-id cid)
                  assessment (if duplicate?
                               (get assessed-by-id cid)
                               (assess-candidate arm (:candidate constructed) cid
                                                 train-tasks train-seeds train-budget))
                  slot-record {:slot slot-idx
                               :candidate-id cid
                               :operator (:operator constructed)
                               :attempts (:attempts constructed)
                               :fallback? (:fallback? constructed)
                               :duplicate-slot? duplicate?
                               :parent-ids (:parent-ids constructed)
                               :training-mean-hv (:mean-normalized-hv assessment)
                               :node-count (:node-count assessment)}]
              (recur (inc slot-idx)
                     active-pool
                     (conj active-batch assessment)
                     (conj slots slot-record)
                     (if duplicate?
                       assessed-by-id
                       (assoc assessed-by-id cid assessment))))))
        distinct-assessments (vec (sort-by :selection-key (vals assessed-by-id)))
        max-finalists (long (:max-finalists validation 8))
        finalists (vec (take max-finalists distinct-assessments))
        val-tasks (:tasks validation train-tasks)
        val-seeds (:inner-seeds validation train-seeds)
        val-budget {:solution-budget (long (:solution-proposals validation (:solution-budget train-budget)))
                    :step-budget (long (:step-budget validation (:step-budget train-budget)))
                    :failure-score (:failure-score train-budget)}
        validation-assessments
        (mapv (fn [f]
                (assess-candidate arm (:candidate f) (:candidate-id f)
                                  val-tasks val-seeds val-budget))
              finalists)
        selected (first (sort-by :selection-key validation-assessments))
        test-evaluation
        (when (and (map? test) (seq (:tasks test)))
          (assess-candidate arm (:candidate selected) (:candidate-id selected)
                            (:tasks test)
                            (:inner-seeds test [0])
                            {:solution-budget (long (:solution-proposals test (:solution-budget train-budget)))
                             :step-budget (long (:step-budget test (:step-budget train-budget)))
                             :failure-score (:failure-score train-budget)}))]
    {:arm arm
     :domain domain
     :run-seed (long run-seed)
     :slot-ledger {:assessment-slots total-slots
                   :slots-used (count slots)
                   :distinct-candidates (count distinct-assessments)
                   :duplicate-slots (count (filter :duplicate-slot? slots))
                   :fallback-slots (count (filter :fallback? slots))
                   :construction-attempts (reduce + 0 (map :attempts slots))}
     :slots slots
     :finalists (mapv #(select-keys % [:candidate-id :mean-normalized-hv :node-count]) finalists)
     :validation-results (mapv #(select-keys % [:candidate-id :mean-normalized-hv :node-count]) validation-assessments)
     :selected {:candidate (:candidate selected)
                :candidate-id (:candidate-id selected)
                :node-count (:node-count selected)
                :validation-mean-hv (:mean-normalized-hv selected)}
     :test-evaluation test-evaluation}))
