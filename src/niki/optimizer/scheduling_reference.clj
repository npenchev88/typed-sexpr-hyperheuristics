(ns niki.optimizer.scheduling-reference
  "Independent exact scheduling reference oracle. Shares zero helpers with
  niki.optimizer.scheduling; computes completion times and tardiness via exact
  BigInt/Ratio arithmetic before final binary64 conversion.")

(defn permutations
  "Lexicographic enumeration of all n! permutations of [0 .. n-1]."
  [n]
  (letfn [(step [remaining]
            (if (empty? remaining)
              [[]]
              (mapcat (fn [x]
                        (let [others (filterv #(not= % x) remaining)]
                          (map #(into [x] %) (step others))))
                      remaining)))]
    (vec (step (vec (range n))))))

(defn prepare-reference
  "Prepare an independent reference map from a raw scheduling panel."
  [panel]
  (when-not (map? panel)
    (throw (ex-info "Panel must be a map" {:error :invalid-panel})))
  (let [jobs (:jobs panel)]
    (when-not (and (vector? jobs) (seq jobs))
      (throw (ex-info "Jobs must be a non-empty vector" {:error :invalid-jobs})))
    {:instance-id (:instance-id panel)
     :job-count (count jobs)
     :durations (mapv #(bigint (:duration %)) jobs)
     :due-dates (mapv #(bigint (:due-date %)) jobs)}))

(defn evaluate-exact
  "Evaluate a permutation using exact BigInt/Ratio arithmetic."
  [reference schedule]
  (let [n (:job-count reference)]
    (when-not (and (vector? schedule)
                   (= n (count schedule))
                   (= (set (range n)) (set schedule)))
      (throw (ex-info "Invalid permutation in reference" {:schedule schedule})))
    (let [durations (:durations reference)
          due-dates (:due-dates reference)
          ordered-durations (mapv #(nth durations %) schedule)
          ordered-dues (mapv #(nth due-dates %) schedule)
          completions (vec (reductions + ordered-durations))
          tardinesses (mapv (fn [c d] (max 0N (- c d))) completions ordered-dues)
          exact-mean (/ (reduce + 0N completions) (bigint n))
          exact-tardiness (reduce + 0N tardinesses)
          f1 (double exact-mean)
          f2 (double exact-tardiness)]
      {:schedule (mapv long schedule)
       :exact-mean-completion-time exact-mean
       :exact-total-tardiness exact-tardiness
       :mean-completion-time (if (zero? f1) 0.0 f1)
       :total-tardiness (if (zero? f2) 0.0 f2)
       :objectives [(if (zero? f1) 0.0 f1)
                    (if (zero? f2) 0.0 f2)]})))
