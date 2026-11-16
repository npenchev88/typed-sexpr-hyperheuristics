(ns niki.optimizer.variation-test
  (:require [clojure.test :refer [deftest is testing]]
            [niki.optimizer.dsl :as dsl]))

(defn api [name & args]
  (try (require 'niki.optimizer.variation)
       (catch java.io.FileNotFoundException _ nil))
  (when-let [n (find-ns 'niki.optimizer.variation)]
    (when-let [f (ns-resolve n name)] (apply f args))))

(deftest seeded-generation-is-checked-and-replayable
  (doseq [domain [:portfolio :scheduling] seed (range 100)]
    (let [r (api 'generate-program domain {:seed seed})
          p (:program r)]
      (is (some? r) "generation API must return metadata")
      (when r
        (is (= r (api 'generate-program domain {:seed seed})))
        (is (= p (:program (dsl/check-program p))))
        (is (= domain (second p)))
        (is (= (dsl/program-id p) (:child-id r)))
        (is (= 1 (:attempts r)))
        (is (false? (:fallback? r)))))))

(def minimal [:program :portfolio [:budget [:noop]]])
(def rich [:program :portfolio
           [:budget [:seq [:initialize [:initializer :first]]
                     [:search [:count 3] [:operator :swap]
                      [:add [:feature :objective-0] [:constant 1.0]]]]]])

(deftest mutation-makes-real-local-edits-or-explicit-fallback
  (doseq [seed (range 200)]
    (let [r (api 'mutate rich {:seed seed})]
      (is (some? r) "mutation API must return metadata")
      (when r
        (let [p (:program r) path (:path r)]
          (is (= r (api 'mutate rich {:seed seed})))
          (is (= p (:program (dsl/check-program p))))
          (is (= [(dsl/program-id rich)] (:parent-ids r)))
          (is (= (dsl/program-id p) (:child-id r)))
          (is (<= 1 (:attempts r) 10))
          (is (false? (:fallback? r)))
          (is (true? (:changed? r)))
          (is (not= rich p))
          (is (= [2 1] (subvec path 0 2)))
          (is (= p (assoc-in rich path (get-in p path))))))))
  (let [r (api 'mutate minimal {:seed 2 :limits {:max-nodes 3 :max-depth 3}})]
    (is (some? r))
    (when r
      (is (= minimal (:program r)))
      (is (:fallback? r))
      (is (false? (:changed? r)))
      (is (= :unchanged-parent (:fallback-reason r)))
      (is (= 10 (:attempts r)))
      (is (= (range 1 11) (map :attempt (:rejections r))))
      (is (every? #(= :identical-subtree (:reason %)) (:rejections r))))))

(def other [:program :portfolio
            [:budget [:if [:less [:constant -2.0] [:feature :progress]]
                      [:step [:operator :restart]]
                      [:repeat [:count 2] [:noop]]]]])

(deftest crossover-is-a-two-parent-swap-not-a-generator
  (doseq [seed (range 200)]
    (let [r (api 'crossover rich other {:seed seed})]
      (is (some? r) "crossover API must return metadata")
      (when r
        (let [[a b] (:children r) [pa pb] (:paths r)]
          (is (= r (api 'crossover rich other {:seed seed})))
          (is (false? (:fallback? r)))
          (is (= [true true] (:changed? r)))
          (is (<= 1 (:attempts r) 10))
          (is (= [(dsl/program-id rich) (dsl/program-id other)] (:parent-ids r)))
          (is (= (mapv dsl/program-id [a b]) (:child-ids r)))
          (is (= [a b] (mapv #(:program (dsl/check-program %)) [a b])))
          (is (not= rich a))
          (is (not= other b))
          (is (= a (assoc-in rich pa (get-in other pb))))
          (is (= b (assoc-in other pb (get-in rich pa))))
          (is (= [2 1] (subvec pa 0 2)))
          (is (= [2 1] (subvec pb 0 2)))
          (is (= (get-in a pa) (get-in other pb)))
          (is (= (get-in b pb) (get-in rich pa))))))))

(deftest crossover-preflight-failures-are-explicit-parent-fallbacks
  (doseq [[left right reason] [[rich rich :same-parent]
                              [rich (assoc other 1 :scheduling) :incompatible-domain]]]
    (let [r (api 'crossover left right {:seed 17})]
      (is (some? r))
      (when r
        (is (= [left right] (:children r)))
        (is (:fallback? r))
        (is (= [false false] (:changed? r)))
        (is (= 0 (:attempts r)))
        (is (= :unchanged-parents (:fallback-reason r)))
        (is (= [reason] (mapv :reason (:rejections r))))))))

(deftest rejection-ledgers-cover-both-children-and-tight-structure
  (let [right (assoc-in other [2 1 2 1 1] :swap)
        options {:limits {:max-nodes 13 :max-depth 6 :max-count 3}
                 :max-attempts 1}
        results (mapv #(api 'crossover rich right (assoc options :seed %)) (range 300))
        rejects (mapcat :rejections results)
        structural (filter #(= :structural-or-type-rejection (:reason %)) rejects)]
    (is (seq structural))
    (is (= #{0 1} (set (map :child structural))))
    (is (some #(= :identical-subtree (:reason %)) rejects))
    (doseq [r results]
      (is (= 1 (:attempts r)))
      (is (<= (count (:rejections r)) 2))
      (is (= (:children r)
             (mapv #(:program (dsl/check-program % (:limits options))) (:children r))))
      (when (:fallback? r)
        (is (= [rich right] (:children r)))
        (is (= [false false] (:changed? r)))
        (is (seq (:rejections r)))))))

(deftest options-and-parent-inputs-are-rejected-without-lazy-realization
  (let [touched (atom false)
        poison (lazy-seq (reset! touched true) (throw (Exception. "realized")))
        invoke-all (fn [o] [(fn [] (api 'generate-program :portfolio o))
                            (fn [] (api 'mutate minimal o))
                            (fn [] (api 'crossover minimal rich o))])]
    (doseq [o [nil [] '(1) poison {:unknown 1} {:seed poison}
               {:seed ##NaN} {:seed ##Inf} {:seed 1.5} {:seed 1/2}
               {:seed 9223372036854775808N} {:seed (java.util.concurrent.atomic.AtomicLong. 1)}
               {:max-attempts 0} {:max-attempts 11} {:max-attempts ##Inf}
               {:max-attempts 1.0} {:max-attempts poison}
               {:limits poison} {:limits nil} {:limits {:unknown 1}}
               {:limits {:max-count ##NaN}} {:limits {:max-count -1}}
               {:limits {:max-nodes 129}} {:limits {:max-depth 13}}
               {:limits {:max-count 257}} {:limits {:max-source-chars 65537}}]]
      (doseq [f (invoke-all o)] (is (thrown? clojure.lang.ExceptionInfo (f)))))
    (is (false? @touched))
    (doseq [f [(fn [] (api 'mutate poison {}))
               (fn [] (api 'crossover poison rich {}))
               (fn [] (api 'crossover rich poison {}))
               (fn [] (api 'generate-program poison {}))]]
      (is (thrown? clojure.lang.ExceptionInfo (f))))
    (is (false? @touched)))
  (doseq [v [##NaN ##Inf -1 1.2 (java.util.concurrent.atomic.AtomicLong. 1)]
          :let [p [:program :portfolio [:budget [:repeat [:count v] [:noop]]]]]]
    (is (thrown? clojure.lang.ExceptionInfo (api 'mutate p {})))
    (is (thrown? clojure.lang.ExceptionInfo (api 'crossover rich p {})))))

;; Independent grammar walk: no production grammar, sites or size helper reused.
(defn independent-node! [node expected domain limits depth]
  (is (vector? node))
  (let [op (first node)
        [role child-roles payload?]
        (case op
          :noop [:operation [] false]
          :seq [:operation [:operation :operation] false]
          :repeat [:operation [:count :operation] false]
          :if [:operation [:predicate :operation :operation] false]
          :initialize [:operation [:initializer] false]
          :step [:operation [:operator] false]
          :search [:operation [:count :operator :score] false]
          :not [:predicate [:predicate] false]
          :and [:predicate [:predicate :predicate] false]
          :less [:predicate [:score :score] false]
          :add [:score [:score :score] false]
          :multiply [:score [:score :score] false]
          :constant [:score [] true]
          :feature [:score [] true]
          :predicate [:predicate [] true]
          :count [:count [] true]
          :initializer [:initializer [] true]
          :operator [:operator [] true]
          [nil [] false])
        value (second node)
        children (mapv (fn [i r] (independent-node! (nth node i) r domain limits (inc depth)))
                       (range 1 (inc (count child-roles))) child-roles)
        intrinsic (case op
                    :operator #{:random}
                    :initializer (if (= :random value) #{:random} #{})
                    :predicate (if (= :coin value) #{:random} #{:read})
                    :feature #{:read}
                    (:initialize :step) #{:propose}
                    :search #{:read :propose}
                    #{})
        effects (reduce into intrinsic (map :effects children))
        allowance (case role
                    :operation #{:read :random :propose}
                    :predicate #{:read :random}
                    :score #{:read}
                    (:operator :initializer) #{:random}
                    :count #{})]
    (is (= expected role))
    (is (= (+ 1 (if payload? 1 (count child-roles))) (count node)))
    (is (<= depth (:max-depth limits)))
    (is (every? allowance effects))
    (case op
      :count (is (and (integer? value) (<= 0 value (:max-count limits))))
      :constant (is (and (number? value) (Double/isFinite (double value))
                        (<= -1000000 value 1000000)))
      :initializer (is (contains? #{:first :random} value))
      :operator (is (contains? (if (= domain :portfolio) #{:swap :restart}
                                  #{:swap :restart :insert :reverse}) value))
      :feature (is (contains? #{:objective-0 :objective-1 :progress} value))
      :predicate (is (contains? #{:archive-changed :budget-left :coin} value))
      nil)
    {:nodes (inc (reduce + 0 (map :nodes children)))
     :depth (reduce max depth (map :depth children)) :effects effects}))

(defn independent-program! [p limits]
  (is (and (vector? p) (= 3 (count p)) (= :program (first p))))
  (is (contains? #{:portfolio :scheduling} (second p)))
  (is (and (vector? (nth p 2)) (= 2 (count (nth p 2))) (= :budget (get-in p [2 0]))))
  (let [r (independent-node! (get-in p [2 1]) :operation (second p) limits 3)
        checked (dsl/check-program p limits)]
    (is (<= (+ 2 (:nodes r)) (:max-nodes limits)))
    (is (= (+ 2 (:nodes r)) (:node-count checked)))
    (is (= (:depth r) (:depth checked)))
    (is (= (:effects r) (:effects (first (:sites checked)))))
    (is (= p (:program checked)))))

(def envelopes [{:max-nodes 3 :max-depth 3 :max-count 1 :max-source-chars 65536}
                {:max-nodes 4 :max-depth 4 :max-count 1 :max-source-chars 65536}
                {:max-nodes 12 :max-depth 6 :max-count 3 :max-source-chars 65536}
                {:max-nodes 128 :max-depth 12 :max-count 256 :max-source-chars 65536}])

(deftest broad-seeded-preservation-has-independent-structure-and-effects-oracles
  (let [records
        (vec
         (for [domain [:portfolio :scheduling] seed (range 300)
               :let [limits (nth envelopes (mod seed (count envelopes)))
                     options {:seed seed :limits limits}
                     g (api 'generate-program domain options)
                     q (:program (api 'generate-program domain (assoc options :seed (+ 10000 seed))))
                     p (:program g)
                     m (api 'mutate p options)
                     c (api 'crossover p q options)]]
           (do
             (doseq [child (into [p q (:program m)] (:children c))]
               (independent-program! child limits)
               (is (= domain (second child))))
             (is (= g (api 'generate-program domain options)))
             (is (= m (api 'mutate p options)))
             (is (= c (api 'crossover p q options)))
             (is (<= 1 (:attempts m) 10))
             (is (<= 0 (:attempts c) 10))
             (is (= (not= p (:program m)) (:changed? m)))
             (is (= (mapv not= [p q] (:children c)) (:changed? c)))
             (if (:fallback? c)
               (do (is (= [p q] (:children c))) (is (seq (:rejections c))))
               (let [[a b] (:children c) [pa pb] (:paths c)
                     sa (some #(when (= pa (:path %)) %) (:sites (dsl/check-program p)))
                     sb (some #(when (= pb (:path %)) %) (:sites (dsl/check-program q)))]
                 (is (= (:type sa) (:type sb)))
                 (is (every? (:allowed-effects sa) (:effects sb)))
                 (is (every? (:allowed-effects sb) (:effects sa)))
                 (is (= a (assoc-in p pa (get-in q pb))))
                 (is (= b (assoc-in q pb (get-in p pa))))
                 (is (= [true true] (:changed? c)))))
             {:generation g :mutation m :crossover c})))
        programs (mapcat (fn [{:keys [generation mutation crossover]}]
                           (into [(:program generation) (:program mutation)] (:children crossover))) records)
        tags (set (map first (mapcat #(tree-seq vector? (fn [n] (filterv vector? n)) %) programs)))]
    (is (> (count (filter #(get-in % [:mutation :changed?]) records)) 100))
    (is (> (count (filter #(not (get-in % [:crossover :fallback?])) records)) 50))
    (doseq [tag [:noop :seq :repeat :if :initialize :step :search :count :initializer
                 :operator :predicate :not :and :less :constant :feature :add :multiply]]
      (is (contains? tags tag) (str "real generated form coverage: " tag)))))

(deftest defaults-seed-extremes-and-minimal-envelope-terminate
  (is (= (api 'generate-program :portfolio {}) (api 'generate-program :portfolio {:seed 0})))
  (doseq [seed [Long/MIN_VALUE Long/MAX_VALUE -1 0 1]]
    (let [options {:seed seed :max-attempts 1 :limits {:max-nodes 3 :max-depth 3 :max-count 1}}
          g (api 'generate-program :portfolio options)
          m (api 'mutate minimal options)
          c (api 'crossover minimal minimal options)]
      (is (= minimal (:program g)))
      (is (= minimal (:program m)))
      (is (= 1 (:attempts m)))
      (is (:fallback? m))
      (is (= 1 (count (:rejections m))))
      (is (= [minimal minimal] (:children c)))
      (is (= 0 (:attempts c)))
      (is (= g (api 'generate-program :portfolio options)))
      (is (= m (api 'mutate minimal options)))
      (is (= c (api 'crossover minimal minimal options))))))
