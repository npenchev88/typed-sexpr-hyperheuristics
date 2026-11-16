(ns niki.optimizer.test-runner
  (:require [clojure.test :as test]
            [niki.optimizer.portfolio-test]
            [niki.optimizer.portfolio-reference-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'niki.optimizer.portfolio-test
                                                        'niki.optimizer.portfolio-reference-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
