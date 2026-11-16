(ns niki.optimizer.g4-test-runner
  (:require [clojure.test :as test]
            [niki.optimizer.variation-test]
            [niki.optimizer.shape-validation-test]
            [niki.optimizer.integration-test]))
(defn -main [& _]
  (let [r (test/run-tests 'niki.optimizer.variation-test
                          'niki.optimizer.shape-validation-test
                          'niki.optimizer.integration-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
