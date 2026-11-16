;; Checks proposal-data consistency only. This is not the new experiment.
(require '[clojure.edn :as edn]
         '[clojure.test :refer [deftest is run-tests]])

(def flagship (edn/read-string (slurp "configs/flagship-proposal.edn")))
(def smoke (edn/read-string (slurp "configs/smoke-proposal.edn")))

(deftest proposal-status
  (is (= :proposal (:status flagship)))
  (is (false? (:confirmatory-ready? flagship)))
  (is (false? (:launch-authorized? flagship)))
  (is (= :not-locked (get-in flagship [:test :holdout-status])))
  (is (false? (:runner-implemented? smoke))))

(deftest resource-accounting
  (let [runs (get-in flagship [:outer :independent-runs])
        arms (count (:arms flagship))
        domains (count (:domains flagship))
        repetitions (* runs arms domains)
        slots (get-in flagship [:outer :assessment-slots])
        train (:train flagship)
        validation (:validation flagship)
        test (:test flagship)
        training (* repetitions slots (:tasks-per-domain train)
                    (:inner-seeds train) (:solution-proposals train))
        validating (* repetitions (:max-finalists validation)
                      (:tasks-per-domain validation) (:inner-seeds validation)
                      (:solution-proposals validation))
        testing (* repetitions (:tasks-per-domain test) (:inner-seeds test)
                   (:solution-proposals test))]
    (is (= slots (+ (get-in flagship [:outer :initial-population])
                    (* (get-in flagship [:outer :offspring-batches])
                       (get-in flagship [:outer :offspring-per-batch])))))
    (is (= 5 arms))
    (is (= 2 domains))
    (is (= training (get-in flagship [:budget-ceilings :training])))
    (is (= validating (get-in flagship [:budget-ceilings :validation])))
    (is (= testing (get-in flagship [:budget-ceilings :test])))
    (is (= (+ training validating testing)
           (get-in flagship [:budget-ceilings :subtotal])))
    (is (true? (get-in flagship [:outer :duplicate-consumes-slot?])))
    (is (true? (get-in flagship [:outer :failed-slot-consumes-slot?])))
    (is (true? (get-in flagship [:resource-policy :charge-duplicates?])))
    (is (false? (get-in flagship [:test :pool-archives-across-seeds?])))))

(deftest smoke-budget
  (let [o (:outer smoke) t (:train smoke)
        slots (+ (:initial-population o)
                 (* (:offspring-batches o) (:offspring-per-batch o)))]
    (is (= slots (:assessment-slots o)))
    (is (= (:maximum-solution-proposals smoke)
           (* slots (:independent-runs o) (:tasks-per-domain t)
              (:inner-seeds t) (:solution-proposals t))))
    (is (= :synthetic-development-only (:data smoke)))))

(deftest indicator-and-domain-contract
  (let [reference (get-in flagship [:indicator :reference])]
    (is (= (reduce * reference) (get-in flagship [:indicator :normalizer])))
    (is (= [:min :min] (get-in flagship [:indicator :directions])))
    (is (= [:negative-mean-return :sample-standard-deviation]
           (get-in flagship [:domains :portfolio :objectives])))
    (is (= [:mean-completion-time :total-tardiness]
           (get-in flagship [:domains :scheduling :objectives])))))

(println "Protocol proposal validation only; no optimization run is launched.")
(let [{:keys [fail error]} (run-tests 'user)]
  (shutdown-agents)
  (System/exit (if (zero? (+ fail error)) 0 1)))
