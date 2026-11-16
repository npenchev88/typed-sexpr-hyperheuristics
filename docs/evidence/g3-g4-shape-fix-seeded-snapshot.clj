;; Standalone valid-input equivalence corpus, no dsl-test or aggregate runner.
(require '[niki.optimizer.dsl :as dsl]
         '[niki.optimizer.variation :as variation])
(def envelopes [{} {:max-nodes 3 :max-depth 3 :max-count 1}
                {:max-nodes 12 :max-depth 6 :max-count 3}])
(doseq [domain [:portfolio :scheduling] seed (range 100)]
  (let [options {:seed seed :limits (nth envelopes (mod seed (count envelopes)))}
        g (variation/generate-program domain options)
        q (variation/generate-program domain (assoc options :seed (+ 10000 seed)))
        p (:program g)]
    (prn [domain seed g q (dsl/check-program p) (dsl/canonical-edn p)
          (dsl/program-id p) (variation/mutate p options)
          (variation/crossover p (:program q) options)])))
(doseq [domain [:portfolio :scheduling]
        body [[:noop] [:seq [:noop] [:noop]] [:repeat [:count 2] [:noop]]
              [:if [:and [:not [:predicate :coin]] [:predicate :archive-changed]]
               [:initialize [:initializer :first]] [:step [:operator :swap]]]
              [:if [:predicate :budget-left] [:initialize [:initializer :random]] [:noop]]
              [:search [:count 3] [:operator :restart]
               [:add [:multiply [:constant 1] [:feature :objective-0]]
                [:add [:feature :objective-1] [:feature :progress]]]]
              [:if [:less [:constant -1] [:constant 0]] [:noop] [:noop]]]
        :let [p [:program domain [:budget body]]]]
  (prn [(dsl/check-program p) (dsl/canonical-edn p) (dsl/program-id p)]))
(doseq [op [:insert :reverse]
        :let [p [:program :scheduling [:budget [:step [:operator op]]]]]]
  (prn [(dsl/check-program p) (dsl/canonical-edn p) (dsl/program-id p)]))
(shutdown-agents)
