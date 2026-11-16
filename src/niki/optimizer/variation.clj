(ns niki.optimizer.variation
  "Bounded, seeded development-only typed variation; not a frozen search prior."
  (:require [niki.optimizer.dsl :as dsl]
            [clojure.set :as set])
  (:import [java.util Random]))

(def ^:private integer-classes
  #{Byte Short Integer Long clojure.lang.BigInt java.math.BigInteger})

(defn- ensure! [ok message data]
  (when-not ok (throw (ex-info message data))))

(defn- options! [options]
  ;; Shape checks precede destructuring, sequence traversal and numeric coercion.
  (ensure! (map? options) "Variation options must be a map" {})
  (ensure! (every? #(and (keyword? %) (#{:seed :limits :max-attempts} %)) (keys options))
           "Unknown variation option" {})
  (let [seed (get options :seed 0)
        attempts (get options :max-attempts 10)
        limits (dsl/effective-limits (get options :limits {}))]
    (ensure! (and (integer-classes (class seed))
                  (<= Long/MIN_VALUE seed Long/MAX_VALUE)) "Invalid signed-long seed" {})
    (ensure! (and (integer-classes (class attempts)) (<= 1 attempts 10))
             "max-attempts must be an integer in [1,10]" {})
    (doseq [[k v] limits]
      (ensure! (<= v (get dsl/default-limits k))
               "Variation limits cannot exceed the runtime/ID default envelope" {:limit k}))
    {:seed (long seed) :limits limits :max-attempts (long attempts)}))

(defn- pick [^Random rng xs] (nth xs (.nextInt rng (count xs))))
(defn- between [^Random rng lo hi] (+ lo (.nextInt rng (inc (- hi lo)))))

;; Descriptor order is stable, never derived from hash-map iteration.
(def ^:private productions
  {:operation [[:noop []] [:seq [:operation :operation]]
               [:repeat [:count :operation]] [:if [:predicate :operation :operation]]
               [:initialize [:initializer]] [:step [:operator]]
               [:search [:count :operator :score]]]
   :predicate [[:predicate []] [:not [:predicate]] [:and [:predicate :predicate]]
               [:less [:score :score]]]
   :score [[:constant []] [:feature []] [:add [:score :score]] [:multiply [:score :score]]]
   :count [[:count []]] :initializer [[:initializer []]] :operator [[:operator []]]})

(defn- leaf [rng domain op limits]
  (case op
    :noop [:noop]
    :count [:count (between rng 0 (:max-count limits))]
    :initializer [:initializer (pick rng [:random :first])]
    :operator [:operator (pick rng (if (= :portfolio domain)
                                     [:swap :restart] [:swap :restart :insert :reverse]))]
    :predicate [:predicate (pick rng [:archive-changed :budget-left :coin])]
    :constant [:constant (double (between rng -10 10))]
    :feature [:feature (pick rng [:objective-0 :objective-1 :progress])]))

(defn- grow [rng domain role nodes depth limits]
  (let [feasible (filterv (fn [[_ children]]
                            (and (<= (inc (count children)) nodes)
                                 (or (empty? children) (> depth 1))))
                          (get productions role))
        [op roles] (pick rng feasible)]
    (if (empty? roles)
      (leaf rng domain op limits)
      ;; Allocate caps, reserving one node for every remaining sibling. Unused
      ;; capacity is intentionally not recycled; realized sizes are not uniform.
      (loop [remaining roles available (dec nodes) children []]
        (if (empty? remaining)
          (into [op] children)
          (let [cap (between rng 1 (- available (dec (count remaining))))]
            (recur (subvec remaining 1) (- available cap)
                   (conj children (grow rng domain (first remaining) cap (dec depth) limits)))))))))

(defn- random-subtree [rng domain role node-cap depth-cap limits]
  (let [nodes (between rng 1 (min 30 node-cap))
        depth (between rng 1 (min 6 depth-cap))]
    (grow rng domain role nodes depth limits)))

(defn generate-program
  "Return a checked AST and construction metadata. Options default to seed 0,
   DSL default limits and max-attempts 10. Generation needs exactly one attempt."
  [domain options]
  (let [{:keys [seed limits]} (options! options)]
    (ensure! (and (keyword? domain) (#{:portfolio :scheduling} domain))
             "Unknown generation domain" {})
    (let [rng (Random. seed)
          body (random-subtree rng domain :operation (- (:max-nodes limits) 2)
                               (- (:max-depth limits) 2) limits)
          checked (dsl/check-program [:program domain [:budget body]] limits)
          p (:program checked)]
      {:program p :child-id (dsl/program-id p) :parent-ids [] :paths []
       :changed? true :attempts 1 :fallback? false :rejections []
       :seed seed :limits limits :distribution :development-only-v1})))

(defn- vector-count [node]
  (count (tree-seq vector? #(filterv vector? %) node)))

(defn- rejected-edit [program path replacement limits child]
  (try
    {:program (dsl/replace-subtree program path replacement limits)}
    (catch clojure.lang.ExceptionInfo e
      {:rejection {:reason :structural-or-type-rejection :child child
                   :message (.getMessage e) :details (ex-data e)}})))

(defn mutate
  "One seeded typed replacement, with at most ten construction attempts.
   Exhaustion returns the original canonical parent, never an invalid child."
  [program options]
  (let [{:keys [seed limits max-attempts]} (options! options)
        checked (dsl/check-program program limits)
        parent (:program checked)
        parent-id (dsl/program-id parent)
        rng (Random. seed)
        base {:parent-ids [parent-id] :seed seed :limits limits}]
    (loop [attempt 1 rejections [] last-path nil]
      (if (> attempt max-attempts)
        (merge base {:program parent :child-id parent-id :attempts max-attempts
                     :fallback? true :fallback-reason :unchanged-parent :changed? false
                     :path last-path :paths (if last-path [last-path] []) :rejections rejections})
        (let [{:keys [path type]} (pick rng (:sites checked))
              old (get-in parent path)
              node-cap (- (:max-nodes limits) (- (:node-count checked) (vector-count old)))
              depth-cap (- (:max-depth limits) (count path))
              replacement (random-subtree rng (:domain checked) (first type) node-cap depth-cap limits)
              edit (rejected-edit parent path replacement limits 0)
              reasons (cond
                        (:rejection edit) [(:rejection edit)]
                        (= old replacement) [{:reason :identical-subtree}]
                        :else [])]
          (if (seq reasons)
            (recur (inc attempt)
                   (into rejections (map #(assoc % :attempt attempt :path path) reasons)) path)
            (let [child (:program edit)]
              (merge base {:program child :child-id (dsl/program-id child) :attempts attempt
                           :fallback? false :changed? (not= parent child)
                           :path path :paths [path] :rejections rejections}))))))))

(defn- compatible? [a b]
  (and (= (:type a) (:type b))
       (set/subset? (:effects a) (:allowed-effects b))
       (set/subset? (:effects b) (:allowed-effects a))))

(defn crossover
  "Swap actual subtrees from two checked parents. BOTH changed full children
   must pass before either is returned. No mutation/generation substitutes."
  [left right options]
  (let [{:keys [seed limits max-attempts]} (options! options)
        a (dsl/check-program left limits)
        b (dsl/check-program right limits)
        parents [(:program a) (:program b)]
        ids (mapv dsl/program-id parents)
        base {:parent-ids ids :seed seed :limits limits}
        fallback (fn [attempts rejections paths]
                   (merge base {:children parents :child-ids ids :attempts attempts
                                :fallback? true :fallback-reason :unchanged-parents
                                :changed? [false false] :paths paths :rejections rejections}))
        preflight (cond
                    (= (first ids) (second ids)) :same-parent
                    (not= (:domain a) (:domain b)) :incompatible-domain)]
    (if preflight
      (fallback 0 [{:attempt 0 :reason preflight :paths []}] [])
      (let [rng (Random. seed)
            ;; At most 126^2 site pairs under the runtime default envelope.
            pairs (vec (for [sa (:sites a) sb (:sites b) :when (compatible? sa sb)] [sa sb]))]
        (loop [attempt 1 available pairs rejections [] last-paths []]
          (if (or (> attempt max-attempts) (empty? available))
            (fallback (dec attempt)
                      (cond-> rejections
                        (empty? pairs) (conj {:attempt 0 :reason :no-compatible-sites :paths []}))
                      last-paths)
            (let [index (.nextInt rng (count available))
                  [sa sb] (nth available index)
                  paths [(:path sa) (:path sb)]
                  [pa pb] paths
                  x (get-in (first parents) pa)
                  y (get-in (second parents) pb)
                  ;; Deliberately evaluate both replacements even if one fails.
                  ea (rejected-edit (first parents) pa y limits 0)
                  eb (rejected-edit (second parents) pb x limits 1)
                  reasons (cond-> []
                            (= x y) (conj {:reason :identical-subtree})
                            (:rejection ea) (conj (:rejection ea))
                            (:rejection eb) (conj (:rejection eb)))
                  children [(:program ea) (:program eb)]]
              (if (seq reasons)
                (recur (inc attempt)
                       (into (subvec available 0 index) (subvec available (inc index)))
                       (into rejections (map #(assoc % :attempt attempt :paths paths) reasons)) paths)
                (merge base {:children children :child-ids (mapv dsl/program-id children)
                             :attempts attempt :fallback? false :changed? [true true]
                             :paths paths :rejections rejections})))))))))
