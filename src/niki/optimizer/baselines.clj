(ns niki.optimizer.baselines
  "Conventional multiobjective algorithm families (:nsga2, :pareto-local-search,
  :multi-start-restart) and direct deployment controls (:random-search,
  :initializer-only, :heuristic-local-search).
  Every candidate goes through a strict proposal ledger matching niki.optimizer.runtime."
  (:require [niki.optimizer.pareto :as pareto])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]
           [java.util Random]))

(defn- ensure! [condition code message data]
  (when-not condition
    (throw (ex-info message (assoc data :error code)))))

(defn canonical-config-edn [config]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-readably* true *print-dup* false]
    (pr-str (into (sorted-map) config))))

(defn config-id [config]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes ^String (canonical-config-edn config) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))

(def parameter-space
  "Published configuration parameter space for Arm A5 (:configured-conventional)."
  {:families [:nsga2 :pareto-local-search :multi-start-restart]
   :nsga2 {:population-size [4 8 16 32]
           :initializer [:random :first]
           :operator {:portfolio [:swap :restart]
                      :scheduling [:swap :restart :insert :reverse]}
           :perturbation-steps [1 2 3]}
   :pareto-local-search {:initial-starts [1 2 4 8]
                         :initializer [:random :first]
                         :operator {:portfolio [:swap :restart]
                                    :scheduling [:swap :restart :insert :reverse]}
                         :neighbors-per-step [1 2 4 8]
                         :selection-rule [:unvisited-archive :crowding-archive :random-archive]}
   :multi-start-restart {:restart-period [4 8 16 32]
                         :initializer [:random :first]
                         :operator {:portfolio [:swap :restart]
                                    :scheduling [:swap :restart :insert :reverse]}
                         :weight-schedule [:balanced :alternating :uniform-grid]}})

(defn- pick [^Random rng xs]
  (nth xs (.nextInt rng (count xs))))

(defn validate-conventional-config
  "Validate and canonicalize a conventional solver configuration map."
  [config]
  (ensure! (map? config) :invalid-config "Conventional config must be a map" {})
  (let [domain (:domain config)
        family (:family config)]
    (ensure! (contains? #{:portfolio :scheduling} domain)
             :invalid-domain "Unknown config domain" {:domain domain})
    (ensure! (contains? #{:nsga2 :pareto-local-search :multi-start-restart} family)
             :invalid-family "Unknown conventional solver family" {:family family})
    (let [allowed-ops (set (get-in parameter-space [family :operator domain]))]
      (case family
        :nsga2
        (let [{:keys [population-size initializer operator perturbation-steps]} config]
          (ensure! (and (contains? (set (get-in parameter-space [:nsga2 :population-size])) population-size)
                        (contains? #{:random :first} initializer)
                        (contains? allowed-ops operator)
                        (contains? (set (get-in parameter-space [:nsga2 :perturbation-steps])) perturbation-steps))
                   :invalid-nsga2-config "Invalid :nsga2 parameters" {:config config})
          {:domain domain :family :nsga2
           :population-size (long population-size)
           :initializer initializer
           :operator operator
           :perturbation-steps (long perturbation-steps)})

        :pareto-local-search
        (let [{:keys [initial-starts initializer operator neighbors-per-step selection-rule]} config]
          (ensure! (and (contains? (set (get-in parameter-space [:pareto-local-search :initial-starts])) initial-starts)
                        (contains? #{:random :first} initializer)
                        (contains? allowed-ops operator)
                        (contains? (set (get-in parameter-space [:pareto-local-search :neighbors-per-step])) neighbors-per-step)
                        (contains? (set (get-in parameter-space [:pareto-local-search :selection-rule])) selection-rule))
                   :invalid-pls-config "Invalid :pareto-local-search parameters" {:config config})
          {:domain domain :family :pareto-local-search
           :initial-starts (long initial-starts)
           :initializer initializer
           :operator operator
           :neighbors-per-step (long neighbors-per-step)
           :selection-rule selection-rule})

        :multi-start-restart
        (let [{:keys [restart-period initializer operator weight-schedule]} config]
          (ensure! (and (contains? (set (get-in parameter-space [:multi-start-restart :restart-period])) restart-period)
                        (contains? #{:random :first} initializer)
                        (contains? allowed-ops operator)
                        (contains? (set (get-in parameter-space [:multi-start-restart :weight-schedule])) weight-schedule))
                   :invalid-restart-config "Invalid :multi-start-restart parameters" {:config config})
          {:domain domain :family :multi-start-restart
           :restart-period (long restart-period)
           :initializer initializer
           :operator operator
           :weight-schedule weight-schedule})))))

(defn sample-conventional-config
  "Deterministically sample a conventional solver configuration from parameter-space."
  [domain {:keys [seed] :or {seed 0}}]
  (ensure! (contains? #{:portfolio :scheduling} domain) :invalid-domain "Unknown domain" {:domain domain})
  (let [rng (Random. (long seed))
        family (pick rng (:families parameter-space))
        ops (get-in parameter-space [family :operator domain])
        raw (case family
              :nsga2
              {:domain domain
               :family :nsga2
               :population-size (pick rng (get-in parameter-space [:nsga2 :population-size]))
               :initializer (pick rng (get-in parameter-space [:nsga2 :initializer]))
               :operator (pick rng ops)
               :perturbation-steps (pick rng (get-in parameter-space [:nsga2 :perturbation-steps]))}
              :pareto-local-search
              {:domain domain
               :family :pareto-local-search
               :initial-starts (pick rng (get-in parameter-space [:pareto-local-search :initial-starts]))
               :initializer (pick rng (get-in parameter-space [:pareto-local-search :initializer]))
               :operator (pick rng ops)
               :neighbors-per-step (pick rng (get-in parameter-space [:pareto-local-search :neighbors-per-step]))
               :selection-rule (pick rng (get-in parameter-space [:pareto-local-search :selection-rule]))}
              :multi-start-restart
              {:domain domain
               :family :multi-start-restart
               :restart-period (pick rng (get-in parameter-space [:multi-start-restart :restart-period]))
               :initializer (pick rng (get-in parameter-space [:multi-start-restart :initializer]))
               :operator (pick rng ops)
               :weight-schedule (pick rng (get-in parameter-space [:multi-start-restart :weight-schedule]))})
        checked (validate-conventional-config raw)]
    {:config checked
     :config-id (config-id checked)
     :seed (long seed)}))

(defn- dominates? [[a0 a1] [b0 b1]]
  (and (<= a0 b0) (<= a1 b1) (or (< a0 b0) (< a1 b1))))

(defn- crowding-distances
  "Compute 2-D crowding distances for a vector of points (each with :normalized [x y])."
  [front-items]
  (let [n (count front-items)]
    (if (<= n 2)
      (vec (repeat n Double/POSITIVE_INFINITY))
      (let [indexed (vec (map-indexed vector front-items))
            dist (double-array n 0.0)]
        (doseq [coord [0 1]]
          (let [sorted (vec (sort-by (fn [[idx item]] [(get-in item [:normalized coord]) idx]) indexed))
                lo (get-in (first sorted) [1 :normalized coord])
                hi (get-in (peek sorted) [1 :normalized coord])
                span (- (double hi) (double lo))]
            (aset dist (first (first sorted)) Double/POSITIVE_INFINITY)
            (aset dist (first (peek sorted)) Double/POSITIVE_INFINITY)
            (when (pos? span)
              (doseq [k (range 1 (dec n))]
                (let [idx (first (nth sorted k))
                      prev-v (get-in (nth sorted (dec k)) [1 :normalized coord])
                      next-v (get-in (nth sorted (inc k)) [1 :normalized coord])
                      cur (aget dist idx)]
                  (when (Double/isFinite cur)
                    (aset dist idx (+ cur (/ (- (double next-v) (double prev-v)) span)))))))))
        (vec dist)))))

(defn- rank-and-crowd
  "Assign NSGA-II non-domination :rank and :crowding to a vector of evaluated points."
  [points]
  (loop [remaining (vec (map-indexed (fn [i p] (assoc p :idx i)) points))
         rank 0
         out []]
    (if (empty? remaining)
      (vec (sort-by :idx out))
      (let [front-points (filterv (fn [p]
                                    (not-any? #(dominates? (:objectives %) (:objectives p)) remaining))
                                  remaining)
            front-ids (set (map :idx front-points))
            crowds (crowding-distances front-points)
            annotated (mapv (fn [p c] (assoc p :rank rank :crowding c)) front-points crowds)]
        (recur (filterv #(not (contains? front-ids (:idx %))) remaining)
               (inc rank)
               (into out annotated))))))

(defn- select-nsga2-parent [ranked ^Random rng]
  (let [a (pick rng ranked)
        b (pick rng ranked)]
    (cond
      (< (:rank a) (:rank b)) a
      (> (:rank a) (:rank b)) b
      (> (:crowding a) (:crowding b)) a
      (< (:crowding a) (:crowding b)) b
      :else a)))

(defn- stop! [state status]
  (throw (ex-info "Baseline stopped" {::state (assoc state :status status)})))

(defn- make-ledger [adapter options]
  (let [initial {:archive [] :archive-solutions [] :current nil :current-point nil
                 :seen #{} :cache {} :representatives {} :archive-changed? false
                 :charged-costs {:proposal-attempts 0 :valid-candidates 0 :unique-candidates 0
                                 :cache-hits 0 :objective-calculations 0 :interpreter-steps 0}
                 :status :completed :diagnostics []}]
    {:initial initial
     :request
     (fn [state construct-fn]
       (when (>= (get-in state [:charged-costs :proposal-attempts]) (:solution-budget options))
         (stop! state :proposal-budget-exhausted))
       (let [state (-> state
                       (update-in [:charged-costs :proposal-attempts] inc)
                       (update-in [:charged-costs :interpreter-steps] inc))
             raw (try (construct-fn)
                      (catch Exception e
                        (stop! (update state :diagnostics conj
                                       {:kind :primitive-error :stage :propose
                                        :exception (.getName (class e)) :message (.getMessage e)})
                               :method-failure)))
             solution (try ((:canonicalize adapter) raw)
                           (catch Exception e
                             (stop! (update state :diagnostics conj
                                            {:kind :primitive-error :stage :canonicalize
                                             :exception (.getName (class e)) :message (.getMessage e)})
                                    :method-failure)))
             valid? (try ((:valid? adapter) solution)
                         (catch Exception e
                           (stop! (update state :diagnostics conj
                                          {:kind :primitive-error :stage :valid?
                                           :exception (.getName (class e)) :message (.getMessage e)})
                                  :method-failure)))]
         (when-not (true? valid?)
           (stop! (update state :diagnostics conj {:kind :invalid-candidate :stage :valid?})
                  :method-failure))
         (let [duplicate? (contains? (:seen state) solution)
               state (-> state
                         (update-in [:charged-costs :valid-candidates] inc)
                         (update :seen conj solution)
                         (cond-> (not duplicate?) (update-in [:charged-costs :unique-candidates] inc)))
               cache-hit? (and (:cache? options true) (contains? (:cache state) solution))
               [state point]
               (if cache-hit?
                 [(update-in state [:charged-costs :cache-hits] inc) (get-in state [:cache solution])]
                 (let [state (update-in state [:charged-costs :objective-calculations] inc)
                       point (try
                               (let [objectives ((:evaluate adapter) solution)
                                     normalized (pareto/normalize-point (:normalization adapter) objectives)]
                                 {:solution solution
                                  :objectives (first (pareto/front [objectives]))
                                  :normalized normalized})
                               (catch Exception e
                                 (stop! (update state :diagnostics conj
                                                {:kind :evaluation-error :stage :evaluate
                                                 :exception (.getName (class e)) :message (.getMessage e)})
                                        :method-failure)))]
                   [(cond-> state (:cache? options true) (assoc-in [:cache solution] point)) point]))
               front (pareto/insert (:archive state) (:objectives point))
               representatives (if (contains? (:representatives state) (:objectives point))
                                 (:representatives state)
                                 (assoc (:representatives state) (:objectives point) solution))
               representatives (select-keys representatives front)
               archive-sols (mapv representatives front)
               archive-pts (into {}
                                 (map (fn [obj sol]
                                        [sol {:solution sol
                                              :objectives obj
                                              :normalized (pareto/normalize-point (:normalization adapter) obj)}])
                                      front archive-sols))]
           [(assoc state
                   :archive front
                   :representatives representatives
                   :archive-solutions archive-sols
                   :archive-points archive-pts
                   :current solution
                   :current-point point
                   :archive-changed? (not= (:archive state) front))
            point])))}))

(defn run-conventional
  "Execute a validated conventional configuration on adapter under host-owned
  :solution-budget and :seed."
  [config adapter options]
  (let [checked (validate-conventional-config config)
        _ (ensure! (= (:domain checked) (:domain adapter))
                   :domain-mismatch "Config domain does not match adapter domain" {})
        budget (long (:solution-budget options 0))
        seed (long (:seed options 0))
        _ (ensure! (<= 0 budget 1000000) :invalid-budget "Invalid solution-budget" {:budget budget})
        started (System/nanoTime)
        rng (Random. seed)
        {:keys [initial request]} (make-ledger adapter (assoc options :solution-budget budget))
        perturb (fn [sol op steps]
                  ;; Perform bounded uncharged internal representation steps before single charged validation/evaluation,
                  ;; or single-step operator proposal when steps = 1.
                  (loop [k 0 cur sol]
                    (if (= k steps)
                      cur
                      (recur (inc k) ((:propose adapter) op cur rng)))))
        final-state
        (try
          (case (:family checked)
            :nsga2
            (let [pop-size (:population-size checked)
                  init-kind (:initializer checked)
                  op (:operator checked)
                  steps (:perturbation-steps checked)
                  [state pop]
                  (loop [i 0 st initial acc []]
                    (if (= i pop-size)
                      [st acc]
                      (let [[st2 pt] (request st #(if (and (zero? i) (= init-kind :first))
                                                    ((:initialize adapter) :first rng)
                                                    ((:initialize adapter) :random rng)))]
                        (recur (inc i) st2 (conj acc pt)))))]
              (loop [st state current-pop pop]
                (let [ranked (rank-and-crowd current-pop)
                      [st-next offspring]
                      (loop [i 0 s st children []]
                        (if (= i pop-size)
                          [s children]
                          (let [parent (select-nsga2-parent ranked rng)
                                [s2 child-pt] (request s #(perturb (:solution parent) op steps))]
                            (recur (inc i) s2 (conj children child-pt)))))
                      combined (rank-and-crowd (into current-pop offspring))
                      survivors (vec (take pop-size
                                           (sort-by (fn [p] [(:rank p) (- (double (:crowding p))) (:idx p)])
                                                    combined)))]
                  (recur st-next survivors))))

            :pareto-local-search
            (let [starts (:initial-starts checked)
                  init-kind (:initializer checked)
                  op (:operator checked)
                  nbrs (:neighbors-per-step checked)
                  rule (:selection-rule checked)
                  [state _]
                  (loop [i 0 st initial]
                    (if (= i starts)
                      [st nil]
                      (let [[st2 _] (request st #(if (and (zero? i) (= init-kind :first))
                                                   ((:initialize adapter) :first rng)
                                                   ((:initialize adapter) :random rng)))]
                        (recur (inc i) st2))))]
              (loop [st state visited #{}]
                (let [front-sols (:archive-solutions st)
                      unvisited (filterv #(not (contains? visited %)) front-sols)]
                  (if (empty? unvisited)
                    (let [[st2 _] (request st #((:initialize adapter) :random rng))]
                      (recur st2 #{}))
                    (let [chosen (case rule
                                   :unvisited-archive (first unvisited)
                                   :random-archive (pick rng unvisited)
                                   :crowding-archive
                                   (let [pts (mapv #(get-in st [:archive-points %]) unvisited)
                                         crowds (crowding-distances pts)
                                         best-idx (first (apply max-key second (map-indexed vector crowds)))]
                                     (nth unvisited best-idx)))
                          [st-after improved?]
                          (loop [k 0 s st imp? false]
                            (if (= k nbrs)
                              [s imp?]
                              (let [[s2 _] (request s #((:propose adapter) op chosen rng))]
                                (recur (inc k) s2 (or imp? (:archive-changed? s2))))))]
                      (recur st-after (if improved? visited (conj visited chosen))))))))

            :multi-start-restart
            (let [period (:restart-period checked)
                  init-kind (:initializer checked)
                  op (:operator checked)
                  w-sched (:weight-schedule checked)
                  weights (case w-sched
                            :balanced [0.5]
                            :alternating [0.2 0.8]
                            :uniform-grid [0.0 0.25 0.5 0.75 1.0])]
              (loop [step 0 st initial cur-pt nil]
                (if (or (nil? cur-pt) (zero? (mod step period)))
                  (let [[st2 pt] (request st #(if (and (zero? step) (= init-kind :first))
                                                ((:initialize adapter) :first rng)
                                                ((:initialize adapter) :random rng)))]
                    (recur (inc step) st2 pt))
                  (let [w (nth weights (mod (quot step period) (count weights)))
                        scalar (fn [pt]
                                 (let [[x y] (:normalized pt)]
                                   (+ (* w (double x)) (* (- 1.0 w) (double y)))))
                        [st2 cand-pt] (request st #((:propose adapter) op (:solution cur-pt) rng))
                        next-pt (if (<= (scalar cand-pt) (scalar cur-pt)) cand-pt cur-pt)]
                    (recur (inc step) st2 next-pt))))))
          (catch clojure.lang.ExceptionInfo e
            (if-let [st (::state (ex-data e))]
              st
              (throw e))))]
    (assoc (dissoc final-state :seen :cache :representatives :archive-points :current-point :archive-changed?)
           :seed seed
           :adapter-id (:id adapter)
           :config checked
           :config-id (config-id checked)
           :timing {:elapsed-ns (- (System/nanoTime) started)
                    :preparation (:preparation-timing adapter)})))

(defn run-direct-control
  "Execute a direct deployment control (:random-search, :initializer-only,
  :heuristic-local-search) under the same proposal ledger."
  [control-kind adapter options]
  (ensure! (contains? #{:random-search :initializer-only :heuristic-local-search} control-kind)
           :invalid-control "Unknown direct control kind" {:control control-kind})
  (let [budget (long (:solution-budget options 0))
        seed (long (:seed options 0))
        started (System/nanoTime)
        rng (Random. seed)
        {:keys [initial request]} (make-ledger adapter (assoc options :solution-budget budget))
        final-state
        (try
          (case control-kind
            :initializer-only
            (let [[st _] (request initial #((:initialize adapter) :first rng))]
              st)

            :random-search
            (loop [st initial]
              (let [[st2 _] (request st #((:initialize adapter) :random rng))]
                (recur st2)))

            :heuristic-local-search
            (let [[st start-pt] (request initial #((:initialize adapter) :first rng))]
              (loop [s st cur start-pt]
                (let [[s2 cand] (request s #((:propose adapter) :swap (:solution cur) rng))
                      cur-sum (reduce + 0.0 (:normalized cur))
                      cand-sum (reduce + 0.0 (:normalized cand))]
                  (recur s2 (if (<= cand-sum cur-sum) cand cur))))))
          (catch clojure.lang.ExceptionInfo e
            (if-let [st (::state (ex-data e))]
              st
              (throw e))))]
    (assoc (dissoc final-state :seen :cache :representatives :archive-points :current-point :archive-changed?)
           :seed seed
           :adapter-id (:id adapter)
           :control control-kind
           :timing {:elapsed-ns (- (System/nanoTime) started)
                    :preparation (:preparation-timing adapter)})))
