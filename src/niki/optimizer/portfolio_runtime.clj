(ns niki.optimizer.portfolio-runtime
  "Trusted exact-K adapter. Preparation (including fixed G2 bounds) is timed
  separately; primitive candidate construction performs no objective queries."
  (:require [niki.optimizer.portfolio :as portfolio]
            [niki.optimizer.pareto :as pareto])
  (:import [java.util Random]))

(defn- random-selection [n k ^Random rng]
  ;; Partial Fisher-Yates: exactly K bounded draws, never duplicate rejection.
  (loop [pool (vec (range n)) i 0]
    (if (= i k)
      (vec (sort (subvec pool 0 k)))
      (let [j (+ i (.nextInt rng (- n i)))
            a (nth pool i) b (nth pool j)]
        (recur (assoc pool i b j a) (inc i))))))

(defn make-adapter
  "Build once from a G1 PreparedPortfolio. Random/first initialization and
  swap/restart are bounded. Swap from nil initializes once; K=N swaps are
  charged duplicates at runtime, not retries. :preparation-timing is scientific
  preparation cost, outside the runtime's proposal/interpreter allowances."
  [instance]
  (when-not (instance? niki.optimizer.portfolio.PreparedPortfolio instance)
    (throw (ex-info "Call portfolio/prepare-instance first" {:error :unprepared-instance})))
  (let [started (System/nanoTime)
        n (count (:returns instance)) k (:k instance)
        normalization (pareto/portfolio-normalization instance)
        canonicalize #(portfolio/validate-selection instance %)
        initialize (fn [kind rng]
                     (case kind
                       :first (vec (range k))
                       :random (random-selection n k rng)
                       (throw (ex-info "Unsupported initializer" {:error :invalid-initializer :kind kind}))))
        propose (fn [kind current ^Random rng]
                  (case kind
                    :restart (random-selection n k rng)
                    :swap (if (nil? current)
                            (random-selection n k rng)
                            (let [current (canonicalize current)]
                              (if (= k n) current
                                  (let [members (set current)
                                        outside (filterv #(not (contains? members %)) (range n))
                                        position (.nextInt rng k)
                                        replacement (nth outside (.nextInt rng (count outside)))]
                                    (vec (sort (assoc current position replacement)))))))
                    (throw (ex-info "Unsupported operator" {:error :invalid-operator :kind kind}))))]
    {:domain :portfolio :id "portfolio-runtime-v1" :normalization normalization
     :initializers #{:random :first} :operators #{:swap :restart}
     :initialize initialize :propose propose :canonicalize canonicalize
     :valid? (fn [solution]
               (try (= solution (canonicalize solution)) (catch Exception _ false)))
     :evaluate #(:objectives (portfolio/evaluate instance %))
     :preparation-timing {:elapsed-ns (- (System/nanoTime) started)}}))
