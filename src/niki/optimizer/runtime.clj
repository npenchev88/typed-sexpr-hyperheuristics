(ns niki.optimizer.runtime
  "Bounded interpreter for the checked, binder-free optimizer DSL."
  (:require [clojure.string :as str]
            [niki.optimizer.dsl :as dsl]
            [niki.optimizer.pareto :as pareto])
  (:import [java.lang.management ManagementFactory]
           [java.util Random]))

(defn- ensure! [condition code message]
  (when-not condition (throw (ex-info message {:error code}))))

(defn- immutable-integer? [x]
  (contains? #{Byte Short Integer Long clojure.lang.BigInt java.math.BigInteger} (class x)))

(defn- options! [options]
  (ensure! (and (map? options)
                (every? #{:solution-budget :step-budget :seed :cache? :trace?} (keys options))
                (every? #(contains? options %) [:solution-budget :step-budget :seed])
                (every? (fn [[k lo hi]]
                          (let [x (get options k)]
                            (and (immutable-integer? x) (<= lo x hi))))
                        [[:solution-budget 0 1000000] [:step-budget 0 10000000]
                         [:seed Long/MIN_VALUE Long/MAX_VALUE]])
                (every? #(or (not (contains? options %)) (boolean? (get options %)))
                        [:cache? :trace?]))
           :invalid-options "Invalid runtime options")
  (merge {:cache? true :trace? true} options))

(defn- adapter! [adapter checked]
  (let [normalization (:normalization adapter)
        normalization-ok?
        (and (instance? niki.optimizer.pareto.Normalization normalization)
             (try (= normalization (pareto/prepare-normalization (:bounds normalization)))
                  (catch Exception _ false)))
        operators (if (= :portfolio (:domain checked)) #{:swap :restart}
                      #{:swap :restart :insert :reverse})]
    (ensure! (and (map? adapter) (= (:domain checked) (:domain adapter))
                  (or (keyword? (:id adapter))
                      (and (string? (:id adapter)) (not (str/blank? (:id adapter)))))
                  normalization-ok?
                  (set? (:initializers adapter)) (set? (:operators adapter))
                  (every? #{:random :first} (:initializers adapter))
                  (every? operators (:operators adapter))
                  (every? #(fn? (get adapter %))
                          [:initialize :propose :canonicalize :valid? :evaluate]))
             :invalid-adapter "Invalid adapter or unprepared normalization")
    (doseq [node (tree-seq vector? #(filterv vector? %) (:program checked))
            :let [[tag kind] node]
            :when (#{:initializer :operator} tag)]
      (ensure! (contains? (get adapter (if (= :initializer tag) :initializers :operators)) kind)
               :invalid-adapter "Adapter lacks a selected capability")))
  adapter)

(defn- stop! [state status]
  (throw (ex-info "Interpreter stopped" {::state (assoc state :status status)})))

(defn- charge-step [state options]
  (when (>= (get-in state [:charged-costs :interpreter-steps]) (:step-budget options))
    (stop! state :step-budget-exhausted))
  (update-in state [:charged-costs :interpreter-steps] inc))

(def trace-cap
  "At most 2048 trace events per run; excess events increment :trace-dropped.
  Disabled tracing records no events and is not reported as truncation."
  2048)

(defn- trace-event [state options event]
  (cond
    (not (:trace? options)) state
    (< (count (:trace state)) trace-cap)
    (update state :trace conj (assoc event :step (get-in state [:charged-costs :interpreter-steps])))
    :else (-> state (assoc :trace-truncated? true) (update :trace-dropped inc))))

(defn- cpu-now []
  (try (let [bean (ManagementFactory/getThreadMXBean)]
         (when (and (.isCurrentThreadCpuTimeSupported bean) (.isThreadCpuTimeEnabled bean))
           (let [value (.getCurrentThreadCpuTime bean)] (when (<= 0 value) value))))
       (catch Exception _ nil)))

(defn run
  "Run a checked program with a trusted bounded adapter and host-owned budgets.
  State is persistent; only the run-local Java Random advances. Exhaustion is
  normal, while invalid candidates/host Exceptions are :method-failure and keep
  the charged partial archive. No primary performance score is returned.
  Timing is measured after preflight; CPU time is nil when unavailable.
  This is not host-code isolation or a wall-clock timeout."
  [program adapter options]
  (let [options (options! options)
        checked (dsl/check-program program)
        adapter (adapter! adapter checked)
        program (:program checked)
        started (System/nanoTime)
        cpu-started (cpu-now)
        rng (Random. (long (:seed options)))
        initial {:archive [] :archive-solutions [] :current nil :current-point nil
                 :seen #{} :cache {} :representatives {} :archive-changed? false
                 :charged-costs {:proposal-attempts 0 :valid-candidates 0 :unique-candidates 0
                                 :cache-hits 0 :objective-calculations 0 :interpreter-steps 0}
                 :status :completed :trace [] :trace-truncated? false :trace-dropped 0 :diagnostics []}]
    (letfn [(visit [state node]
              (trace-event (charge-step state options) options {:event :visit :form node}))
            (host-call [state stage f]
              (try (f)
                   (catch Exception e
                     (stop! (update state :diagnostics conj
                                    {:kind (if (= stage :evaluate) :evaluation-error :primitive-error)
                                     :stage stage :exception (.getName (class e))
                                     :message (.getMessage e)})
                            :method-failure))))
            (request [state source kind]
              (when (>= (get-in state [:charged-costs :proposal-attempts]) (:solution-budget options))
                (stop! state :proposal-budget-exhausted))
              (let [state (update-in state [:charged-costs :proposal-attempts] inc)
                    state (trace-event state options
                                       {:event :proposal :attempt (get-in state [:charged-costs :proposal-attempts])
                                        :source source :operator kind :parent (:current state)})
                    raw (host-call state source
                                   #(if (= source :initialize)
                                      ((:initialize adapter) kind rng)
                                      ((:propose adapter) kind (:current state) rng)))
                    solution (host-call state :canonicalize #((:canonicalize adapter) raw))
                    valid? (host-call state :valid? #((:valid? adapter) solution))]
                (when-not (true? valid?)
                  (stop! (update state :diagnostics conj {:kind :invalid-candidate :stage :valid?})
                         :method-failure))
                (let [duplicate? (contains? (:seen state) solution)
                      state (-> state
                                (update-in [:charged-costs :valid-candidates] inc)
                                (update :seen conj solution)
                                (cond-> (not duplicate?) (update-in [:charged-costs :unique-candidates] inc)))
                      cache-hit? (and (:cache? options) (contains? (:cache state) solution))
                      [state point]
                      (if cache-hit?
                        [(update-in state [:charged-costs :cache-hits] inc) (get-in state [:cache solution])]
                        (let [state (update-in state [:charged-costs :objective-calculations] inc)
                              point (host-call state :evaluate
                                               #(let [objectives ((:evaluate adapter) solution)
                                                      normalized (pareto/normalize-point (:normalization adapter) objectives)]
                                                  {:solution solution :objectives (first (pareto/front [objectives]))
                                                   :normalized normalized}))]
                          [(cond-> state (:cache? options) (assoc-in [:cache solution] point)) point]))
                      front (pareto/insert (:archive state) (:objectives point))
                      representatives (if (contains? (:representatives state) (:objectives point))
                                        (:representatives state)
                                        (assoc (:representatives state) (:objectives point) solution))
                      representatives (select-keys representatives front)]
                  [(trace-event (assoc state :archive front :representatives representatives
                                       :archive-solutions (mapv representatives front)
                                       :archive-changed? (not= (:archive state) front)) options
                                {:event :candidate :attempt (get-in state [:charged-costs :proposal-attempts])
                                 :solution solution :objectives (:objectives point)
                                 :duplicate? duplicate? :cache-hit? (boolean cache-hit?)
                                 :archive-changed? (not= (:archive state) front)}) point])))
            (adopt [state point]
              (assoc state :current (:solution point) :current-point point))
            (score [state node point]
              (let [state (visit state node)
                    [tag x y] node
                    clamp (fn [v] (max -1000000.0 (min 1000000.0 (double v))))]
                (case tag
                  :constant [state x]
                  :feature [state (clamp
                                    (case x
                                      :objective-0 (get-in point [:normalized 0] 0.0)
                                      :objective-1 (get-in point [:normalized 1] 0.0)
                                      :progress (if (zero? (:solution-budget options)) 0.0
                                                    (/ (double (get-in state [:charged-costs :proposal-attempts]))
                                                       (:solution-budget options)))))]
                  (:add :multiply) (let [[state a] (score state x point)
                                         [state b] (score state y point)]
                                     [state (clamp ((if (= tag :add) + *) a b))]))))
            (predicate [state node]
              (let [state (visit state node)
                    [tag x y] node]
                (case tag
                  :predicate [state (case x
                                      :archive-changed (:archive-changed? state)
                                      :budget-left (< (get-in state [:charged-costs :proposal-attempts])
                                                      (:solution-budget options))
                                      :coin (.nextBoolean rng))]
                  :not (let [[state value] (predicate state x)] [state (not value)])
                  :and (let [[state value] (predicate state x)]
                         (if value (predicate state y) [state false]))
                  :less (let [[state a] (score state x (:current-point state))
                              [state b] (score state y (:current-point state))]
                          [state (< a b)]))))
            (execute [state node]
              (let [state (visit state node)]
                (case (first node)
                  :program (execute state (nth node 2))
                  :budget (execute state (second node))
                  :noop state
                  :if (let [[state value] (predicate state (second node))
                            state (trace-event state options {:event :branch :taken (if value :then :else)})]
                        (execute state (nth node (if value 2 3))))
                  :initialize (let [state (visit state (second node))
                                    [state point] (request state :initialize (second (second node)))]
                                (adopt state point))
                  :step (let [state (visit state (second node))
                              [state point] (request state :propose (second (second node)))]
                          (adopt state point))
                  :search (let [state (visit state (second node))]
                            (loop [state state i 0]
                              (if (= i (second (second node))) state
                                  (let [state (visit (charge-step state options) (nth node 2))
                                        [state candidate] (request state :propose (second (nth node 2)))
                                        state (if (nil? (:current-point state))
                                                (trace-event (adopt state candidate) options
                                                             {:event :search-choice :accepted? true :reason :no-current
                                                              :current-score nil :candidate-score nil})
                                                (let [[state current-score] (score state (nth node 3) (:current-point state))
                                                      [state candidate-score] (score state (nth node 3) candidate)
                                                      accepted? (<= candidate-score current-score)]
                                                  (trace-event (if accepted? (adopt state candidate) state) options
                                                               {:event :search-choice :accepted? accepted? :reason :score
                                                                :current-score current-score :candidate-score candidate-score})))]
                                    (recur state (inc i))))))
                  :seq (execute (execute state (second node)) (nth node 2))
                  :repeat (let [state (visit state (second node))]
                            (loop [state state i 0]
                              (if (= i (second (second node))) state
                                  (recur (execute (charge-step state options) (nth node 2))
                                         (inc i))))))))]
      (let [state (try (execute initial program)
                       (catch clojure.lang.ExceptionInfo e
                         (if-let [state (::state (ex-data e))] state (throw e))))
            cpu-ended (cpu-now)]
        (assoc (dissoc state :seen :cache :representatives :current-point :archive-changed?)
               :seed (:seed options) :adapter-id (:id adapter)
               :program-id (dsl/program-id program)
               :timing {:elapsed-ns (- (System/nanoTime) started)
                        :thread-cpu-ns (when (and cpu-started cpu-ended (<= cpu-started cpu-ended))
                                         (- cpu-ended cpu-started))
                        :preparation (:preparation-timing adapter)})))))
