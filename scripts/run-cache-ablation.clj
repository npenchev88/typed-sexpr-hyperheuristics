;; Secondary Mechanism Ablation: Cache-On (:cache? true) vs Cache-Off (:cache? false)
;; & Charged-Proposal Budget vs Uncharged-Cache-Hit (256 Unique Evaluations) Counterfactual.
;; Evaluates all 16 selected representatives per domain (8 arm/prior configs x 2 outer replications)
;; across 24 held-out test tasks x 10 inner seeds (240 executions per representative).
;; Saves full evidence to docs/evidence/cache-ablation-results.edn and generates
;; docs/figures/cache_ablation.svg.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-runtime :as port-runtime]
         '[niki.optimizer.portfolio-data :as port-data]
         '[niki.optimizer.scheduling :as scheduling]
         '[niki.optimizer.scheduling-runtime :as sched-runtime]
         '[niki.optimizer.pareto :as pareto]
         '[niki.optimizer.runtime :as runtime]
         '[niki.optimizer.baselines :as baselines]
         '[niki.optimizer.search :as search])
(import '[java.time Instant]
        '[java.util Locale])

(defn fmt [pattern & args]
  (String/format Locale/US pattern (to-array args)))

(defn svg-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn prepare-portfolio-split [tasks]
  (mapv (fn [t]
          {:task-id (:task-id t)
           :adapter (port-runtime/make-adapter (portfolio/prepare-instance (:panel t)))})
        tasks))

(defn prepare-scheduling-split [instances]
  (mapv (fn [inst]
          {:task-id (:instance-id inst)
           :adapter (sched-runtime/make-adapter (scheduling/prepare-instance inst))})
        instances))

(defn evaluate-uncharged-cache-counterfactual
  "Counterfactual where duplicate proposals (cache hits) do NOT consume the 256-proposal budget,
   reusing `eval-on` for the 256-proposal baseline and only re-running when `cache-hits > 0`."
  [arm candidate tasks eval-on]
  (let [task-by-id (into {} (map (juxt :task-id :adapter) tasks))
        runs
        (for [task-out (:task-outcomes eval-on)
              :let [adapter (get task-by-id (:task-id task-out))]
              s-run (:seed-runs task-out)
              :let [s (:seed s-run)
                    hits (long (get-in s-run [:charged-costs :cache-hits] 0))
                    props-on (long (get-in s-run [:charged-costs :proposal-attempts] 0))
                    hv-on (double (:normalized-hv s-run))]]
          (if (zero? hits)
            {:hv-charged hv-on
             :hv-uncharged hv-on
             :extra-proposals 0}
            (let [r-free (if (= arm :configured-conventional)
                           (baselines/run-conventional candidate adapter {:solution-budget (+ 256 hits) :seed (long s) :cache? true})
                           (runtime/run candidate adapter {:solution-budget (+ 256 hits) :step-budget 4096 :seed (long s) :cache? true :trace? false}))]
              {:hv-charged hv-on
               :hv-uncharged (pareto/normalized-hypervolume (:archive r-free) (:normalization adapter))
               :extra-proposals (- (long (get-in r-free [:charged-costs :proposal-attempts] 0)) props-on)})))]
    {:mean-hv-charged (/ (reduce + 0.0 (map :hv-charged runs)) (double (count runs)))
     :mean-hv-uncharged (/ (reduce + 0.0 (map :hv-uncharged runs)) (double (count runs)))
     :mean-extra-proposals (/ (reduce + 0.0 (map :extra-proposals runs)) (double (count runs)))}))

(let [started-ns (System/nanoTime)
      _ (println "Loading real-data G8 results (seeds 0..1) and preparing test splits...")
      two-rep (edn/read-string (slurp "docs/evidence/g8-thirty-replications-results.edn"))
      port-raw (port-data/load-nasdaq-suite)
      sched-raw (scheduling/load-orlib-wt-suite)
      test-splits {:portfolio (prepare-portfolio-split (:test port-raw))
                   :scheduling (prepare-scheduling-split (:test sched-raw))}
      test-seeds (vec (range 1001 1011))
      domain-ablation
      (into {}
            (for [domain [:portfolio :scheduling]]
              (let [_ (println "Running Cache-On vs Cache-Off ablation for domain:" domain)
                    test-tasks (get test-splits domain)
                    summaries (->> (get-in two-rep [:domains domain :arm-summaries])
                                   (mapv (fn [s]
                                           (let [rs (vec (take 2 (:replications s)))
                                                 hvs (mapv :test-mean-normalized-hv rs)]
                                             (assoc s
                                                    :replications rs
                                                    :replication-test-hvs hvs
                                                    :mean-test-hv (/ (reduce + 0.0 hvs) (double (count hvs))))))))
                    rows
                    (vec
                     (pmap
                      (fn [summary]
                        (let [arm (:arm summary)
                              prior (:generator-prior summary)
                              rep-results
                              (mapv
                               (fn [rep]
                                 (let [rseed (:run-seed rep)
                                       cand (get-in rep [:selected :candidate])
                                       cid (get-in rep [:selected :candidate-id])
                                       ;; 1. Cache-ON evaluation (:cache? true)
                                       t-on-0 (System/nanoTime)
                                       eval-on (search/assess-candidate
                                                arm cand cid test-tasks test-seeds
                                                {:solution-budget 256 :step-budget 4096 :cache? true})
                                       ms-on (/ (double (- (System/nanoTime) t-on-0)) 1.0e6)
                                       ;; 2. Cache-OFF evaluation (:cache? false)
                                       t-off-0 (System/nanoTime)
                                       eval-off (search/assess-candidate
                                                 arm cand cid test-tasks test-seeds
                                                 {:solution-budget 256 :step-budget 4096 :cache? false})
                                       ms-off (/ (double (- (System/nanoTime) t-off-0)) 1.0e6)
                                       ;; 3. Uncharged-cache-hit counterfactual
                                       cf (evaluate-uncharged-cache-counterfactual arm cand test-tasks eval-on)
                                       ;; Verify exact per-task, per-seed archive & HV identity between Cache-ON and Cache-OFF
                                       exact-archive-match?
                                       (every? true?
                                               (map (fn [t1 t2]
                                                      (and (= (:mean-normalized-hv t1) (:mean-normalized-hv t2))
                                                           (every? true?
                                                                   (map (fn [s1 s2]
                                                                          (and (= (:normalized-hv s1) (:normalized-hv s2))
                                                                               (= (:archive s1) (:archive s2))))
                                                                        (:seed-runs t1) (:seed-runs t2)))))
                                                    (:task-outcomes eval-on)
                                                    (:task-outcomes eval-off)))]
                                   {:run-seed rseed
                                    :hv-cache-on (:mean-normalized-hv eval-on)
                                    :hv-cache-off (:mean-normalized-hv eval-off)
                                    :hv-abs-diff (Math/abs (- (double (:mean-normalized-hv eval-on))
                                                              (double (:mean-normalized-hv eval-off))))
                                    :exact-archive-match? exact-archive-match?
                                    :totals-cache-on (:totals eval-on)
                                    :totals-cache-off (:totals eval-off)
                                    :elapsed-ms-cache-on ms-on
                                    :elapsed-ms-cache-off ms-off
                                    :counterfactual-uncharged-hv (:mean-hv-uncharged cf)
                                    :counterfactual-hv-inflation (- (double (:mean-hv-uncharged cf))
                                                                    (double (:mean-normalized-hv eval-on)))
                                    :counterfactual-extra-proposals (:mean-extra-proposals cf)
                                    :outer-slot-ledger (:slot-ledger rep)}))
                               (:replications summary))
                              avg (fn [f] (/ (reduce + 0.0 (map f rep-results)) (double (count rep-results))))
                              proposals-on (avg #(get-in % [:totals-cache-on :proposal-attempts]))
                              hits-on (avg #(get-in % [:totals-cache-on :cache-hits]))
                              obj-on (avg #(get-in % [:totals-cache-on :objective-calculations]))
                              obj-off (avg #(get-in % [:totals-cache-off :objective-calculations]))]
                          {:arm arm
                           :generator-prior prior
                           :mean-hv-cache-on (avg :hv-cache-on)
                           :mean-hv-cache-off (avg :hv-cache-off)
                           :max-hv-abs-diff (apply max (map :hv-abs-diff rep-results))
                           :all-archives-identical? (every? :exact-archive-match? rep-results)
                           :mean-proposals-240-runs proposals-on
                           :mean-cache-hits-240-runs hits-on
                           :cache-hit-rate-pct (if (pos? proposals-on) (* 100.0 (/ hits-on proposals-on)) 0.0)
                           :mean-obj-calcs-cache-on obj-on
                           :mean-obj-calcs-cache-off obj-off
                           :mean-elapsed-ms-cache-on (avg :elapsed-ms-cache-on)
                           :mean-elapsed-ms-cache-off (avg :elapsed-ms-cache-off)
                           :mean-counterfactual-uncharged-hv (avg :counterfactual-uncharged-hv)
                           :mean-counterfactual-hv-inflation (avg :counterfactual-hv-inflation)
                           :mean-counterfactual-extra-proposals (avg :counterfactual-extra-proposals)
                           :mean-outer-distinct-slots (avg #(get-in % [:outer-slot-ledger :distinct-candidates]))
                           :mean-outer-duplicate-slots (avg #(get-in % [:outer-slot-ledger :duplicate-slots]))
                           :replications rep-results}))
                      summaries))]
                [domain rows])))
      total-s (/ (double (- (System/nanoTime) started-ns)) 1.0e9)
      out-data {:experiment :cache-on-vs-cache-off-ablation
                :executed-at (str (Instant/now))
                :total-elapsed-seconds total-s
                :domains domain-ablation}]
  (io/make-parents "docs/evidence/cache-ablation-results.edn")
  (spit "docs/evidence/cache-ablation-results.edn" (str (pr-str out-data) "\n") :encoding "UTF-8")
  (println "\nCompleted Cache-On vs Cache-Off ablation in" (format "%.2f" total-s) "seconds.")
  (doseq [domain [:portfolio :scheduling]]
    (println "\n=========================================================================================================")
    (println "DOMAIN:" domain "(2 Replications x 24 Test Tasks x 10 Seeds = 480 Executions per Row)")
    (println "=========================================================================================================")
    (doseq [r (get domain-ablation domain)]
      (println (format "  %-23s [%-19s] | HV(on/off): %.5f / %.5f (diff=%.1e, match=%s) | Cache Hits: %6.0f (%5.1f%%) | ObjCalcs(on/off): %6.0f / %6.0f | Uncharged-Cache Inflation: %+.5f (+%.1f props)"
                       (name (:arm r))
                       (name (:generator-prior r))
                       (double (:mean-hv-cache-on r))
                       (double (:mean-hv-cache-off r))
                       (double (:max-hv-abs-diff r))
                       (str (:all-archives-identical? r))
                       (double (:mean-cache-hits-240-runs r))
                       (double (:cache-hit-rate-pct r))
                       (double (:mean-obj-calcs-cache-on r))
                       (double (:mean-obj-calcs-cache-off r))
                       (double (:mean-counterfactual-hv-inflation r))
                       (double (:mean-counterfactual-extra-proposals r))))))

  ;; Render publication-grade SVG: docs/figures/cache_ablation.svg
  (let [width 920
        height 540
        sb (StringBuilder.)
        port-v2 (filterv #(= :budget-aware-v2 (:generator-prior %)) (:portfolio domain-ablation))
        arms [[:gp-mixed "A1: GP-Mixed"]
              [:gp-mutation-only "A2: GP-Mutation"]
              [:random-dsl "A3: Random-DSL"]
              [:formula-only "A4: Formula-Only"]
              [:configured-conventional "A5: Conventional"]]]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"Inter, Segoe UI, Arial, sans-serif\">\n"
                     width height width height))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" rx=\"8\"/>\n" width height))
    (.append sb "  <text x=\"65\" y=\"34\" font-size=\"18\" font-weight=\"700\" fill=\"#0f172a\">Secondary Ablation: Inner-Loop Cache-On vs. Cache-Off &amp; Ledger Integrity</text>\n")
    (.append sb "  <text x=\"65\" y=\"55\" font-size=\"13\" fill=\"#475569\">Left: Physical Objective Evaluations Saved (:cache? true vs :cache? false, zero HV change). Right: Uncharged-Cache-Hit Counterfactual Inflation.</text>\n")

    ;; Left Panel: Portfolio Physical Objective Evaluations (Cache-Off vs Cache-On out of 61,440 proposals)
    (let [x0 65 y0 98 pw 375 ph 340
          y-max 65000.0
          py (fn [v] (+ y0 (- ph (* (/ (double v) y-max) ph))))
          col-w (/ (double pw) (count arms))]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"14\" font-weight=\"700\" fill=\"#0f172a\">A. Objective Evaluations per 240 Runs (Portfolio)</text>\n"
                       x0 (- y0 14)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1.2\"/>\n"
                       x0 y0 pw ph))
      (doseq [i (range 6)
              :let [yv (* (/ (double i) 5.0) y-max)
                    cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                         x0 cy (+ x0 pw) cy))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"10.5\" fill=\"#475569\" text-anchor=\"end\" dominant-baseline=\"middle\">%.0fk</text>\n"
                         (- x0 8) cy (/ yv 1000.0))))
      (doseq [[idx [arm label]] (map-indexed vector arms)
              :let [row (first (filter #(= arm (:arm %)) port-v2))
                    cx (+ x0 (* (+ idx 0.5) col-w))
                    bw 22.0
                    c-off (:mean-obj-calcs-cache-off row)
                    c-on (:mean-obj-calcs-cache-on row)
                    pct (:cache-hit-rate-pct row)
                    y-off (py c-off)
                    y-on (py c-on)]]
        ;; Cache-Off bar (slate)
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"3\" fill=\"#94a3b8\" fill-opacity=\"0.85\" stroke=\"#475569\" stroke-width=\"1.2\"/>\n"
                         (- cx bw 2.0) y-off bw (- (+ y0 ph) y-off)))
        ;; Cache-On bar (emerald)
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"3\" fill=\"#10b981\" fill-opacity=\"0.88\" stroke=\"#047857\" stroke-width=\"1.2\"/>\n"
                         (+ cx 2.0) y-on bw (- (+ y0 ph) y-on)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"10\" font-weight=\"700\" fill=\"#047857\" text-anchor=\"middle\">-%.1f%%</text>\n"
                         cx (- (min y-off y-on) 7.0) pct))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"10.5\" font-weight=\"600\" fill=\"#1e293b\" text-anchor=\"middle\">%s</text>\n"
                         cx (+ y0 ph 20) (svg-escape label)))))

    ;; Right Panel: Charged Proposal Budget vs Uncharged-Cache-Hit Counterfactual (Portfolio HV)
    (let [x0 505 y0 98 pw 375 ph 340
          y-min 0.62 y-max 0.67
          y-span (- y-max y-min)
          py (fn [v] (+ y0 (- ph (* (/ (- (double v) y-min) y-span) ph))))
          col-w (/ (double pw) (count arms))]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"14\" font-weight=\"700\" fill=\"#0f172a\">B. Charged Ledger vs. Uncharged-Cache Loophole (HV)</text>\n"
                       x0 (- y0 14)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1.2\"/>\n"
                       x0 y0 pw ph))
      (doseq [i (range 6)
              :let [yv (+ y-min (* (/ (double i) 5.0) y-span))
                    cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                         x0 cy (+ x0 pw) cy))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"10.5\" fill=\"#475569\" text-anchor=\"end\" dominant-baseline=\"middle\">%.3f</text>\n"
                         (- x0 8) cy yv)))
      (doseq [[idx [arm label]] (map-indexed vector arms)
              :let [row (first (filter #(= arm (:arm %)) port-v2))
                    cx (+ x0 (* (+ idx 0.5) col-w))
                    bw 22.0
                    hv-strict (:mean-hv-cache-on row)
                    hv-free (:mean-counterfactual-uncharged-hv row)
                    infl (:mean-counterfactual-hv-inflation row)
                    y1 (py hv-strict)
                    y2 (py hv-free)]]
        ;; Strict ledger bar (emerald — identical for Cache-On and Cache-Off)
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"3\" fill=\"#10b981\" fill-opacity=\"0.88\" stroke=\"#047857\" stroke-width=\"1.2\"/>\n"
                         (- cx bw 2.0) y1 bw (- (+ y0 ph) y1)))
        ;; Uncharged cache loophole bar (amber/red)
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"3\" fill=\"#f59e0b\" fill-opacity=\"0.85\" stroke=\"#b45309\" stroke-width=\"1.2\"/>\n"
                         (+ cx 2.0) y2 bw (- (+ y0 ph) y2)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"10\" font-weight=\"700\" fill=\"#b45309\" text-anchor=\"middle\">%+.4f</text>\n"
                         cx (- (min y1 y2) 7.0) infl))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"10.5\" font-weight=\"600\" fill=\"#1e293b\" text-anchor=\"middle\">%s</text>\n"
                         cx (+ y0 ph 20) (svg-escape label)))))

    ;; Bottom Legend
    (let [ly 482]
      (.append sb (fmt "  <rect x=\"65\" y=\"%d\" width=\"815\" height=\"40\" rx=\"6\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1\"/>\n" ly))
      (.append sb (fmt "  <rect x=\"82\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#94a3b8\" stroke=\"#475569\"/>\n" (+ ly 13)))
      (.append sb (fmt "  <text x=\"102\" y=\"%d\" font-size=\"11.5\" fill=\"#1e293b\" dominant-baseline=\"middle\">Cache-Off (:cache? false)</text>\n" (+ ly 21)))
      (.append sb (fmt "  <rect x=\"272\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#10b981\" stroke=\"#047857\"/>\n" (+ ly 13)))
      (.append sb (fmt "  <text x=\"292\" y=\"%d\" font-size=\"11.5\" font-weight=\"600\" fill=\"#064e3b\" dominant-baseline=\"middle\">Cache-On (:cache? true, |Delta HV| = 0.00000 exact match)</text>\n" (+ ly 21)))
      (.append sb (fmt "  <rect x=\"635\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#f59e0b\" stroke=\"#b45309\"/>\n" (+ ly 13)))
      (.append sb (fmt "  <text x=\"655\" y=\"%d\" font-size=\"11.5\" fill=\"#78350f\" dominant-baseline=\"middle\">Uncharged-Cache Counterfactual</text>\n" (+ ly 21))))
    (.append sb "</svg>\n")
    (spit "docs/figures/cache_ablation.svg" (.toString sb) :encoding "UTF-8")
    (println "Wrote SVG: docs/figures/cache_ablation.svg"))
  (shutdown-agents))
