(ns niki.optimizer.g3-test-runner
  (:require [clojure.test :as test]
            [niki.optimizer.dsl-test]
            [niki.optimizer.runtime-test]))
(defn -main [& _]
  (let [r (test/run-tests 'niki.optimizer.dsl-test 'niki.optimizer.runtime-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
