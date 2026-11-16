;; Bounded development audit only; no optimizer, objective calls or market data.
(require '[niki.optimizer.dsl :as dsl]
         '[niki.optimizer.variation :as v])
(load-file "test/niki/optimizer/variation_test.clj")
(let [records
      (vec (for [domain [:portfolio :scheduling] seed (range 300)
                 :let [limits (nth niki.optimizer.variation-test/envelopes (mod seed 4))
                       options {:seed seed :limits limits}
                       p (:program (v/generate-program domain options))
                       q (:program (v/generate-program domain (assoc options :seed (+ seed 10000))))]]
             {:program p :mutation (v/mutate p options) :crossover (v/crossover p q options)}))
      summary {:cases (count records)
               :generation-constructions (* 2 (count records))
               :mutation-changed (count (filter #(get-in % [:mutation :changed?]) records))
               :mutation-fallbacks (count (filter #(get-in % [:mutation :fallback?]) records))
               :crossover-both-changed (count (filter #(= [true true] (get-in % [:crossover :changed?])) records))
               :crossover-fallbacks (count (filter #(get-in % [:crossover :fallback?]) records))
               :max-mutation-attempts (apply max (map #(get-in % [:mutation :attempts]) records))
               :max-crossover-attempts (apply max (map #(get-in % [:crossover :attempts]) records))}
      rejection-counts (frequencies (map :reason (mapcat #(concat (get-in % [:mutation :rejections])
                                                                (get-in % [:crossover :rejections])) records)))
      tight-results (mapv #(v/crossover niki.optimizer.variation-test/rich
                                       (assoc-in niki.optimizer.variation-test/other [2 1 2 1 1] :swap)
                                       {:seed % :max-attempts 1
                                        :limits {:max-nodes 13 :max-depth 6 :max-count 3}}) (range 300))
      program-tags (sort (set (map first (mapcat #(tree-seq vector? (fn [n] (filterv vector? n)) (:program %)) records))))]
  (println summary)
  (println {:broad-rejection-counts rejection-counts :generation-only-tags program-tags})
  (println {:tight-cases (count tight-results)
            :tight-rejection-counts (frequencies (map :message (mapcat :rejections tight-results)))})
  (spit "docs/evidence/g4-variation-sample-lineage.edn"
        (pr-str {:mutation (v/mutate niki.optimizer.variation-test/rich {:seed 37})
                 :crossover (v/crossover niki.optimizer.variation-test/rich niki.optimizer.variation-test/other {:seed 37})
                 :minimal-fallback (v/mutate niki.optimizer.variation-test/minimal
                                              {:seed 2 :limits {:max-nodes 3 :max-depth 3}})
                 :structural-fallback (first (filter #(some (fn [r] (= :structural-or-type-rejection (:reason r)))
                                                            (:rejections %)) tight-results))})))
