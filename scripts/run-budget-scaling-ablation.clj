;; Secondary Mechanism Ablation: Proposal-Budget Scaling & Anytime Trajectory
;; Evaluates all selected representatives from the 2-outer-replication run across
;; inner proposal budgets B in [16, 32, 64, 128, 256] on the 24 held-out test tasks x 10 inner seeds
;; in both :portfolio (N=50, K=5, T=365) and :scheduling (30 jobs), plus direct controls.
;; Saves full evidence to docs/evidence/budget-scaling-ablation-results.edn and generates
;; docs/figures/budget_scaling_ablation.svg.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-runtime :as port-runtime]
         '[niki.optimizer.portfolio-data :as port-data]
         '[niki.optimizer.scheduling :as scheduling]
         '[niki.optimizer.scheduling-runtime :as sched-runtime]
         '[niki.optimizer.pareto :as pareto]
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

(defn evaluate-control-at-budget [control-kind test-tasks inner-seeds budget]
  (let [task-means
        (mapv (fn [{:keys [adapter]}]
                (let [seed-hvs
                      (mapv (fn [s]
                              (let [res (baselines/run-direct-control
                                         control-kind adapter
                                         {:solution-budget (long budget)
                                          :seed (long s)
                                          :cache? true})]
                                (pareto/normalized-hypervolume (:archive res) (:normalization adapter))))
                            inner-seeds)]
                  (/ (reduce + 0.0 seed-hvs) (double (count seed-hvs)))))
              test-tasks)]
    (/ (reduce + 0.0 task-means) (double (count task-means)))))

(let [started-ns (System/nanoTime)
      _ (println "Loading real-data G8 results (seeds 0..1) and preparing test splits...")
      two-rep (edn/read-string (slurp "docs/evidence/g8-thirty-replications-results.edn"))
      port-raw (port-data/load-nasdaq-suite)
      sched-raw (scheduling/load-orlib-wt-suite)
      test-splits {:portfolio (prepare-portfolio-split (:test port-raw))
                   :scheduling (prepare-scheduling-split (:test sched-raw))}
      test-seeds (vec (range 1001 1011))
      budgets [16 32 64 128 256]
      domain-results
      (into {}
            (for [domain [:portfolio :scheduling]]
              (let [_ (println "Running Proposal-Budget Scaling [16, 32, 64, 128, 256] for domain:" domain)
                    test-tasks (get test-splits domain)
                    summaries (->> (get-in two-rep [:domains domain :arm-summaries])
                                   (mapv (fn [s]
                                           (let [rs (vec (take 2 (:replications s)))
                                                 hvs (mapv :test-mean-normalized-hv rs)]
                                             (assoc s
                                                    :replications rs
                                                    :replication-test-hvs hvs
                                                    :mean-test-hv (/ (reduce + 0.0 hvs) (double (count hvs))))))))
                    arm-rows
                    (vec
                     (pmap
                      (fn [summary]
                        (let [arm (:arm summary)
                              prior (:generator-prior summary)
                              rep-curves
                              (mapv
                               (fn [rep]
                                 (let [rseed (:run-seed rep)
                                       cand (get-in rep [:selected :candidate])
                                       cid (get-in rep [:selected :candidate-id])
                                       by-budget
                                       (into {}
                                             (map (fn [b]
                                                    (if (and (= b 256) (get-in rep [:test-totals :proposal-attempts]))
                                                      [256 {:budget 256
                                                            :mean-hv (:test-mean-normalized-hv rep)
                                                            :proposals-used (/ (double (get-in rep [:test-totals :proposal-attempts])) 240.0)}]
                                                      (let [ev (search/assess-candidate
                                                                arm cand cid test-tasks test-seeds
                                                                {:solution-budget b :step-budget 4096 :cache? true})]
                                                        [b {:budget b
                                                            :mean-hv (:mean-normalized-hv ev)
                                                            :proposals-used (/ (double (get-in ev [:totals :proposal-attempts])) 240.0)}])))
                                                  budgets))]
                                   {:run-seed rseed
                                    :candidate-id cid
                                    :by-budget by-budget}))
                               (:replications summary))
                              mean-by-budget
                              (into {}
                                    (map (fn [b]
                                           (let [hvs (mapv #(get-in % [:by-budget b :mean-hv]) rep-curves)
                                                 props (mapv #(get-in % [:by-budget b :proposals-used]) rep-curves)]
                                             [b {:budget b
                                                 :mean-hv (/ (reduce + 0.0 hvs) (double (count hvs)))
                                                 :rep-hvs hvs
                                                 :mean-proposals-used (/ (reduce + 0.0 props) (double (count props)))}]))
                                         budgets))]
                          {:arm arm
                           :generator-prior prior
                           :by-budget mean-by-budget
                           :replications rep-curves}))
                      summaries))
                    ctrl-rows
                    (vec
                     (pmap
                      (fn [ctrl]
                        {:control ctrl
                         :by-budget (into {}
                                          (map (fn [b]
                                                 [b {:budget b
                                                     :mean-hv (evaluate-control-at-budget ctrl test-tasks test-seeds b)}])
                                               budgets))})
                      [:random-search :heuristic-local-search]))]
                [domain {:arm-curves arm-rows
                         :control-curves ctrl-rows}])))
      total-s (/ (double (- (System/nanoTime) started-ns)) 1.0e9)
      out-data {:experiment :proposal-budget-scaling-ablation
                :budgets budgets
                :executed-at (str (Instant/now))
                :total-elapsed-seconds total-s
                :domains domain-results}]
  (io/make-parents "docs/evidence/budget-scaling-ablation-results.edn")
  (spit "docs/evidence/budget-scaling-ablation-results.edn" (str (pr-str out-data) "\n") :encoding "UTF-8")
  (println "\nCompleted Proposal-Budget Scaling ablation in" (format "%.2f" total-s) "seconds.")
  (doseq [domain [:portfolio :scheduling]]
    (println "\n=========================================================================================================")
    (println "DOMAIN:" domain "— Mean Held-Out Test Normalized HV by Proposal Budget [16, 32, 64, 128, 256]")
    (println "=========================================================================================================")
    (doseq [r (get-in domain-results [domain :arm-curves])]
      (println (format "  %-23s [%-19s] | B=16: %.5f | B=32: %.5f | B=64: %.5f | B=128: %.5f | B=256: %.5f | Gain(16->256): %+.5f"
                       (name (:arm r))
                       (name (:generator-prior r))
                       (double (get-in r [:by-budget 16 :mean-hv]))
                       (double (get-in r [:by-budget 32 :mean-hv]))
                       (double (get-in r [:by-budget 64 :mean-hv]))
                       (double (get-in r [:by-budget 128 :mean-hv]))
                       (double (get-in r [:by-budget 256 :mean-hv]))
                       (- (double (get-in r [:by-budget 256 :mean-hv]))
                          (double (get-in r [:by-budget 16 :mean-hv]))))))
    (doseq [c (get-in domain-results [domain :control-curves])]
      (println (format "  Control %-15s [direct-control     ] | B=16: %.5f | B=32: %.5f | B=64: %.5f | B=128: %.5f | B=256: %.5f | Gain(16->256): %+.5f"
                       (name (:control c))
                       (double (get-in c [:by-budget 16 :mean-hv]))
                       (double (get-in c [:by-budget 32 :mean-hv]))
                       (double (get-in c [:by-budget 64 :mean-hv]))
                       (double (get-in c [:by-budget 128 :mean-hv]))
                       (double (get-in c [:by-budget 256 :mean-hv]))
                       (- (double (get-in c [:by-budget 256 :mean-hv]))
                          (double (get-in c [:by-budget 16 :mean-hv])))))))

  ;; Render publication-grade SVG: docs/figures/budget_scaling_ablation.svg
  (let [width 920
        height 540
        sb (StringBuilder.)
        render-panel
        (fn [x0 y0 pw ph title y-min y-max domain-map]
          (let [arm-curves (:arm-curves domain-map)
                ctrl-curves (:control-curves domain-map)
                find-arm (fn [arm prior]
                           (first (filter #(and (= arm (:arm %)) (= prior (:generator-prior %))) arm-curves)))
                find-ctrl (fn [ctrl]
                            (first (filter #(= ctrl (:control %)) ctrl-curves)))
                y-span (- (double y-max) (double y-min))
                py (fn [v] (+ y0 (- ph (* (/ (- (double v) (double y-min)) y-span) ph))))
                ;; Log2 x-scale for [16, 32, 64, 128, 256] -> indices 0, 1, 2, 3, 4
                px (fn [b]
                     (let [idx (/ (- (/ (Math/log (double b)) (Math/log 2.0)) 4.0) 4.0)]
                       (+ x0 28.0 (* idx (- pw 56.0)))))
                series
                [{:label "A1: GP-Mixed (v2)" :color "#10b981" :dash nil :width 2.8
                  :data (find-arm :gp-mixed :budget-aware-v2)}
                 {:label "A2: GP-Mutation (v2)" :color "#0ea5e9" :dash nil :width 2.4
                  :data (find-arm :gp-mutation-only :budget-aware-v2)}
                 {:label "A4: Formula-Only" :color "#6366f1" :dash nil :width 2.2
                  :data (find-arm :formula-only :budget-aware-v2)}
                 {:label "A5: Conventional" :color "#8b5cf6" :dash "5,3" :width 2.2
                  :data (find-arm :configured-conventional :budget-aware-v2)}
                 {:label "A1: GP-Mixed (v1 naive)" :color "#f59e0b" :dash "4,4" :width 2.2
                  :data (find-arm :gp-mixed :development-only-v1)}
                 {:label "Direct: Random Search" :color "#64748b" :dash "2,3" :width 1.8
                  :data (find-ctrl :random-search)}]]
            (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"14.5\" font-weight=\"700\" fill=\"#0f172a\">%s</text>\n"
                             x0 (- y0 14) (svg-escape title)))
            (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1.2\"/>\n"
                             x0 y0 pw ph))
            ;; Y-axis grid
            (doseq [i (range 6)
                    :let [yv (+ y-min (* (/ (double i) 5.0) y-span))
                          cy (py yv)]]
              (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                               x0 cy (+ x0 pw) cy))
              (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"10.5\" fill=\"#475569\" text-anchor=\"end\" dominant-baseline=\"middle\">%.3f</text>\n"
                               (- x0 8) cy yv)))
            ;; X-axis grid for [16, 32, 64, 128, 256]
            (doseq [b budgets
                    :let [cx (px b)]]
              (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%d\" x2=\"%.2f\" y2=\"%d\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                               cx y0 cx (+ y0 ph)))
              (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"11\" font-weight=\"600\" fill=\"#334155\" text-anchor=\"middle\">%d</text>\n"
                               cx (+ y0 ph 20) b)))
            (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"11.5\" font-weight=\"600\" fill=\"#475569\" text-anchor=\"middle\">Inner Solution Proposal Budget (log2 scale)</text>\n"
                             (+ x0 (/ pw 2.0)) (+ y0 ph 40)))
            ;; Plot lines and markers
            (doseq [{:keys [color dash width data]} series
                    :when data
                    :let [pts (mapv (fn [b] [(px b) (py (get-in data [:by-budget b :mean-hv]))]) budgets)
                          poly (str/join " " (map (fn [[x y]] (fmt "%.2f,%.2f" x y)) pts))]]
              (.append sb (fmt "  <polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\"%s/>\n"
                               poly color (double width)
                               (if dash (fmt " stroke-dasharray=\"%s\"" dash) "")))
              (doseq [[x y] pts]
                (.append sb (fmt "  <circle cx=\"%.2f\" cy=\"%.2f\" r=\"3.8\" fill=\"%s\" stroke=\"#ffffff\" stroke-width=\"1.2\"/>\n"
                                 x y color))))))]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"Inter, Segoe UI, Arial, sans-serif\">\n"
                     width height width height))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" rx=\"8\"/>\n" width height))
    (.append sb "  <text x=\"65\" y=\"34\" font-size=\"18\" font-weight=\"700\" fill=\"#0f172a\">Secondary Ablation: Inner Proposal-Budget Scaling (16 to 256 Proposals)</text>\n")
    (.append sb "  <text x=\"65\" y=\"55\" font-size=\"13\" fill=\"#475569\">Anytime generalization of frozen 256-proposal representatives evaluated across B in {16, 32, 64, 128, 256} (24 test tasks x 10 seeds)</text>\n")
    (render-panel 65 95 375 330 "A. Portfolio Selection (N=50, K=5, T=365)" 0.36 0.67 (:portfolio domain-results))
    (render-panel 505 95 375 330 "B. Single-Machine Scheduling (30 Jobs)" 0.40 0.565 (:scheduling domain-results))
    ;; Legend
    (let [ly 480]
      (.append sb (fmt "  <rect x=\"65\" y=\"%d\" width=\"815\" height=\"42\" rx=\"6\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1\"/>\n" ly))
      (doseq [[idx [lbl col]] (map-indexed vector
                                           [["A1: GP-Mixed (v2)" "#10b981"]
                                            ["A2: GP-Mutation (v2)" "#0ea5e9"]
                                            ["A4: Formula-Only" "#6366f1"]
                                            ["A5: Conventional" "#8b5cf6"]
                                            ["A1: GP-Mixed (v1)" "#f59e0b"]
                                            ["Random Search" "#64748b"]])
              :let [lx (+ 80 (* idx 134))]]
        (.append sb (fmt "  <circle cx=\"%d\" cy=\"%d\" r=\"4.5\" fill=\"%s\"/>\n" lx (+ ly 21) col))
        (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"11\" font-weight=\"600\" fill=\"#1e293b\" dominant-baseline=\"middle\">%s</text>\n"
                         (+ lx 10) (+ ly 21) lbl))))
    (.append sb "</svg>\n")
    (spit "docs/figures/budget_scaling_ablation.svg" (.toString sb) :encoding "UTF-8")
    (println "Wrote SVG: docs/figures/budget_scaling_ablation.svg"))
  (shutdown-agents))
