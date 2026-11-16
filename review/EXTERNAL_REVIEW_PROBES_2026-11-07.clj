;; Read-only G1 contract probes for external-review triage. No optimizer run.
(require '[niki.optimizer.portfolio :as p]
         '[niki.optimizer.portfolio-reference :as r])
(import '[java.math BigDecimal]
        '[java.util.concurrent.atomic DoubleAdder])

(def dates ["2000-01-01" "2000-01-02"])
(defn panel [rows]
  {:k (count rows)
   :assets (mapv (fn [i xs] {:id (str "a" i) :dates dates :returns xs}) (range) rows)})
(defn compare-risks [rows]
  (let [raw (panel rows)
        normalized (update raw :assets
                           (fn [assets] (mapv #(update % :returns
                                                       (fn [xs] (mapv (fn [x] (BigDecimal. (double x))) xs)))
                                              assets)))
        selected (vec (range (count rows)))
        direct (p/evaluate (p/prepare-instance raw) selected)
        reference (r/evaluate (r/prepare-reference raw) selected)
        same-domain (r/evaluate (r/prepare-reference normalized) selected)]
    {:direct-risk (:sample-standard-deviation direct)
     :raw-reference-risk (:sample-standard-deviation reference)
     :normalized-reference-risk (:sample-standard-deviation same-domain)
     :normalized-reference-matches? (= (:sample-standard-deviation direct)
                                       (:sample-standard-deviation same-domain))}))

(let [a (doto (DoubleAdder.) (.add 0.5))
      negative (doto (DoubleAdder.) (.add -1.5))
      valid (panel [[0.0 0.0]])
      kv-input (list :k 1 :assets (:assets valid))
      seq-result (try {:accepted? true :prepared (p/prepare-instance kv-input)}
                      (catch Exception e {:accepted? false :exception (.getName (class e))}))
      lazy-realized (atom false)
      poison (lazy-seq (reset! lazy-realized true)
                       (throw (ex-info "Bounded probe: lazy panel realized" {:probe :realized})))
      lazy-result (try (p/prepare-instance poison) {:unexpected-success true}
                       (catch Exception e {:exception (.getName (class e)) :data (ex-data e)}))
      negative-result (try (let [prepared (p/prepare-instance (panel [[negative 0.0]]))]
                             {:accepted? true :normalized-returns (:returns prepared)})
                           (catch Exception e {:accepted? false :exception (.getName (class e))}))]
  (prn {:scope :read-only-g1-contract-probes
        :large-doubles (compare-risks [[1.0e23 0.0] [0.0 2.0e22] [0.0 4.0e22] [0.0 4.0e22]])
        :ordinary-doubles (compare-risks [[0.3 0.1] [0.0 0.2]])
        :longs (compare-risks [[9223372036854775807 9223372036854775000]])
        :mutable-number (compare-risks [[a 0.0]])
        :negative-mutable-number negative-result
        :non-map-kv-sequence seq-result
        :lazy-input {:realized? @lazy-realized :result lazy-result}}))
