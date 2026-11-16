;; Synthetic G6 scheduling correctness check: all 5,040 7-job permutations checked
;; against independent exact reference, plus runtime execution with unchanged G1-G4 core.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[niki.optimizer.scheduling :as scheduling]
         '[niki.optimizer.scheduling-reference :as reference]
         '[niki.optimizer.scheduling-runtime :as sched-runtime]
         '[niki.optimizer.pareto :as pareto]
         '[niki.optimizer.runtime :as runtime])
(import '[java.time Instant]
        '[java.nio.charset StandardCharsets]
        '[java.security MessageDigest]
        '[java.math BigInteger])

(let [input-bytes (with-open [in (io/input-stream (io/resource "fixtures/g6-scheduling.edn"))]
                    (.readAllBytes in))
      panel (edn/read-string (String. input-bytes StandardCharsets/UTF_8))
      _ (when-not (= :synthetic-development-only (:source panel))
          (throw (ex-info "G6 verification requires the synthetic fixture" {:error :invalid-fixture})))
      instance (scheduling/prepare-instance panel)
      oracle (reference/prepare-reference panel)
      normalization (scheduling/scheduling-normalization instance)
      perms (reference/permutations (count (:jobs panel)))
      prod-results (mapv #(scheduling/evaluate instance %) perms)
      ref-results (mapv #(reference/evaluate-exact oracle %) perms)
      points (mapv :objectives prod-results)
      archive (pareto/front points)
      representatives (reduce (fn [m result]
                                (let [k (:objectives result)]
                                  (if (contains? m k) m (assoc m k (:schedule result)))))
                              {} prod-results)
      trace (mapv #(pareto/normalized-hypervolume % normalization)
                  (reductions pareto/insert [] points))
      score (pareto/normalized-hypervolume archive normalization)
      spt (sched-runtime/spt-schedule instance)
      edd (sched-runtime/edd-schedule instance)
      spt-eval (scheduling/evaluate instance spt)
      edd-eval (scheduling/evaluate instance edd)
      all-agree? (every? true?
                         (map (fn [p r]
                                (and (= (:schedule p) (:schedule r))
                                     (<= (Math/abs (- (double (:mean-completion-time p))
                                                      (double (:mean-completion-time r))))
                                         1.0e-12)
                                     (= (:total-tardiness p) (:total-tardiness r))))
                              prod-results ref-results))
      passed? (and (= 5040 (count perms))
                   all-agree?
                   (= archive (reduce pareto/insert [] points))
                   (= archive (pareto/front (vec (reverse points))))
                   (every? true? (map <= trace (rest trace)))
                   (<= 0.0 score 1.0))
      csv-path "docs/evidence/g6-front.csv"
      summary {:gate :G6
               :status (if passed? :passed :failed)
               :scope :synthetic-correctness-only
               :experiment-launched? false
               :verified-at (str (Instant/now))
               :instance-id (:instance-id panel)
               :fixture-sha256 (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") input-bytes)))
               :clojure-version (clojure-version)
               :java-version (System/getProperty "java.version")
               :schedules-checked (count prod-results)
               :exact-reference-agreement? all-agree?
               :archive-size (count archive)
               :bounds (:bounds normalization)
               :normalized-hypervolume score
               :spt-objectives (:objectives spt-eval)
               :edd-objectives (:objectives edd-eval)
               :insertion-and-batch-agree? (= archive (reduce pareto/insert [] points))
               :order-invariant? (= archive (pareto/front (vec (reverse points))))
               :hypervolume-monotone? (every? true? (map <= trace (rest trace)))}]
  (io/make-parents csv-path)
  (with-open [out (io/writer csv-path :encoding "UTF-8")]
    (.write out "schedule,mean_completion_time,total_tardiness,normalized_mean_completion,normalized_tardiness\n")
    (doseq [[x y :as point] archive]
      (let [[nx ny] (pareto/normalize-point normalization point)]
        (.write out (str (str/join "," [(pr-str (representatives point)) x y nx ny]) "\n")))))
  (spit "docs/evidence/g6-verification.edn" (str (pr-str summary) "\n") :encoding "UTF-8")
  (prn summary)
  (when-not passed? (throw (ex-info "G6 synthetic check failed" summary))))
