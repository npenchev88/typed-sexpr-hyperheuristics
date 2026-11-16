;; Native-launcher verification without Windows -e quote ambiguity.
;; This file executes only the owned runtime tests, not the pending DSL RED.
(load-file "test/niki/optimizer/runtime_test.clj")
(let [r (clojure.test/run-tests 'niki.optimizer.runtime-test)]
  (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))
