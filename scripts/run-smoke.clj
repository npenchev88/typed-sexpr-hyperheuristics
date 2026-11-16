;; Development smoke/pilot profiler per configs/smoke-proposal.edn:
;; 32 assessment slots (8 initial + 3 x 8 offspring), 2 synthetic tasks per domain,
;; 1 inner seed, 64 proposals per execution (ceiling 4,096 proposals per arm/domain).
;; No scientific efficacy or confirmatory claim is made.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-runtime :as port-runtime]
         '[niki.optimizer.portfolio-data :as port-data]
         '[niki.optimizer.scheduling :as scheduling]
         '[niki.optimizer.scheduling-runtime :as sched-runtime]
         '[niki.optimizer.search :as search])
(import '[java.time Instant]
        '[java.lang.management ManagementFactory])

(let [smoke-cfg (edn/read-string (slurp "configs/smoke-proposal.edn"))
      port-suite (port-data/generate-synthetic-suite {:n 15 :k 5 :t 20 :base-seed 20261108})
      port-tasks (mapv (fn [t]
                         {:task-id (:task-id t)
                          :adapter (port-runtime/make-adapter (portfolio/prepare-instance (:panel t)))})
                       (take 2 (:train port-suite)))
      sched-suite (scheduling/generate-benchmark-suite {:jobs 30 :base-seed 20261108})
      sched-tasks (mapv (fn [inst]
                          {:task-id (:instance-id inst)
                           :adapter (sched-runtime/make-adapter (scheduling/prepare-instance inst))})
                        (take 2 (:train sched-suite)))
      mem-bean (ManagementFactory/getMemoryMXBean)
      arms [:gp-mixed :gp-mutation-only :random-dsl :formula-only :configured-conventional]
      started-ns (System/nanoTime)
      results
      (vec
       (for [[domain tasks] [[:portfolio port-tasks] [:scheduling sched-tasks]]
             arm arms]
         (let [t0 (System/nanoTime)
               res (search/run-arm {:arm arm
                                    :domain domain
                                    :run-seed 0
                                    :outer (:outer smoke-cfg)
                                    :train {:tasks tasks
                                            :inner-seeds [0]
                                            :solution-proposals (get-in smoke-cfg [:train :solution-proposals])
                                            :step-budget 4096}})
               elapsed-ms (/ (double (- (System/nanoTime) t0)) 1.0e6)
               heap-bytes (.getUsed (.getHeapMemoryUsage mem-bean))]
           {:domain domain
            :arm arm
            :slot-ledger (:slot-ledger res)
            :selected-id (get-in res [:selected :candidate-id])
            :selected-node-count (get-in res [:selected :node-count])
            :training-best-mean-hv (get-in res [:selected :validation-mean-hv])
            :elapsed-ms elapsed-ms
            :observed-heap-mib (/ (double heap-bytes) (* 1024.0 1024.0))})))
      total-elapsed-ms (/ (double (- (System/nanoTime) started-ns)) 1.0e6)
      summary {:gate :G7-smoke-pilot
               :status :passed
               :purpose :correctness-and-resource-profiling
               :scientific-claim :none
               :executed-at (str (Instant/now))
               :clojure-version (clojure-version)
               :java-version (System/getProperty "java.version")
               :total-elapsed-ms total-elapsed-ms
               :runs results}]
  (io/make-parents "docs/evidence/g7-smoke-pilot.edn")
  (spit "docs/evidence/g7-smoke-pilot.edn" (str (pr-str summary) "\n") :encoding "UTF-8")
  (prn summary))
