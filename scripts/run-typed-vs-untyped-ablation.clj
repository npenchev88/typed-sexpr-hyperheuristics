;; Secondary Mechanism Ablation (§12.1):
;; Type-Directed Proposals vs. Untyped Proposals with Validation over the Same Permitted Language.
;; Never executes invalid programs (all untyped proposals are validated by dsl/check-program
;; and dsl/replace-subtree before any runtime evaluation).
;; Part 1: 2,000-operation operator microbenchmark (Generation, Subtree Mutation, Subtree Crossover)
;;         measuring valid-child throughput, rejections, fallbacks, distinct children, CPU time,
;;         and size/role/edit distributions.
;; Part 2: End-to-end 2-replication outer search (Equal-256-Slot and Equal-CPU-Time) comparing
;;         Typed GP (:budget-aware-v2 & :development-only-v1) vs Untyped+Validated GP.
;; Saves full evidence to docs/evidence/typed-vs-untyped-ablation-results.edn and generates
;; docs/figures/typed_vs_untyped_ablation.svg.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-runtime :as port-runtime]
         '[niki.optimizer.portfolio-data :as port-data]
         '[niki.optimizer.scheduling :as scheduling]
         '[niki.optimizer.scheduling-runtime :as sched-runtime]
         '[niki.optimizer.dsl :as dsl]
         '[niki.optimizer.variation :as variation]
         '[niki.optimizer.search :as search])
(import '[java.time Instant]
        '[java.util Random Locale])

(defn fmt [pattern & args]
  (String/format Locale/US pattern (to-array args)))

(defn svg-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- pick [^Random rng xs]
  (nth xs (.nextInt rng (count xs))))

(defn- between [^Random rng lo hi]
  (+ lo (.nextInt rng (inc (- hi lo)))))

(def ^:private all-roles
  [:operation :predicate :score :count :initializer :operator])

(def ^:private untyped-productions
  [[:noop 0]
   [:seq 2]
   [:repeat 2]
   [:if 3]
   [:initialize 1]
   [:step 1]
   [:search 3]
   [:predicate 0]
   [:not 1]
   [:and 2]
   [:less 2]
   [:constant 0]
   [:feature 0]
   [:add 2]
   [:multiply 2]
   [:count 0]
   [:initializer 0]
   [:operator 0]])

(defn- untyped-leaf [^Random rng domain op]
  (case op
    :noop [:noop]
    :count [:count (long (pick rng [2 4 8 12 16 24 32 64]))]
    :initializer [:initializer (pick rng [:random :first])]
    :operator [:operator (pick rng (if (= :portfolio domain)
                                     [:swap :restart]
                                     [:swap :restart :insert :reverse]))]
    :predicate [:predicate (pick rng [:archive-changed :budget-left :coin])]
    :constant [:constant (double (pick rng [-4.0 -2.0 -1.0 -0.5 0.25 0.5 1.0 1.5 2.0 4.0]))]
    :feature [:feature (pick rng [:objective-0 :objective-1 :progress])]))

(defn- grow-untyped-raw
  "Grow a raw syntactic tree from the SAME grammar symbols without conditioning on role types."
  [^Random rng domain nodes depth]
  (let [feasible (filterv (fn [[_ arity]]
                            (and (<= (inc arity) nodes)
                                 (or (zero? arity) (> depth 1))))
                          untyped-productions)
        [op arity] (pick rng feasible)]
    (if (zero? arity)
      (untyped-leaf rng domain op)
      (loop [rem arity avail (dec nodes) children []]
        (if (zero? rem)
          (into [op] children)
          (let [cap (if (== 1 rem) avail (between rng 1 (max 1 (- avail (dec rem)))))]
            (recur (dec rem)
                   (- avail cap)
                   (conj children (grow-untyped-raw rng domain cap (dec depth))))))))))

(defn untyped-generate-program
  "Untyped proposal + dsl/check-program validation over the exact same permitted language.
   Attempts up to `max-attempts` (default 10) raw trees; never returns an invalid program
   (falls back to canonical [:program domain [:budget [:noop]]] if all 10 attempts fail validation)."
  [domain {:keys [seed limits max-attempts] :or {seed 0 limits {} max-attempts 10}}]
  (let [eff-limits (dsl/effective-limits limits)
        rng (Random. (long seed))]
    (loop [attempt 1 rejections []]
      (if (> attempt max-attempts)
        (let [fallback-prog (:program (dsl/check-program [:program domain [:budget [:noop]]] eff-limits))]
          {:program fallback-prog
           :child-id (dsl/program-id fallback-prog eff-limits)
           :parent-ids []
           :paths []
           :changed? false
           :attempts max-attempts
           :fallback? true
           :fallback-reason :validation-exhausted
           :rejections rejections
           :seed (long seed)
           :limits eff-limits})
        (let [nodes (between rng 1 12)
              depth (between rng 1 5)
              raw-body (grow-untyped-raw rng domain nodes depth)
              res (try
                    {:checked (dsl/check-program [:program domain [:budget raw-body]] eff-limits)}
                    (catch clojure.lang.ExceptionInfo e
                      {:rejection {:attempt attempt :reason :type-or-grammar-rejection :message (.getMessage e)}}))]
          (if (:rejection res)
            (recur (inc attempt) (conj rejections (:rejection res)))
            (let [p (get-in res [:checked :program])]
              {:program p
               :child-id (dsl/program-id p eff-limits)
               :parent-ids []
               :paths []
               :changed? true
               :attempts attempt
               :fallback? false
               :rejections rejections
               :seed (long seed)
               :limits eff-limits})))))))

(defn untyped-mutate-program
  "Untyped subtree mutation + dsl/replace-subtree validation (up to 10 attempts).
   Selects a random target site in parent, proposes a subtree without conditioning on
   the site's required type, and validates with dsl/replace-subtree."
  [program {:keys [seed limits max-attempts] :or {seed 0 limits {} max-attempts 10}}]
  (let [eff-limits (dsl/effective-limits limits)
        checked (dsl/check-program program eff-limits)
        parent (:program checked)
        parent-id (dsl/program-id parent eff-limits)
        rng (Random. (long seed))
        sites (:sites checked)]
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
        (let [{:keys [path type]} (pick rng sites)
              old (get-in parent path)
              nodes (between rng 1 8)
              depth (between rng 1 4)
              raw-sub (grow-untyped-raw rng (:domain checked) nodes depth)
              res (try
                    {:program (dsl/replace-subtree parent path raw-sub eff-limits)}
                    (catch clojure.lang.ExceptionInfo e
                      {:rejection {:attempt attempt :path path :site-role (first type)
                                   :reason :type-or-structural-rejection :message (.getMessage e)}}))]
          (cond
            (:rejection res)
            (recur (inc attempt) (conj rejections (:rejection res)) path)
            (= old raw-sub)
            (recur (inc attempt) (conj rejections {:attempt attempt :path path :site-role (first type)
                                                   :reason :identical-subtree}) path)
            :else
            (let [child (:program res)]
              {:program child
               :child-id (dsl/program-id child eff-limits)
               :parent-ids [parent-id]
               :attempts attempt
               :fallback? false
               :changed? (not= parent child)
               :path path
               :site-role (first type)
               :paths [path]
               :rejections rejections
               :seed (long seed)
               :limits eff-limits})))))))

(defn untyped-crossover-programs
  "Untyped subtree crossover + dsl/replace-subtree validation (up to 10 attempts).
   Samples site pairs from all sites without pre-filtering by type compatibility,
   requiring both children to pass dsl/replace-subtree."
  [left right {:keys [seed limits max-attempts] :or {seed 0 limits {} max-attempts 10}}]
  (let [eff-limits (dsl/effective-limits limits)
        a (dsl/check-program left eff-limits)
        b (dsl/check-program right eff-limits)
        p1 (:program a)
        p2 (:program b)
        id1 (dsl/program-id p1 eff-limits)
        id2 (dsl/program-id p2 eff-limits)
        rng (Random. (long seed))
        all-pairs (vec (for [sa (:sites a) sb (:sites b)] [sa sb]))]
    (if (= id1 id2)
      {:children [p1 p2] :child-ids [id1 id2] :parent-ids [id1 id2]
       :attempts 0 :fallback? true :changed? [false false] :rejections [{:reason :same-parent}]}
      (loop [attempt 1 avail all-pairs rejections []]
        (if (or (> attempt max-attempts) (empty? avail))
          {:children [p1 p2] :child-ids [id1 id2] :parent-ids [id1 id2]
           :attempts (dec attempt) :fallback? true :changed? [false false] :rejections rejections}
          (let [idx (.nextInt rng (count avail))
                [sa sb] (nth avail idx)
                pa (:path sa)
                pb (:path sb)
                sub-a (get-in p1 pa)
                sub-b (get-in p2 pb)
                res (try
                      (let [c1 (dsl/replace-subtree p1 pa sub-b eff-limits)
                            c2 (dsl/replace-subtree p2 pb sub-a eff-limits)]
                        {:children [c1 c2]})
                      (catch clojure.lang.ExceptionInfo e
                        {:rejection {:attempt attempt :paths [pa pb]
                                     :roles [(first (:type sa)) (first (:type sb))]
                                     :reason :type-or-effect-mismatch}}))]
            (cond
              (:rejection res)
              (recur (inc attempt)
                     (into (subvec avail 0 idx) (subvec avail (inc idx)))
                     (conj rejections (:rejection res)))
              (= sub-a sub-b)
              (recur (inc attempt)
                     (into (subvec avail 0 idx) (subvec avail (inc idx)))
                     (conj rejections {:attempt attempt :paths [pa pb] :reason :identical-subtree}))
              :else
              (let [[c1 c2] (:children res)]
                {:children [c1 c2]
                 :child-ids [(dsl/program-id c1 eff-limits) (dsl/program-id c2 eff-limits)]
                 :parent-ids [id1 id2]
                 :attempts attempt
                 :fallback? false
                 :changed? [true true]
                 :roles [(first (:type sa)) (first (:type sb))]
                 :rejections rejections}))))))))

(defn- ast-node-count [p]
  (count (tree-seq vector? #(filterv vector? %) p)))

(defn run-operator-microbenchmark
  "Run N=1000 seeded operations for Generation, Mutation, and Crossover across
   :typed-v2, :typed-v1, and :untyped-validated."
  [domain n]
  (let [parent-pool (mapv #(:program (search/generate-program-v2 domain {:seed (+ 5000 %)})) (range 100))
        bench-gen
        (fn [mode gen-fn]
          (let [t0 (System/nanoTime)
                runs (mapv #(gen-fn domain {:seed (+ 10000 %)}) (range n))
                elapsed-ms (/ (double (- (System/nanoTime) t0)) 1.0e6)
                valid-runs (filterv #(not (:fallback? %)) runs)
                total-attempts (reduce + 0 (map :attempts runs))
                total-rejects (reduce + 0 (map #(count (:rejections %)) runs))
                distinct-ids (count (set (map :child-id valid-runs)))
                mean-nodes (/ (reduce + 0.0 (map #(ast-node-count (:program %)) runs)) (double n))]
            {:mode mode :operator :generate :n n
             :valid-children (count valid-runs)
             :valid-rate-pct (* 100.0 (/ (double (count valid-runs)) (double n)))
             :fallback-rate-pct (* 100.0 (/ (double (- n (count valid-runs))) (double n)))
             :total-attempts total-attempts
             :total-rejections total-rejects
             :attempt-success-rate-pct (* 100.0 (/ (double (count valid-runs)) (double (max 1 total-attempts))))
             :distinct-valid-children distinct-ids
             :mean-node-count mean-nodes
             :elapsed-ms elapsed-ms}))
        bench-mut
        (fn [mode mut-fn]
          (let [t0 (System/nanoTime)
                runs (mapv (fn [i]
                             (let [p (nth parent-pool (mod i (count parent-pool)))]
                               (mut-fn p {:seed (+ 20000 i)})))
                           (range n))
                elapsed-ms (/ (double (- (System/nanoTime) t0)) 1.0e6)
                valid-runs (filterv #(not (:fallback? %)) runs)
                total-attempts (reduce + 0 (map :attempts runs))
                total-rejects (reduce + 0 (map #(count (:rejections %)) runs))
                distinct-ids (count (set (map :child-id valid-runs)))
                mean-nodes (/ (reduce + 0.0 (map #(ast-node-count (:program %)) runs)) (double n))
                role-counts (frequencies (keep :site-role valid-runs))]
            {:mode mode :operator :mutate :n n
             :valid-children (count valid-runs)
             :valid-rate-pct (* 100.0 (/ (double (count valid-runs)) (double n)))
             :fallback-rate-pct (* 100.0 (/ (double (- n (count valid-runs))) (double n)))
             :total-attempts total-attempts
             :total-rejections total-rejects
             :attempt-success-rate-pct (* 100.0 (/ (double (count valid-runs)) (double (max 1 total-attempts))))
             :distinct-valid-children distinct-ids
             :mean-node-count mean-nodes
             :accepted-edit-role-distribution role-counts
             :elapsed-ms elapsed-ms}))
        bench-cross
        (fn [mode cross-fn]
          (let [t0 (System/nanoTime)
                runs (mapv (fn [i]
                             (let [p1 (nth parent-pool (mod i (count parent-pool)))
                                   p2 (nth parent-pool (mod (+ i 17) (count parent-pool)))]
                               (cross-fn p1 p2 {:seed (+ 30000 i)})))
                           (range n))
                elapsed-ms (/ (double (- (System/nanoTime) t0)) 1.0e6)
                valid-runs (filterv #(not (:fallback? %)) runs)
                total-attempts (reduce + 0 (map :attempts runs))
                total-rejects (reduce + 0 (map #(count (:rejections %)) runs))
                distinct-ids (count (set (map #(first (:child-ids %)) valid-runs)))
                mean-nodes (/ (reduce + 0.0 (map #(ast-node-count (first (:children %))) runs)) (double n))]
            {:mode mode :operator :crossover :n n
             :valid-children (count valid-runs)
             :valid-rate-pct (* 100.0 (/ (double (count valid-runs)) (double n)))
             :fallback-rate-pct (* 100.0 (/ (double (- n (count valid-runs))) (double n)))
             :total-attempts total-attempts
             :total-rejections total-rejects
             :attempt-success-rate-pct (* 100.0 (/ (double (count valid-runs)) (double (max 1 total-attempts))))
             :distinct-valid-children distinct-ids
             :mean-node-count mean-nodes
             :elapsed-ms elapsed-ms}))]
    [(bench-gen :typed-budget-aware-v2 search/generate-program-v2)
     (bench-gen :typed-development-v1 variation/generate-program)
     (bench-gen :untyped-validated untyped-generate-program)
     (bench-mut :typed-budget-aware-v2 search/mutate-program-v2)
     (bench-mut :typed-development-v1 variation/mutate)
     (bench-mut :untyped-validated untyped-mutate-program)
     (bench-cross :typed-v1-v2 variation/crossover)
     (bench-cross :untyped-validated untyped-crossover-programs)]))

;; Outer runner for Untyped+Validated GP (identical equal-256-slot ledger as search/run-arm)
(defn run-untyped-gp-arm
  [{:keys [arm domain run-seed outer train validation test]}]
  (let [init-pop (long (:initial-population outer 32))
        batches (long (:offspring-batches outer 7))
        batch-size (long (:offspring-per-batch outer 32))
        total-slots (long (:assessment-slots outer 256))
        crossover-prob (double (:mixed-crossover-probability outer 0.5))
        limits (dsl/effective-limits (:limits outer {}))
        train-tasks (:tasks train)
        train-seeds (:inner-seeds train [101 102])
        train-budget {:solution-budget (long (:solution-proposals train 256))
                      :step-budget (long (:step-budget train 4096))
                      :failure-score 0.0}
        mix-seed (fn [& parts]
                   (long (reduce (fn [^long acc x]
                                   (unchecked-add (unchecked-multiply acc 6364136223846793005)
                                                  (bit-xor (long (hash x)) 1442695040888963407)))
                                 20261108 parts)))
        tournament-select (fn [pool ^Random rng]
                            (let [a (pick rng pool)
                                  b (pick rng pool)]
                              (if (<= (compare (:selection-key a) (:selection-key b)) 0) a b)))
        construct-slot
        (fn [slot-idx pool]
          (if (< slot-idx init-pop)
            (let [init-seed (mix-seed domain run-seed :init slot-idx)
                  g (untyped-generate-program domain {:seed init-seed :limits limits})]
              {:candidate (:program g) :candidate-id (:child-id g)
               :operator :untyped-generate :attempts (:attempts g)
               :rejections (count (:rejections g))
               :fallback? (:fallback? g) :parent-ids []})
            (let [slot-seed (mix-seed domain run-seed arm :slot slot-idx)
                  slot-rng (Random. slot-seed)]
              (if (and (= arm :gp-mixed) (< (.nextDouble slot-rng) crossover-prob))
                (let [p1 (tournament-select pool slot-rng)
                      p2 (tournament-select pool slot-rng)
                      c (untyped-crossover-programs (:candidate p1) (:candidate p2) {:seed slot-seed :limits limits})]
                  {:candidate (first (:children c)) :candidate-id (first (:child-ids c))
                   :operator :untyped-crossover :attempts (:attempts c)
                   :rejections (count (:rejections c))
                   :fallback? (:fallback? c) :parent-ids (:parent-ids c)})
                (let [p1 (tournament-select pool slot-rng)
                      m (untyped-mutate-program (:candidate p1) {:seed slot-seed :limits limits})]
                  {:candidate (:program m) :candidate-id (:child-id m)
                   :operator :untyped-mutate :attempts (:attempts m)
                   :rejections (count (:rejections m))
                   :fallback? (:fallback? m) :parent-ids (:parent-ids m)})))))
        {:keys [slots assessed-by-id]}
        (loop [slot-idx 0 batch-pool [] current-batch [] slots [] assessed-by-id {}]
          (if (= slot-idx total-slots)
            {:slots slots :assessed-by-id assessed-by-id}
            (let [boundary? (and (>= slot-idx init-pop) (zero? (mod (- slot-idx init-pop) batch-size)))
                  active-pool (if boundary? (into batch-pool current-batch) (if (= slot-idx init-pop) current-batch batch-pool))
                  active-batch (if (or boundary? (= slot-idx init-pop)) [] current-batch)
                  constructed (construct-slot slot-idx active-pool)
                  cid (:candidate-id constructed)
                  duplicate? (contains? assessed-by-id cid)
                  assessment (if duplicate?
                               (get assessed-by-id cid)
                               (search/assess-candidate arm (:candidate constructed) cid train-tasks train-seeds train-budget))
                  slot-record {:slot slot-idx
                               :candidate-id cid
                               :operator (:operator constructed)
                               :attempts (:attempts constructed)
                               :rejections (:rejections constructed)
                               :fallback? (:fallback? constructed)
                               :duplicate-slot? duplicate?
                               :training-mean-hv (:mean-normalized-hv assessment)
                               :node-count (:node-count assessment)}]
              (recur (inc slot-idx)
                     active-pool
                     (conj active-batch assessment)
                     (conj slots slot-record)
                     (if duplicate? assessed-by-id (assoc assessed-by-id cid assessment))))))
        distinct-assessments (vec (sort-by :selection-key (vals assessed-by-id)))
        finalists (vec (take (long (:max-finalists validation 8)) distinct-assessments))
        val-assessments (mapv #(search/assess-candidate arm (:candidate %) (:candidate-id %)
                                                        (:tasks validation) (:inner-seeds validation) train-budget)
                              finalists)
        selected (first (sort-by :selection-key val-assessments))
        test-eval (search/assess-candidate arm (:candidate selected) (:candidate-id selected)
                                           (:tasks test) (:inner-seeds test) train-budget)]
    {:arm arm :domain domain :run-seed run-seed :generator-prior :untyped-validated
     :slot-ledger {:assessment-slots total-slots
                   :slots-used (count slots)
                   :distinct-candidates (count distinct-assessments)
                   :duplicate-slots (count (filter :duplicate-slot? slots))
                   :fallback-slots (count (filter :fallback? slots))
                   :construction-attempts (reduce + 0 (map :attempts slots))
                   :validation-rejections (reduce + 0 (map :rejections slots))}
     :selected {:candidate (:candidate selected)
                :candidate-id (:candidate-id selected)
                :node-count (:node-count selected)
                :validation-mean-hv (:mean-normalized-hv selected)}
     :test-mean-normalized-hv (:mean-normalized-hv test-eval)}))

(defn prepare-portfolio-split [tasks]
  (mapv (fn [t]
          {:task-id (:task-id t)
           :adapter (port-runtime/make-adapter (portfolio/prepare-instance (:panel t)))})
        tasks))

(defn prepare-scheduling-split [instances]
  (mapv (fn [inst]
          {:task-id (:instance-id inst)
           :adapter (sched-runtime/make-adapter (scheduling/prepare-instance inst))})
        instances))

(let [started-ns (System/nanoTime)
      _ (println "Part 1: Running 1,000-operation Typed vs Untyped+Validated microbenchmark...")
      micro-port (run-operator-microbenchmark :portfolio 1000)
      micro-sched (run-operator-microbenchmark :scheduling 1000)
      _ (doseq [[dom rows] [[:portfolio micro-port] [:scheduling micro-sched]]]
          (println "\n--- Microbenchmark (N=1,000 per row) Domain:" dom "---")
          (doseq [r rows]
            (println (format "  %-10s | %-23s | ValidChild: %5.1f%% | Fallbacks: %5.1f%% | Attempts: %5d (Rejects: %4d, HitRate: %5.1f%%) | Distinct/1000: %4d | AvgNodes: %4.1f | Time: %5.1fms"
                             (name (:operator r))
                             (name (:mode r))
                             (double (:valid-rate-pct r))
                             (double (:fallback-rate-pct r))
                             (long (:total-attempts r))
                             (long (:total-rejections r))
                             (double (:attempt-success-rate-pct r))
                             (long (:distinct-valid-children r))
                             (double (:mean-node-count r))
                             (double (:elapsed-ms r))))))
      _ (println "\nPart 2: Running 2-replication Untyped+Validated outer GP runs on :portfolio (NASDAQ-50) and :scheduling (OR-Library wt40)...")
      two-rep (edn/read-string (slurp "docs/evidence/g8-thirty-replications-results.edn"))
      port-raw (port-data/load-nasdaq-suite)
      sched-raw (scheduling/load-orlib-wt-suite)
      domains {:portfolio {:train (prepare-portfolio-split (:train port-raw))
                           :validation (prepare-portfolio-split (:validation port-raw))
                           :test (prepare-portfolio-split (:test port-raw))}
               :scheduling {:train (prepare-scheduling-split (:train sched-raw))
                            :validation (prepare-scheduling-split (:validation sched-raw))
                            :test (prepare-scheduling-split (:test sched-raw))}}
      base-outer {:initial-population 32 :offspring-batches 7 :offspring-per-batch 32
                  :assessment-slots 256 :mixed-crossover-probability 0.5}
      outer-comparison
      (into {}
            (for [[domain splits] domains]
              (let [jobs (for [rseed [0 1] arm [:gp-mixed :gp-mutation-only]] {:run-seed rseed :arm arm})
                    untyped-runs
                    (vec
                     (pmap
                      (fn [{:keys [run-seed arm]}]
                        (let [t0 (System/nanoTime)
                              res (run-untyped-gp-arm
                                   {:arm arm :domain domain :run-seed run-seed :outer base-outer
                                    :train {:tasks (:train splits) :inner-seeds [101 102] :solution-proposals 256 :step-budget 4096}
                                    :validation {:max-finalists 8 :tasks (:validation splits) :inner-seeds [201 202]}
                                    :test {:tasks (:test splits) :inner-seeds (vec (range 1001 1011))}})
                              elapsed-s (/ (double (- (System/nanoTime) t0)) 1.0e9)]
                          (assoc res :elapsed-seconds elapsed-s)))
                      jobs))
                    by-arm (group-by :arm untyped-runs)
                    untyped-summaries
                    (mapv (fn [arm]
                            (let [rs (sort-by :run-seed (get by-arm arm))
                                  avg (fn [f] (/ (reduce + 0.0 (map f rs)) (double (count rs))))]
                              {:arm arm
                               :generator-prior :untyped-validated
                               :replication-test-hvs (mapv :test-mean-normalized-hv rs)
                               :mean-test-hv (avg :test-mean-normalized-hv)
                               :mean-distinct-candidates (avg #(get-in % [:slot-ledger :distinct-candidates]))
                               :mean-fallback-slots (avg #(get-in % [:slot-ledger :fallback-slots]))
                               :mean-construction-attempts (avg #(get-in % [:slot-ledger :construction-attempts]))
                               :mean-validation-rejections (avg #(get-in % [:slot-ledger :validation-rejections]))
                               :mean-elapsed-seconds (avg :elapsed-seconds)
                               :replications (vec rs)}))
                          [:gp-mixed :gp-mutation-only])
                    existing-typed
                    (->> (get-in two-rep [:domains domain :arm-summaries])
                         (filterv #(contains? #{:gp-mixed :gp-mutation-only} (:arm %)))
                         (mapv (fn [s]
                                 (let [rs (vec (take 2 (:replications s)))
                                       hvs (mapv :test-mean-normalized-hv rs)]
                                   (assoc s
                                          :replications rs
                                          :replication-test-hvs hvs
                                          :mean-test-hv (/ (reduce + 0.0 hvs) (double (count hvs))))))))]
                [domain {:untyped-summaries untyped-summaries
                         :typed-summaries (mapv #(select-keys % [:arm :generator-prior :replication-test-hvs
                                                                 :mean-test-hv :mean-distinct-candidates])
                                                existing-typed)}])))
      total-s (/ (double (- (System/nanoTime) started-ns)) 1.0e9)
      out-data {:experiment :type-directed-vs-untyped-ablation
                :executed-at (str (Instant/now))
                :total-elapsed-seconds total-s
                :microbenchmarks {:portfolio micro-port :scheduling micro-sched}
                :outer-search outer-comparison}]
  (io/make-parents "docs/evidence/typed-vs-untyped-ablation-results.edn")
  (spit "docs/evidence/typed-vs-untyped-ablation-results.edn" (str (pr-str out-data) "\n") :encoding "UTF-8")
  (println "\nCompleted Typed vs Untyped+Validated Ablation in" (format "%.2f" total-s) "seconds.")
  (doseq [domain [:portfolio :scheduling]]
    (println "\n=========================================================================================================")
    (println "OUTER SEARCH (256 Slots, 2 Replications) — DOMAIN:" domain)
    (println "=========================================================================================================")
    (doseq [s (get-in outer-comparison [domain :typed-summaries])]
      (println (format "  %-18s [%-20s] | Rep0: %.5f | Rep1: %.5f | Mean Test HV: %.5f | Distinct/256: %5.1f"
                       (name (:arm s)) (name (:generator-prior s))
                       (double (first (:replication-test-hvs s)))
                       (double (second (:replication-test-hvs s)))
                       (double (:mean-test-hv s))
                       (double (:mean-distinct-candidates s)))))
    (doseq [s (get-in outer-comparison [domain :untyped-summaries])]
      (println (format "  %-18s [%-20s] | Rep0: %.5f | Rep1: %.5f | Mean Test HV: %.5f | Distinct/256: %5.1f | Fallbacks/256: %5.1f | Attempts: %6.1f (Rejects: %6.1f)"
                       (name (:arm s)) (name (:generator-prior s))
                       (double (first (:replication-test-hvs s)))
                       (double (second (:replication-test-hvs s)))
                       (double (:mean-test-hv s))
                       (double (:mean-distinct-candidates s))
                       (double (:mean-fallback-slots s))
                       (double (:mean-construction-attempts s))
                       (double (:mean-validation-rejections s))))))

  ;; Render publication-grade SVG: docs/figures/typed_vs_untyped_ablation.svg
  (let [width 920
        height 540
        sb (StringBuilder.)]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"Inter, Segoe UI, Arial, sans-serif\">\n"
                     width height width height))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" rx=\"8\"/>\n" width height))
    (.append sb "  <text x=\"65\" y=\"34\" font-size=\"18\" font-weight=\"700\" fill=\"#0f172a\">Secondary Ablation: Type-Directed vs. Untyped Variation with Validation</text>\n")
    (.append sb "  <text x=\"65\" y=\"55\" font-size=\"13\" fill=\"#475569\">Left: Per-Attempt Validation Success Rate (%) across 1,000 Operations. Right: End-to-End Held-Out Test HV (256 Slots).</text>\n")

    ;; Left Panel: Per-Attempt Validation Success Rate (%) for Generate, Mutate, Crossover
    (let [x0 65 y0 98 pw 375 ph 340
          py (fn [pct] (+ y0 (- ph (* (/ (double pct) 100.0) ph))))
          ops [[:generate "Generate AST"] [:mutate "Subtree Mutate"] [:crossover "Subtree Crossover"]]
          col-w (/ (double pw) 3.0)
          find-m (fn [op mode] (first (filter #(and (= op (:operator %)) (= mode (:mode %))) micro-port)))]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"14\" font-weight=\"700\" fill=\"#0f172a\">A. Valid-Attempt Throughput Rate (%%)</text>\n"
                       x0 (- y0 14)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1.2\"/>\n"
                       x0 y0 pw ph))
      (doseq [i (range 6)
              :let [yv (* i 20.0)
                    cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                         x0 cy (+ x0 pw) cy))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"10.5\" fill=\"#475569\" text-anchor=\"end\" dominant-baseline=\"middle\">%.0f%%</text>\n"
                         (- x0 8) cy yv)))
      (doseq [[idx [op label]] (map-indexed vector ops)
              :let [cx (+ x0 (* (+ idx 0.5) col-w))
                    bw 24.0
                    m-typed (or (find-m op :typed-budget-aware-v2) (find-m op :typed-v1-v2))
                    m-untyped (find-m op :untyped-validated)
                    r-t (:attempt-success-rate-pct m-typed)
                    r-u (:attempt-success-rate-pct m-untyped)
                    yt (py r-t)
                    yu (py r-u)]]
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"3\" fill=\"#10b981\" fill-opacity=\"0.88\" stroke=\"#047857\" stroke-width=\"1.2\"/>\n"
                         (- cx bw 2.0) yt bw (max 2.0 (- (+ y0 ph) yt))))
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"3\" fill=\"#f43f5e\" fill-opacity=\"0.85\" stroke=\"#be123c\" stroke-width=\"1.2\"/>\n"
                         (+ cx 2.0) yu bw (max 2.0 (- (+ y0 ph) yu))))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"10\" font-weight=\"700\" fill=\"#047857\" text-anchor=\"middle\">%.1f%%</text>\n"
                         (- cx (/ bw 2.0) 2.0) (- yt 6.0) r-t))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"10\" font-weight=\"700\" fill=\"#be123c\" text-anchor=\"middle\">%.1f%%</text>\n"
                         (+ cx (/ bw 2.0) 2.0) (- yu 6.0) r-u))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"11\" font-weight=\"600\" fill=\"#1e293b\" text-anchor=\"middle\">%s</text>\n"
                         cx (+ y0 ph 20) label))))

    ;; Right Panel: End-to-End Outer Search Test HV (Typed v2 vs Typed v1 vs Untyped+Validated)
    (let [x0 505 y0 98 pw 375 ph 340
          y-min 0.45 y-max 0.67
          y-span (- y-max y-min)
          py (fn [v] (+ y0 (- ph (* (/ (- (double (max y-min v)) y-min) y-span) ph))))
          groups [[:portfolio :gp-mixed "Port: GP-Mixed"]
                  [:portfolio :gp-mutation-only "Port: GP-Mut"]
                  [:scheduling :gp-mixed "Sched: GP-Mixed"]
                  [:scheduling :gp-mutation-only "Sched: GP-Mut"]]
          col-w (/ (double pw) (count groups))]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"14\" font-weight=\"700\" fill=\"#0f172a\">B. End-to-End Test HV (Typed vs. Untyped GP)</text>\n"
                       x0 (- y0 14)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1.2\"/>\n"
                       x0 y0 pw ph))
      (doseq [i (range 6)
              :let [yv (+ y-min (* (/ (double i) 5.0) y-span))
                    cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                         x0 cy (+ x0 pw) cy))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"10.5\" fill=\"#475569\" text-anchor=\"end\" dominant-baseline=\"middle\">%.3f</text>\n"
                         (- x0 8) cy yv)))
      (doseq [[idx [dom arm lbl]] (map-indexed vector groups)
              :let [cx (+ x0 (* (+ idx 0.5) col-w))
                    bw 17.0
                    t-sums (get-in outer-comparison [dom :typed-summaries])
                    u-sums (get-in outer-comparison [dom :untyped-summaries])
                    v2 (first (filter #(and (= arm (:arm %)) (= :budget-aware-v2 (:generator-prior %))) t-sums))
                    v1 (first (filter #(and (= arm (:arm %)) (= :development-only-v1 (:generator-prior %))) t-sums))
                    un (first (filter #(= arm (:arm %)) u-sums))
                    hv-v2 (:mean-test-hv v2)
                    hv-v1 (:mean-test-hv v1)
                    hv-un (:mean-test-hv un)
                    y-v2 (py hv-v2)
                    y-v1 (py hv-v1)
                    y-un (py hv-un)]]
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"2.5\" fill=\"#10b981\" fill-opacity=\"0.88\" stroke=\"#047857\" stroke-width=\"1.1\"/>\n"
                         (- cx (* 1.5 bw) 2.0) y-v2 bw (max 2.0 (- (+ y0 ph) y-v2))))
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"2.5\" fill=\"#f59e0b\" fill-opacity=\"0.85\" stroke=\"#b45309\" stroke-width=\"1.1\"/>\n"
                         (- cx (* 0.5 bw)) y-v1 bw (max 2.0 (- (+ y0 ph) y-v1))))
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"2.5\" fill=\"#f43f5e\" fill-opacity=\"0.85\" stroke=\"#be123c\" stroke-width=\"1.1\"/>\n"
                         (+ cx (* 0.5 bw) 2.0) y-un bw (max 2.0 (- (+ y0 ph) y-un))))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"10.5\" font-weight=\"600\" fill=\"#1e293b\" text-anchor=\"middle\">%s</text>\n"
                         cx (+ y0 ph 20) lbl))))

    ;; Legend
    (let [ly 482]
      (.append sb (fmt "  <rect x=\"65\" y=\"%d\" width=\"815\" height=\"40\" rx=\"6\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1\"/>\n" ly))
      (.append sb (fmt "  <rect x=\"85\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#10b981\" stroke=\"#047857\"/>\n" (+ ly 13)))
      (.append sb (fmt "  <text x=\"105\" y=\"%d\" font-size=\"11.5\" font-weight=\"600\" fill=\"#064e3b\" dominant-baseline=\"middle\">Type-Directed (:budget-aware-v2)</text>\n" (+ ly 21)))
      (.append sb (fmt "  <rect x=\"345\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#f59e0b\" stroke=\"#b45309\"/>\n" (+ ly 13)))
      (.append sb (fmt "  <text x=\"365\" y=\"%d\" font-size=\"11.5\" fill=\"#1e293b\" dominant-baseline=\"middle\">Type-Directed (:development-only-v1)</text>\n" (+ ly 21)))
      (.append sb (fmt "  <rect x=\"615\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#f43f5e\" stroke=\"#be123c\"/>\n" (+ ly 13)))
      (.append sb (fmt "  <text x=\"635\" y=\"%d\" font-size=\"11.5\" fill=\"#be123c\" dominant-baseline=\"middle\">Untyped + Validation (:untyped-validated)</text>\n" (+ ly 21))))
    (.append sb "</svg>\n")
    (spit "docs/figures/typed_vs_untyped_ablation.svg" (.toString sb) :encoding "UTF-8")
    (println "Wrote SVG: docs/figures/typed_vs_untyped_ablation.svg"))
  (shutdown-agents))
