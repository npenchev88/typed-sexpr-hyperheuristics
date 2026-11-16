(ns niki.optimizer.g2-test-runner
  (:require [clojure.test :as test]
            [niki.optimizer.pareto-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'niki.optimizer.pareto-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
