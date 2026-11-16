;; G1 correctness verification only. No optimizer search or market data access.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-reference :as reference])
(import '[java.nio.charset StandardCharsets]
        '[java.security MessageDigest]
        '[java.math BigInteger]
        '[java.time Instant])

(let [started (System/nanoTime)
      input-bytes (with-open [stream (io/input-stream (io/resource "fixtures/g1-portfolio.edn"))]
                    (.readAllBytes stream))
      panel (edn/read-string (String. input-bytes StandardCharsets/UTF_8))
      _ (when-not (and (= :synthetic-development-only (:source panel))
                       (= :g1-modular-v1 (:fixture-id panel))
                       (= 5 (:k panel)) (= 15 (count (:assets panel))))
          (throw (ex-info "Unexpected G1 fixture" {:error :invalid-fixture})))
      instance (portfolio/prepare-instance panel)
      prepared (reference/prepare-reference panel)
      choices (vec (reference/selections (count (:assets panel)) (:k panel)))
      rows (mapv (fn [selection]
                   (let [direct (portfolio/evaluate instance selection)
                         independent (reference/evaluate prepared selection)
                         errors (mapv #(abs (- (double %1) (double %2)))
                                      (:objectives direct) (:objectives independent))
                         allowed (mapv #(+ 1.0e-12 (* 1.0e-10 (abs (double %))))
                                       (:objectives independent))]
                     {:selection selection
                      :direct direct :reference independent :errors errors
                      :passed? (and (every? #(Double/isFinite (double %)) (:objectives direct))
                                    (every? true? (map <= errors allowed))
                                    (= direct (portfolio/evaluate instance (vec (reverse selection)))))}))
                 choices)
      passed? (and (= 3003 (count rows)) (= 3003 (count (set choices)))
                   (every? :passed? rows))
      digest (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") input-bytes)))
      summary {:gate :G1 :status (if passed? :passed :failed)
               :scope :synthetic-correctness-only :experiment-launched? false
               :reference-input-mode (:input-mode prepared)
               :verified-at (str (Instant/now))
               :clojure-version (clojure-version) :java-version (System/getProperty "java.version")
               :fixture-id (:fixture-id panel) :fixture-sha256 digest
               :assets (count (:assets panel)) :k (:k panel) :observations (count (:dates instance))
               :expected-portfolios 3003 :checked-portfolios (count rows)
               :unique-selections (count (set choices))
               :failed-crosschecks (count (remove :passed? rows))
               :maximum-absolute-mean-error (reduce max 0.0 (map #(first (:errors %)) rows))
               :maximum-absolute-risk-error (reduce max 0.0 (map #(second (:errors %)) rows))
               :tolerance {:absolute 1.0e-12 :relative 1.0e-10}
               :verification-elapsed-ms (/ (- (System/nanoTime) started) 1.0e6)}
      csv-path "docs/evidence/g1-objectives.csv"
      report-path "docs/evidence/g1-verification.edn"]
  (io/make-parents csv-path)
  (with-open [writer (io/writer csv-path :encoding "UTF-8")]
    (.write writer "selection,mean_return,sample_standard_deviation,reference_mean,reference_risk,exact_reference_variance,mean_abs_error,risk_abs_error,passed\n")
    (doseq [{:keys [selection direct reference errors passed?]} rows]
      (.write writer (str (str/join "," [(pr-str selection) (:mean-return direct)
                                        (:sample-standard-deviation direct)
                                        (:mean-return reference) (:sample-standard-deviation reference)
                                        (:exact-variance reference) (first errors) (second errors) passed?]) "\n"))))
  (spit report-path (str (pr-str summary) "\n") :encoding "UTF-8")
  (prn summary)
  (when-not passed? (throw (ex-info "G1 crosscheck failed" summary))))
