(ns niki.optimizer.g7-test-runner
  (:require [clojure.test :as test]
            [niki.optimizer.g7-test]))

(defn -main [& _]
  (let [r (test/run-tests 'niki.optimizer.g7-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
