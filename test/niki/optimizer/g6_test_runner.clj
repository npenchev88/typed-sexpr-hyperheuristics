(ns niki.optimizer.g6-test-runner
  (:require [clojure.test :as test]
            [niki.optimizer.scheduling-test]))

(defn -main [& _]
  (let [r (test/run-tests 'niki.optimizer.scheduling-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
