;; Secondary Mechanism Ablation (§12.2 & §12.3):
;; Initializer/Body Deletion, Score-Blind, Constant-Neutralization, Progress-Annealing Freeze,
;; and Single-Objective Collapse Knockouts on the Evolved DSL Programs.
;; Saves full evidence to docs/evidence/ast-knockout-ablation-results.edn and generates
;; docs/figures/ast_knockout_ablation.svg.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-runtime :as port-runtime]
         '[niki.optimizer.portfolio-data :as port-data]
         '[niki.optimizer.scheduling :as scheduling]
         '[niki.optimizer.scheduling-runtime :as sched-runtime]
         '[niki.optimizer.dsl :as dsl]
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

;; --- Surgical AST Knockout Transformations ---

(defn knockout-body-deleted
  "K1: Keep only the initial solution (replace search body with [:noop] or single [:initialize])."
  [program]
  (let [domain (second program)
        body (get-in program [2 1])]
    (if (and (vector? body) (= :seq (first body))
             (vector? (second body)) (= :initialize (first (second body))))
      [:program domain [:budget [:seq (second body) [:noop]]]]
      [:program domain [:budget [:initialize [:initializer :random]]]])))

(defn knockout-initializer-to-random
  "K2: Replace any [:initializer :first] with [:initializer :random]."
  [program]
  (walk/postwalk
   (fn [x]
     (if (= x [:initializer :first])
       [:initializer :random]
       x))
   program))

(defn knockout-score-blind
  "K3: Replace the score expression in every [:search count op score] with [:constant 0.0]."
  [program]
  (walk/postwalk
   (fn [x]
     (if (and (vector? x) (= :search (first x)) (= 4 (count x)))
       [:search (nth x 1) (nth x 2) [:constant 0.0]]
       x))
   program))

(defn knockout-constants-to-one
  "K4: Replace every [:constant c] leaf with [:constant 1.0]."
  [program]
  (walk/postwalk
   (fn [x]
     (if (and (vector? x) (= :constant (first x)))
       [:constant 1.0]
       x))
   program))

(defn knockout-freeze-progress
  "K5: Replace every [:feature :progress] leaf with a static midpoint [:constant 0.5]."
  [program]
  (walk/postwalk
   (fn [x]
     (if (= x [:feature :progress])
       [:constant 0.5]
       x))
   program))

(defn knockout-single-objective
  "K6: Collapse multi-objective scoring by replacing [:feature :objective-1] with [:feature :objective-0]."
  [program]
  (walk/postwalk
   (fn [x]
     (if (= x [:feature :objective-1])
       [:feature :objective-0]
       x))
   program))

(def knockout-specs
  [[:K0-intact             "Intact Evolved AST"           identity]
   [:K1-body-deleted       "K1: Search Body Deleted"      knockout-body-deleted]
   [:K2-init-random        "K2: Initializer -> :random"   knockout-initializer-to-random]
   [:K3-score-blind        "K3: Score -> 0.0 (Blind)"     knockout-score-blind]
   [:K4-constants-unit     "K4: Constants -> 1.0"         knockout-constants-to-one]
   [:K5-progress-frozen    "K5: :progress -> 0.5"         knockout-freeze-progress]
   [:K6-single-objective   "K6: Obj-0 Only (No Obj-1)"    knockout-single-objective]])

(let [started-ns (System/nanoTime)
      _ (println "Loading real-data G8 results (seeds 0..1) and preparing test splits...")
      two-rep (edn/read-string (slurp "docs/evidence/g8-thirty-replications-results.edn"))
      port-raw (port-data/load-nasdaq-suite)
      sched-raw (scheduling/load-orlib-wt-suite)
      test-splits {:portfolio (prepare-portfolio-split (:test port-raw))
                   :scheduling (prepare-scheduling-split (:test sched-raw))}
      test-seeds (vec (range 1001 1011))
      dsl-arms #{:gp-mixed :gp-mutation-only :random-dsl :formula-only}
      domain-results
      (into {}
            (for [domain [:portfolio :scheduling]]
              (let [_ (println "Running AST Knockout & Constant/Progress Ablations for domain:" domain)
                    test-tasks (get test-splits domain)
                    summaries (->> (get-in two-rep [:domains domain :arm-summaries])
                                   (filterv #(and (= :budget-aware-v2 (:generator-prior %))
                                                  (contains? dsl-arms (:arm %))))
                                   (mapv (fn [s]
                                           (let [rs (vec (take 2 (:replications s)))
                                                 hvs (mapv :test-mean-normalized-hv rs)]
                                             (assoc s
                                                    :replications rs
                                                    :replication-test-hvs hvs
                                                    :mean-test-hv (/ (reduce + 0.0 hvs) (double (count hvs))))))))
                    ;; Cache evaluations by canonical program-id so identical knocked ASTs are evaluated once
                    eval-cache (atom {})
                    eval-ast!
                    (fn [arm ast intact-ast intact-hv]
                      (let [checked (:program (dsl/check-program ast))
                            pid (dsl/program-id checked)]
                        (cond
                          (= checked intact-ast)
                          {:hv intact-hv :changed-ast? false :program checked :program-id pid}

                          (contains? @eval-cache pid)
                          (assoc (get @eval-cache pid) :changed-ast? true)

                          :else
                          (let [ev (search/assess-candidate
                                    arm checked pid test-tasks test-seeds
                                    {:solution-budget 256 :step-budget 4096 :cache? true})
                                res {:hv (:mean-normalized-hv ev)
                                     :changed-ast? true
                                     :program checked
                                     :program-id pid}]
                            (swap! eval-cache assoc pid res)
                            res))))
                    arm-rows
                    (vec
                     (pmap
                      (fn [summary]
                        (let [arm (:arm summary)
                              rep-knockouts
                              (mapv
                               (fn [rep]
                                 (let [intact-ast (:program (dsl/check-program (get-in rep [:selected :candidate])))
                                       intact-hv (double (:test-mean-normalized-hv rep))
                                       ks (into {}
                                                (map (fn [[k-id k-label k-fn]]
                                                       (let [k-ast (k-fn intact-ast)
                                                             res (eval-ast! arm k-ast intact-ast intact-hv)]
                                                         [k-id {:id k-id
                                                                :label k-label
                                                                :hv (:hv res)
                                                                :delta-hv (- (double (:hv res)) intact-hv)
                                                                :changed-ast? (:changed-ast? res)
                                                                :program (:program res)}]))
                                                     knockout-specs))]
                                   {:run-seed (:run-seed rep)
                                    :intact-hv intact-hv
                                    :intact-ast intact-ast
                                    :knockouts ks}))
                               (:replications summary))
                              mean-knockouts
                              (mapv (fn [[k-id k-label _]]
                                      (let [hvs (mapv #(get-in % [:knockouts k-id :hv]) rep-knockouts)
                                            deltas (mapv #(get-in % [:knockouts k-id :delta-hv]) rep-knockouts)
                                            changed-cnt (count (filter #(get-in % [:knockouts k-id :changed-ast?]) rep-knockouts))]
                                        {:id k-id
                                         :label k-label
                                         :mean-hv (/ (reduce + 0.0 hvs) (double (count hvs)))
                                         :mean-delta-hv (/ (reduce + 0.0 deltas) (double (count deltas)))
                                         :rep-hvs hvs
                                         :changed-replications changed-cnt}))
                                    knockout-specs)]
                          {:arm arm
                           :mean-intact-hv (:mean-test-hv summary)
                           :knockout-summaries mean-knockouts
                           :replications rep-knockouts}))
                      summaries))]
                [domain arm-rows])))
      total-s (/ (double (- (System/nanoTime) started-ns)) 1.0e9)
      out-data {:experiment :ast-knockout-and-constant-ablation
                :executed-at (str (Instant/now))
                :total-elapsed-seconds total-s
                :domains domain-results}]
  (io/make-parents "docs/evidence/ast-knockout-ablation-results.edn")
  (spit "docs/evidence/ast-knockout-ablation-results.edn" (str (pr-str out-data) "\n") :encoding "UTF-8")
  (println "\nCompleted AST Knockout & Constant Ablation in" (format "%.2f" total-s) "seconds.")
  (doseq [domain [:portfolio :scheduling]]
    (println "\n=========================================================================================================")
    (println "DOMAIN:" domain "— Surgical AST Knockouts (Mean HV and Delta from Intact Evolved Program)")
    (println "=========================================================================================================")
    (doseq [r (get domain-results domain)]
      (println (format "\n  Arm: %s (Intact Mean HV = %.5f)" (name (:arm r)) (double (:mean-intact-hv r))))
      (doseq [k (:knockout-summaries r)]
        (println (format "    %-28s | Mean HV: %.5f | Delta HV: %+.5f | Rep0: %.5f, Rep1: %.5f | Active in %d/2 reps"
                         (:label k)
                         (double (:mean-hv k))
                         (double (:mean-delta-hv k))
                         (double (first (:rep-hvs k)))
                         (double (second (:rep-hvs k)))
                         (long (:changed-replications k)))))))

  ;; Render publication-grade SVG: docs/figures/ast_knockout_ablation.svg
  (let [width 920
        height 550
        sb (StringBuilder.)
        render-panel
        (fn [x0 y0 pw ph title y-min y-max rows]
          (let [gp-mixed (first (filter #(= :gp-mixed (:arm %)) rows))
                gp-mut (first (filter #(= :gp-mutation-only (:arm %)) rows))
                ks (:knockout-summaries gp-mixed)
                ks-mut (:knockout-summaries gp-mut)
                y-span (- (double y-max) (double y-min))
                py (fn [v] (+ y0 (- ph (* (/ (- (double (max y-min v)) (double y-min)) y-span) ph))))
                col-w (/ (double pw) (count ks))
                short-labels ["K0: Intact" "K1: No Body" "K2: Rand Init" "K3: Blind Score" "K4: Const=1" "K5: No Prog" "K6: Obj0 Only"]]
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
            ;; Reference dashed line for Intact GP-Mixed
            (let [y-intact (py (:mean-intact-hv gp-mixed))]
              (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"#059669\" stroke-dasharray=\"4,4\" stroke-width=\"1.4\"/>\n"
                               x0 y-intact (+ x0 pw) y-intact)))
            ;; Bars for each knockout K0..K6
            (doseq [[idx k-m] (map-indexed vector ks)
                    :let [k-u (nth ks-mut idx)
                          cx (+ x0 (* (+ idx 0.5) col-w))
                          bw 16.0
                          v1 (:mean-hv k-m)
                          v2 (:mean-hv k-u)
                          y1 (py v1)
                          y2 (py v2)
                          d1 (:mean-delta-hv k-m)]]
              ;; GP-Mixed bar (emerald for K0, rose/amber for knockouts)
              (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"2.5\" fill=\"%s\" fill-opacity=\"0.88\" stroke=\"%s\" stroke-width=\"1.1\"/>\n"
                               (- cx bw 1.5) y1 bw (max 2.0 (- (+ y0 ph) y1))
                               (if (zero? idx) "#10b981" "#f43f5e")
                               (if (zero? idx) "#047857" "#be123c")))
              ;; GP-Mutation bar (sky-blue for K0, amber for knockouts)
              (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"2.5\" fill=\"%s\" fill-opacity=\"0.85\" stroke=\"%s\" stroke-width=\"1.1\"/>\n"
                               (+ cx 1.5) y2 bw (max 2.0 (- (+ y0 ph) y2))
                               (if (zero? idx) "#0ea5e9" "#f59e0b")
                               (if (zero? idx) "#0369a1" "#b45309")))
              ;; Delta label above bar
              (when (pos? idx)
                (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"9.5\" font-weight=\"700\" fill=\"#be123c\" text-anchor=\"middle\">%+.3f</text>\n"
                                 cx (- (min y1 y2) 6.0) d1)))
              ;; X-axis label
              (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"9.5\" font-weight=\"600\" fill=\"#1e293b\" text-anchor=\"middle\">%s</text>\n"
                               cx (+ y0 ph 18) (nth short-labels idx))))))]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"Inter, Segoe UI, Arial, sans-serif\">\n"
                     width height width height))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" rx=\"8\"/>\n" width height))
    (.append sb "  <text x=\"65\" y=\"34\" font-size=\"18\" font-weight=\"700\" fill=\"#0f172a\">Secondary Ablation: Surgical AST Knockouts (Body, Initializer &amp; Score Terms)</text>\n")
    (.append sb "  <text x=\"65\" y=\"55\" font-size=\"13\" fill=\"#475569\">Held-out Test Normalized HV across 6 structural/formula knockouts on evolved A1 (:gp-mixed) and A2 (:gp-mutation-only) winners</text>\n")
    (render-panel 65 95 380 340 "A. Portfolio Selection (N=50, K=5, T=365)" 0.45 0.67 (:portfolio domain-results))
    (render-panel 505 95 380 340 "B. Single-Machine Scheduling (30 Jobs)" 0.44 0.565 (:scheduling domain-results))
    ;; Legend
    (let [ly 482]
      (.append sb (fmt "  <rect x=\"65\" y=\"%d\" width=\"820\" height=\"42\" rx=\"6\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1\"/>\n" ly))
      (.append sb (fmt "  <rect x=\"85\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#10b981\" stroke=\"#047857\"/>\n" (+ ly 14)))
      (.append sb (fmt "  <text x=\"105\" y=\"%d\" font-size=\"11.5\" font-weight=\"600\" fill=\"#064e3b\" dominant-baseline=\"middle\">K0: Intact A1 (:gp-mixed)</text>\n" (+ ly 21)))
      (.append sb (fmt "  <rect x=\"285\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#f43f5e\" stroke=\"#be123c\"/>\n" (+ ly 14)))
      (.append sb (fmt "  <text x=\"305\" y=\"%d\" font-size=\"11.5\" fill=\"#1e293b\" dominant-baseline=\"middle\">Knockout on A1 (:gp-mixed, label = Delta HV)</text>\n" (+ ly 21)))
      (.append sb (fmt "  <rect x=\"585\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#f59e0b\" stroke=\"#b45309\"/>\n" (+ ly 14)))
      (.append sb (fmt "  <text x=\"605\" y=\"%d\" font-size=\"11.5\" fill=\"#1e293b\" dominant-baseline=\"middle\">Knockout on A2 (:gp-mutation-only)</text>\n" (+ ly 21))))
    (.append sb "</svg>\n")
    (spit "docs/figures/ast_knockout_ablation.svg" (.toString sb) :encoding "UTF-8")
    (println "Wrote SVG: docs/figures/ast_knockout_ablation.svg"))
  (shutdown-agents))
