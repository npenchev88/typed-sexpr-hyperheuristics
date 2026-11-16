;; Synthetic G2 correctness check only: no GP, market data or optimizer experiment.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-reference :as reference]
         '[niki.optimizer.pareto :as pareto])
(import '[java.time Instant]
        '[java.nio.charset StandardCharsets]
        '[java.security MessageDigest]
        '[java.math BigInteger])

(let [input-bytes (with-open [in (io/input-stream (io/resource "fixtures/g1-portfolio.edn"))]
                    (.readAllBytes in))
      panel (edn/read-string (String. input-bytes StandardCharsets/UTF_8))
      _ (when-not (= :synthetic-development-only (:source panel))
          (throw (ex-info "G2 verification requires the synthetic fixture" {:error :invalid-fixture})))
      instance (portfolio/prepare-instance panel)
      normalization (pareto/portfolio-normalization instance)
      choices (vec (reference/selections (count (:assets panel)) (:k panel)))
      results (mapv #(portfolio/evaluate instance %) choices)
      points (mapv :objectives results)
      archive (pareto/front points)
      representatives (reduce (fn [m result]
                                (let [key (mapv #(if (zero? %) 0.0 %) (:objectives result))]
                                  (if (contains? m key) m (assoc m key (:selection result)))))
                              {} results)
      trace (mapv #(pareto/normalized-hypervolume % normalization)
                  (reductions pareto/insert [] points))
      score (pareto/normalized-hypervolume archive normalization)
      passed? (and (= 3003 (count choices))
                   (= archive (reduce pareto/insert [] points))
                   (= archive (pareto/front (vec (reverse points))))
                   (every? true? (map <= trace (rest trace)))
                   (<= 0.0 score 1.0))
      csv-path "docs/evidence/g2-front.csv"
      summary {:gate :G2 :status (if passed? :passed :failed)
               :scope :synthetic-correctness-only :experiment-launched? false
               :verified-at (str (Instant/now)) :fixture-id (:fixture-id panel)
               :fixture-sha256 (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") input-bytes)))
               :clojure-version (clojure-version) :java-version (System/getProperty "java.version")
               :portfolios-checked (count results) :archive-size (count archive)
               :bounds (:bounds normalization) :constant-coordinates (:constant-coordinates normalization)
               :normalized-hypervolume score :reference [1.05 1.05] :divisor 1.1025
               :boundary-tolerance pareto/boundary-tolerance
               :insertion-and-batch-agree? (= archive (reduce pareto/insert [] points))
               :order-invariant? (= archive (pareto/front (vec (reverse points))))
               :hypervolume-monotone? (every? true? (map <= trace (rest trace)))}]
  (io/make-parents csv-path)
  (with-open [out (io/writer csv-path :encoding "UTF-8")]
    (.write out "selection,negative_mean,standard_deviation,normalized_negative_mean,normalized_risk\n")
    (doseq [[x y :as point] archive]
      (let [[nx ny] (pareto/normalize-point normalization point)]
        (.write out (str (str/join "," [(pr-str (representatives point)) x y nx ny]) "\n")))))
  (spit "docs/evidence/g2-verification.edn" (str (pr-str summary) "\n") :encoding "UTF-8")
  (prn summary)
  (when-not passed? (throw (ex-info "G2 synthetic check failed" summary))))
