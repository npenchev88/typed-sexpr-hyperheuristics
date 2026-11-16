(println {:clojure-version (clojure-version)
          :java-version (System/getProperty "java.version")})
(load-file "test/niki/optimizer/variation_test.clj")
(let [result (clojure.test/run-tests 'niki.optimizer.variation-test)]
  (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))
