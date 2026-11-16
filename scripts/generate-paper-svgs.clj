;; Generate publication-grade SVG figures for the paper:
;; 1. docs/figures/portfolio_pareto_front.svg
;;    Shows dominated candidate solutions, initial random solutions, shaded dominated
;;    hypervolume, and the nondominated Pareto front on portfolio selection.
;; 2. docs/figures/scheduling_pareto_front.svg
;;    Shows initial random schedules, dominated schedule cloud, SPT/EDD heuristics,
;;    and the evolved Pareto front on 30-job single-machine scheduling.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[niki.optimizer.portfolio :as portfolio]
         '[niki.optimizer.portfolio-reference :as port-ref]
         '[niki.optimizer.portfolio-runtime :as port-runtime]
         '[niki.optimizer.scheduling :as scheduling]
         '[niki.optimizer.scheduling-runtime :as sched-runtime]
         '[niki.optimizer.pareto :as pareto]
         '[niki.optimizer.runtime :as runtime])
(import '[java.util Random Locale])

(defn fmt [pattern & args]
  (String/format Locale/US pattern (to-array args)))

(defn svg-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn monotone-cubic-svg-path
  "Build a smooth, overshoot-free Fritsch-Carlson monotone cubic Bezier SVG path
   through a sequence of screen coordinates [[x0 y0] [x1 y1] ...] sorted by increasing x."
  [pts]
  (let [pts (vec pts)
        n (count pts)]
    (cond
      (zero? n) nil
      (== 1 n) (let [[x y] (first pts)] (fmt "M %.2f,%.2f" x y))
      (== 2 n) (let [[[x0 y0] [x1 y1]] pts] (fmt "M %.2f,%.2f L %.2f,%.2f" x0 y0 x1 y1))
      :else
      (let [dxs (mapv (fn [i]
                        (max 1.0e-6 (- (double (first (nth pts (inc i))))
                                       (double (first (nth pts i))))))
                      (range (dec n)))
            slopes (mapv (fn [i]
                           (/ (- (double (second (nth pts (inc i))))
                                 (double (second (nth pts i))))
                              (nth dxs i)))
                         (range (dec n)))
            tangents
            (mapv (fn [i]
                    (cond
                      (zero? i) (first slopes)
                      (== i (dec n)) (peek slopes)
                      :else
                      (let [s0 (nth slopes (dec i))
                            s1 (nth slopes i)]
                        (if (<= (* s0 s1) 0.0)
                          0.0
                          (let [m (* 0.5 (+ s0 s1))
                                cap (* 3.0 (min (Math/abs s0) (Math/abs s1)))]
                            (if (pos? m) (min m cap) (max m (- cap))))))))
                  (range n))
            [x0 y0] (first pts)
            segs
            (mapv (fn [i]
                    (let [[xk yk] (nth pts i)
                          [xk1 yk1] (nth pts (inc i))
                          dx (nth dxs i)
                          mk (nth tangents i)
                          mk1 (nth tangents (inc i))
                          cx1 (+ (double xk) (/ dx 3.0))
                          cy1 (+ (double yk) (/ (* mk dx) 3.0))
                          cx2 (- (double xk1) (/ dx 3.0))
                          cy2 (- (double yk1) (/ (* mk1 dx) 3.0))]
                      (fmt "C %.2f,%.2f %.2f,%.2f %.2f,%.2f" cx1 cy1 cx2 cy2 (double xk1) (double yk1))))
                  (range (dec n)))]
        (str (fmt "M %.2f,%.2f " (double x0) (double y0))
             (str/join " " segs))))))

(defn render-pareto-svg
  "Render a publication-grade 2-D Pareto front SVG showing:
   - Shaded hypervolume polygon from Pareto front to reference corner
   - Dominated solutions (slate-grey circles)
   - Initial random solutions (amber circles with dark stroke)
   - Nondominated Pareto front staircase & markers (emerald/crimson)
   - Optional reference heuristic markers."
  [{:keys [title subtitle x-label y-label
           dominated-points initial-points pareto-points extra-markers
           x-range y-range ref-point hv-text out-path]}]
  (let [width 860
        height 580
        pad-l 95
        pad-r 45
        pad-t 78
        pad-b 82
        plot-w (- width pad-l pad-r)
        plot-h (- height pad-t pad-b)
        [x-min x-max] x-range
        [y-min y-max] y-range
        x-span (max 1.0e-9 (- (double x-max) (double x-min)))
        y-span (max 1.0e-9 (- (double y-max) (double y-min)))
        px (fn [x] (+ pad-l (* (/ (- (double x) (double x-min)) x-span) plot-w)))
        ;; SVG y goes top-to-bottom; lower objective values are better (bottom-left is ideal!)
        py (fn [y] (+ pad-t (- plot-h (* (/ (- (double y) (double y-min)) y-span) plot-h))))
        sorted-front (vec (sort-by first pareto-points))
        [rx ry] ref-point
        ;; Build staircase polygon for shaded dominated hypervolume up to ref-point
        stair-coords
        (when (seq sorted-front)
          (let [first-pt (first sorted-front)
                steps (loop [idx 0
                             acc [[(px (first first-pt)) (py ry)]
                                  [(px (first first-pt)) (py (second first-pt))]]]
                        (if (= idx (dec (count sorted-front)))
                          (let [last-pt (peek sorted-front)]
                            (conj acc
                                  [(px rx) (py (second last-pt))]
                                  [(px rx) (py ry)]))
                          (let [cur (nth sorted-front idx)
                                nxt (nth sorted-front (inc idx))]
                            (recur (inc idx)
                                   (conj acc
                                         [(px (first nxt)) (py (second cur))]
                                         [(px (first nxt)) (py (second nxt))])))))]
            (str/join " " (map (fn [[x y]] (fmt "%.2f,%.2f" x y)) steps))))
        ;; Smooth monotone cubic Bezier curve through the Pareto frontier points
        smooth-front-path
        (when (seq sorted-front)
          (monotone-cubic-svg-path
           (mapv (fn [[x y]] [(px x) (py y)]) sorted-front)))
        x-ticks (mapv (fn [i] (+ x-min (* (/ (double i) 5.0) x-span))) (range 6))
        y-ticks (mapv (fn [i] (+ y-min (* (/ (double i) 5.0) y-span))) (range 6))
        sb (StringBuilder.)]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"Inter, Segoe UI, Arial, sans-serif\">\n"
                     width height width height))
    ;; Background
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" rx=\"8\"/>\n" width height))
    ;; Title & Subtitle
    (.append sb (fmt "  <text x=\"%d\" y=\"34\" font-size=\"18\" font-weight=\"700\" fill=\"#0f172a\">%s</text>\n"
                     pad-l (svg-escape title)))
    (.append sb (fmt "  <text x=\"%d\" y=\"56\" font-size=\"13\" fill=\"#475569\">%s</text>\n"
                     pad-l (svg-escape subtitle)))
    ;; Plot area background
    (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1.2\"/>\n"
                     pad-l pad-t plot-w plot-h))
    ;; Grid lines & ticks
    (doseq [xt x-ticks
            :let [cx (px xt)]]
      (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%d\" x2=\"%.2f\" y2=\"%d\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                       cx pad-t cx (+ pad-t plot-h)))
      (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"11\" fill=\"#475569\" text-anchor=\"middle\">%s</text>\n"
                       cx (+ pad-t plot-h 20)
                       (if (< x-span 1.0) (fmt "%.4f" xt) (fmt "%.1f" xt)))))
    (doseq [yt y-ticks
            :let [cy (py yt)]]
      (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                       pad-l cy (+ pad-l plot-w) cy))
      (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"11\" fill=\"#475569\" text-anchor=\"end\" dominant-baseline=\"middle\">%s</text>\n"
                       (- pad-l 10) cy
                       (if (< y-span 1.0) (fmt "%.4f" yt) (fmt "%.1f" yt)))))
    ;; Shaded hypervolume region (bounded by faint dashed orthogonal dominance staircase)
    (when stair-coords
      (.append sb (fmt "  <polygon points=\"%s\" fill=\"#10b981\" fill-opacity=\"0.12\" stroke=\"#059669\" stroke-opacity=\"0.45\" stroke-dasharray=\"4,4\" stroke-width=\"1.3\"/>\n"
                       stair-coords)))
    ;; Dominated candidate cloud (grey/slate circles)
    (doseq [[x y] dominated-points]
      (.append sb (fmt "  <circle cx=\"%.2f\" cy=\"%.2f\" r=\"3.2\" fill=\"#94a3b8\" fill-opacity=\"0.42\"/>\n"
                       (px x) (py y))))
    ;; Initial random solutions (amber/orange circles)
    (doseq [[x y] initial-points]
      (.append sb (fmt "  <circle cx=\"%.2f\" cy=\"%.2f\" r=\"5.2\" fill=\"#f59e0b\" fill-opacity=\"0.90\" stroke=\"#78350f\" stroke-width=\"1.3\"/>\n"
                       (px x) (py y))))
    ;; Smooth monotone cubic Bezier trade-off curve through the Pareto frontier
    (when smooth-front-path
      (.append sb (fmt "  <path d=\"%s\" fill=\"none\" stroke=\"#059669\" stroke-width=\"2.8\" stroke-linecap=\"round\"/>\n"
                       smooth-front-path)))
    ;; Pareto front nondominated points
    (doseq [[x y] sorted-front]
      (.append sb (fmt "  <circle cx=\"%.2f\" cy=\"%.2f\" r=\"6.0\" fill=\"#10b981\" stroke=\"#064e3b\" stroke-width=\"1.8\"/>\n"
                       (px x) (py y))))
    ;; Extra markers (e.g. SPT / EDD heuristics)
    (doseq [{:keys [point label color]} extra-markers
            :let [[x y] point
                  cx (px x)
                  cy (py y)]]
      (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"10\" height=\"10\" fill=\"%s\" stroke=\"#1e1b4b\" stroke-width=\"1.5\" transform=\"rotate(45 %.2f %.2f)\"/>\n"
                       (- cx 5.0) (- cy 5.0) color cx cy))
      (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"11\" font-weight=\"600\" fill=\"%s\">%s</text>\n"
                       (+ cx 9.0) (- cy 5.0) color (svg-escape label))))
    ;; Ideal direction arrow (bottom-left)
    (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"11\" font-weight=\"600\" fill=\"#047857\">&#8592; &#8595; Ideal (minimize both objectives)</text>\n"
                     (+ pad-l 14) (+ pad-t plot-h -12)))
    ;; Axis labels
    (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"13\" font-weight=\"600\" fill=\"#1e293b\" text-anchor=\"middle\">%s</text>\n"
                     (+ pad-l (/ plot-w 2.0)) (- height 22) (svg-escape x-label)))
    (.append sb (fmt "  <text x=\"24\" y=\"%.2f\" font-size=\"13\" font-weight=\"600\" fill=\"#1e293b\" text-anchor=\"middle\" transform=\"rotate(-90 24 %.2f)\">%s</text>\n"
                     (+ pad-t (/ plot-h 2.0)) (+ pad-t (/ plot-h 2.0)) (svg-escape y-label)))
    ;; Legend box (top-right inside plot)
    (let [lx (- (+ pad-l plot-w) 295)
          ly (+ pad-t 14)]
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"280\" height=\"112\" rx=\"6\" fill=\"#ffffff\" fill-opacity=\"0.94\" stroke=\"#cbd5e1\" stroke-width=\"1\"/>\n"
                       lx ly))
      ;; Legend item 1: Initial random solutions
      (.append sb (fmt "  <circle cx=\"%d\" cy=\"%d\" r=\"5.2\" fill=\"#f59e0b\" stroke=\"#78350f\" stroke-width=\"1.3\"/>\n"
                       (+ lx 18) (+ ly 22)))
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"12\" fill=\"#1e293b\" dominant-baseline=\"middle\">Initial Random Solutions</text>\n"
                       (+ lx 34) (+ ly 22)))
      ;; Legend item 2: Dominated solutions
      (.append sb (fmt "  <circle cx=\"%d\" cy=\"%d\" r=\"3.8\" fill=\"#94a3b8\" fill-opacity=\"0.7\"/>\n"
                       (+ lx 18) (+ ly 46)))
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"12\" fill=\"#1e293b\" dominant-baseline=\"middle\">Dominated Candidate Solutions</text>\n"
                       (+ lx 34) (+ ly 46)))
      ;; Legend item 3: Nondominated Pareto front & smooth trade-off curve
      (.append sb (fmt "  <path d=\"M %d,%d Q %d,%d %d,%d\" fill=\"none\" stroke=\"#059669\" stroke-width=\"2.8\"/>\n"
                       (+ lx 8) (+ ly 73) (+ lx 18) (+ ly 65) (+ lx 28) (+ ly 70)))
      (.append sb (fmt "  <circle cx=\"%d\" cy=\"%d\" r=\"5.5\" fill=\"#10b981\" stroke=\"#064e3b\" stroke-width=\"1.6\"/>\n"
                       (+ lx 18) (+ ly 70)))
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"11.5\" font-weight=\"600\" fill=\"#064e3b\" dominant-baseline=\"middle\">Pareto Front &amp; Smooth Trade-Off Curve</text>\n"
                       (+ lx 34) (+ ly 70)))
      ;; Legend item 4: Hypervolume staircase area
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"16\" height=\"12\" fill=\"#10b981\" fill-opacity=\"0.22\" stroke=\"#059669\" stroke-dasharray=\"2,2\"/>\n"
                       (+ lx 10) (+ ly 88)))
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"11\" fill=\"#334155\" dominant-baseline=\"middle\">%s</text>\n"
                       (+ lx 34) (+ ly 95) (svg-escape hv-text))))
    (.append sb "</svg>\n")
    (io/make-parents out-path)
    (spit out-path (.toString sb) :encoding "UTF-8")
    (println "Wrote SVG:" out-path)))

;; 1. Generate Portfolio Pareto Front SVG (N=15, K=5 exact space + initial random vs Pareto front)
(let [panel (edn/read-string (slurp (io/resource "fixtures/g1-portfolio.edn")))
      instance (portfolio/prepare-instance panel)
      norm (pareto/portfolio-normalization instance)
      adapter (port-runtime/make-adapter instance)
      all-choices (vec (port-ref/selections (count (:assets panel)) (:k panel)))
      all-points (mapv #(:objectives (portfolio/evaluate instance %)) all-choices)
      exact-front (pareto/front all-points)
      front-set (set exact-front)
      dominated (filterv #(not (contains? front-set %)) all-points)
      ;; Draw 16 initial random solutions from seed 0
      rng (Random. 0)
      initial-pts (mapv (fn [_]
                          (let [sol ((:initialize adapter) :random rng)]
                            (:objectives (portfolio/evaluate instance sol))))
                        (range 16))
      hv (pareto/normalized-hypervolume exact-front norm)
      xs (map first all-points)
      ys (map second all-points)
      x-min (apply min xs)
      x-max (apply max xs)
      y-min (apply min ys)
      y-max (apply max ys)
      x-pad (* 0.06 (- x-max x-min))
      y-pad (* 0.08 (- y-max y-min))]
  (render-pareto-svg
   {:title "Portfolio Selection (N=15, K=5): Initial Random Solutions, Dominated Cloud & Pareto Front"
    :subtitle "3,003 equal-weight K=5 portfolios: progression from initial random draws (amber) to the 13-point nondominated Pareto frontier (emerald)"
    :x-label "Objective 1: Negative Mean Daily Return  -mu_p  (minimized)"
    :y-label "Objective 2: Sample Standard Deviation  sigma_p  (minimized)"
    :dominated-points dominated
    :initial-points initial-pts
    :pareto-points exact-front
    :extra-markers []
    :x-range [(- x-min x-pad) (+ x-max x-pad)]
    :y-range [(- y-min y-pad) (+ y-max y-pad)]
    :ref-point [(+ x-max (* 0.03 (- x-max x-min)))
                (+ y-max (* 0.04 (- y-max y-min)))]
    :hv-text (fmt "Dominated Hypervolume (Norm. HV = %.4f)" hv)
    :out-path "docs/figures/portfolio_pareto_front.svg"}))

;; 2. Generate Scheduling Pareto Front SVG (30-Job Instance: Initial Random -> Dominated Cloud -> Evolved Front)
(let [suite (scheduling/generate-benchmark-suite {:jobs 30 :base-seed 20261108})
      inst-map (first (:test suite))
      instance (scheduling/prepare-instance inst-map)
      norm (scheduling/scheduling-normalization instance)
      adapter (sched-runtime/make-adapter instance)
      ;; Run the top evolved scheduling program with trace enabled so we capture every candidate visited!
      evolved-program [:program :scheduling
                       [:budget
                        [:seq [:initialize [:initializer :random]]
                         [:repeat [:count 16]
                          [:search [:count 16] [:operator :insert]
                           [:add [:add [:feature :objective-0] [:feature :objective-1]]
                            [:feature :objective-0]]]]]]]
      run-res (runtime/run evolved-program adapter {:solution-budget 256 :step-budget 4096 :seed 1001 :cache? true :trace? true})
      candidates (mapv :objectives (filter #(= :candidate (:event %)) (:trace run-res)))
      initial-pts (vec (take 16 candidates))
      front (:archive run-res)
      front-set (set front)
      dominated (filterv #(not (contains? front-set %)) (drop 16 candidates))
      spt-pt (:objectives (scheduling/evaluate instance (sched-runtime/spt-schedule instance)))
      edd-pt (:objectives (scheduling/evaluate instance (sched-runtime/edd-schedule instance)))
      hv (pareto/normalized-hypervolume front norm)
      all-pts (conj (into candidates [spt-pt edd-pt]) (first front))
      xs (map first all-pts)
      ys (map second all-pts)
      x-min (apply min xs)
      x-max (apply max xs)
      y-min (apply min ys)
      y-max (apply max ys)
      x-pad (* 0.08 (- x-max x-min))
      y-pad (* 0.08 (- y-max y-min))]
  (render-pareto-svg
   {:title "Single-Machine Scheduling (30 Jobs): Initial Random Schedules, Dominated Cloud & Evolved Front"
    :subtitle "256-proposal trajectory: initial random schedules (amber), dominated search cloud (grey), SPT/EDD rules, and evolved Pareto front (emerald)"
    :x-label "Objective 1: Mean Completion Time (minimized)"
    :y-label "Objective 2: Total Tardiness (minimized)"
    :dominated-points dominated
    :initial-points initial-pts
    :pareto-points front
    :extra-markers [{:point spt-pt :label "SPT Heuristic" :color "#4f46e5"}
                    {:point edd-pt :label "EDD Heuristic" :color "#7c3aed"}]
    :x-range [(- x-min x-pad) (+ x-max x-pad)]
    :y-range [(max 0.0 (- y-min y-pad)) (+ y-max y-pad)]
    :ref-point [(+ x-max (* 0.04 (- x-max x-min)))
                (+ y-max (* 0.04 (- y-max y-min)))]
    :hv-text (fmt "Evolved Front (Norm. HV = %.4f)" hv)
    :out-path "docs/figures/scheduling_pareto_front.svg"}))

;; 3. Generate Generator-Prior Ablation & 5-Arm Comparison SVG (docs/figures/ablation_and_arms_comparison.svg)
(let [ablation-data (edn/read-string (slurp "docs/evidence/two-replication-ablation-results.edn"))
      width 920
      height 540
      sb (StringBuilder.)
      render-domain-panel
      (fn [x0 y0 pw ph title y-min y-max summaries]
        (let [by-key (into {} (map (juxt :arm :generator-prior) summaries))
               get-s (fn [arm prior] (first (filter #(and (= arm (:arm %)) (= prior (:generator-prior %))) summaries)))
              arms [[:gp-mixed "A1: GP-Mixed"]
                    [:gp-mutation-only "A2: GP-Mutation"]
                    [:random-dsl "A3: Random-DSL"]
                    [:formula-only "A4: Formula-Only"]
                    [:configured-conventional "A5: Conventional"]]
              y-span (- (double y-max) (double y-min))
              py (fn [v] (+ y0 (- ph (* (/ (- (double v) (double y-min)) y-span) ph))))
              col-w (/ (double pw) (count arms))]
          (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"15\" font-weight=\"700\" fill=\"#0f172a\">%s</text>\n"
                           x0 (- y0 14) (svg-escape title)))
          (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1.2\"/>\n"
                           x0 y0 pw ph))
          ;; Horizontal grid lines
          (doseq [i (range 6)
                  :let [yv (+ y-min (* (/ (double i) 5.0) y-span))
                        cy (py yv)]]
            (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                             x0 cy (+ x0 pw) cy))
            (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"11\" fill=\"#475569\" text-anchor=\"end\" dominant-baseline=\"middle\">%.3f</text>\n"
                             (- x0 8) cy yv)))
          ;; Bars per arm
          (doseq [[idx [arm label]] (map-indexed vector arms)
                  :let [cx (+ x0 (* (+ idx 0.5) col-w))
                        s-v1 (get-s arm :development-only-v1)
                        s-v2 (get-s arm :budget-aware-v2)]]
            (if s-v1
              ;; Paired v1 (amber/slate) vs v2 (emerald) bars
              (let [bw 24.0
                    v1-mean (:mean-test-hv s-v1)
                    v2-mean (:mean-test-hv s-v2)
                    y1 (py v1-mean)
                    y2 (py v2-mean)
                    h1 (max 2.0 (- (+ y0 ph) y1))
                    h2 (max 2.0 (- (+ y0 ph) y2))
                    delta (- (double v2-mean) (double v1-mean))]
                (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"3\" fill=\"#f59e0b\" fill-opacity=\"0.82\" stroke=\"#b45309\" stroke-width=\"1.2\"/>\n"
                                 (- cx bw 2.0) y1 bw h1))
                (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"3\" fill=\"#10b981\" fill-opacity=\"0.88\" stroke=\"#047857\" stroke-width=\"1.2\"/>\n"
                                 (+ cx 2.0) y2 bw h2))
                ;; Individual replication dots
                (doseq [rv (:replication-test-hvs s-v1)]
                  (.append sb (fmt "  <circle cx=\"%.2f\" cy=\"%.2f\" r=\"3.2\" fill=\"#78350f\"/>\n"
                                   (- cx (/ bw 2.0) 2.0) (py rv))))
                (doseq [rv (:replication-test-hvs s-v2)]
                  (.append sb (fmt "  <circle cx=\"%.2f\" cy=\"%.2f\" r=\"3.2\" fill=\"#064e3b\"/>\n"
                                   (+ cx (/ bw 2.0) 2.0) (py rv))))
                ;; Value & delta label above v2 bar
                (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"10.5\" font-weight=\"700\" fill=\"#047857\" text-anchor=\"middle\">%+.3f</text>\n"
                                 cx (- (min y1 y2) 8.0) delta)))
              ;; Single bar for A4 / A5 (prior-independent baselines)
              (let [bw 30.0
                    v2-mean (:mean-test-hv s-v2)
                    y2 (py v2-mean)
                    h2 (max 2.0 (- (+ y0 ph) y2))]
                (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" rx=\"3\" fill=\"#6366f1\" fill-opacity=\"0.82\" stroke=\"#3730a3\" stroke-width=\"1.2\"/>\n"
                                 (- cx (/ bw 2.0)) y2 bw h2))
                (doseq [rv (:replication-test-hvs s-v2)]
                  (.append sb (fmt "  <circle cx=\"%.2f\" cy=\"%.2f\" r=\"3.2\" fill=\"#1e1b4b\"/>\n"
                                   cx (py rv))))
                (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"10.5\" font-weight=\"600\" fill=\"#3730a3\" text-anchor=\"middle\">%.3f</text>\n"
                                 cx (- y2 8.0) (double v2-mean)))))
            ;; X-axis arm label
            (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"11\" font-weight=\"600\" fill=\"#1e293b\" text-anchor=\"middle\">%s</text>\n"
                             cx (+ y0 ph 20) (svg-escape label))))))]
  (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"Inter, Segoe UI, Arial, sans-serif\">\n"
                   width height width height))
  (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" rx=\"8\"/>\n" width height))
  (.append sb "  <text x=\"65\" y=\"34\" font-size=\"18\" font-weight=\"700\" fill=\"#0f172a\">Two-Replication Generator Prior Ablation &amp; Five-Arm Comparison</text>\n")
  (.append sb "  <text x=\"65\" y=\"55\" font-size=\"13\" fill=\"#475569\">Held-out Test Normalized Hypervolume (24 test tasks x 10 inner seeds x 2 outer replications; dots = Rep 0, Rep 1)</text>\n")
  ;; Panel 1: Portfolio
  (render-domain-panel 65 98 375 340
                       "Domain 1: Portfolio (N=50, K=5, T=365)"
                       0.52 0.68
                       (get-in ablation-data [:domains :portfolio :arm-summaries]))
  ;; Panel 2: Scheduling
  (render-domain-panel 505 98 375 340
                       "Domain 2: Scheduling (30 Jobs)"
                       0.51 0.565
                       (get-in ablation-data [:domains :scheduling :arm-summaries]))
  ;; Bottom Legend
  (let [ly 485]
    (.append sb (fmt "  <rect x=\"65\" y=\"%d\" width=\"815\" height=\"38\" rx=\"6\" fill=\"#f8fafc\" stroke=\"#cbd5e1\" stroke-width=\"1\"/>\n" ly))
    (.append sb (fmt "  <rect x=\"85\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#f59e0b\" stroke=\"#b45309\"/>\n" (+ ly 12)))
    (.append sb (fmt "  <text x=\"106\" y=\"%d\" font-size=\"12\" fill=\"#1e293b\" dominant-baseline=\"middle\">Naive Prior (:development-only-v1)</text>\n" (+ ly 20)))
    (.append sb (fmt "  <rect x=\"335\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#10b981\" stroke=\"#047857\"/>\n" (+ ly 12)))
    (.append sb (fmt "  <text x=\"356\" y=\"%d\" font-size=\"12\" font-weight=\"600\" fill=\"#064e3b\" dominant-baseline=\"middle\">Improved Prior (:budget-aware-v2, all p &lt; 0.0001)</text>\n" (+ ly 20)))
    (.append sb (fmt "  <rect x=\"660\" y=\"%d\" width=\"14\" height=\"14\" rx=\"2\" fill=\"#6366f1\" stroke=\"#3730a3\"/>\n" (+ ly 12)))
    (.append sb (fmt "  <text x=\"681\" y=\"%d\" font-size=\"12\" fill=\"#1e293b\" dominant-baseline=\"middle\">Fixed-Scaffold / Conventional Baselines</text>\n" (+ ly 20))))
  (.append sb "</svg>\n")
  (spit "docs/figures/ablation_and_arms_comparison.svg" (.toString sb) :encoding "UTF-8")
  (println "Wrote SVG: docs/figures/ablation_and_arms_comparison.svg"))

;; 4. Generate Proposal-Budget Scaling SVG (docs/figures/budget_scaling_ablation.svg) when evidence exists
(when (.exists (io/file "docs/evidence/budget-scaling-ablation-results.edn"))
  (let [scaling-data (edn/read-string (slurp "docs/evidence/budget-scaling-ablation-results.edn"))
        domain-results (:domains scaling-data)
        budgets [16 32 64 128 256]
        width 920
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
            (doseq [i (range 6)
                    :let [yv (+ y-min (* (/ (double i) 5.0) y-span))
                          cy (py yv)]]
              (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                               x0 cy (+ x0 pw) cy))
              (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"10.5\" fill=\"#475569\" text-anchor=\"end\" dominant-baseline=\"middle\">%.3f</text>\n"
                               (- x0 8) cy yv)))
            (doseq [b budgets
                    :let [cx (px b)]]
              (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%d\" x2=\"%.2f\" y2=\"%d\" stroke=\"#e2e8f0\" stroke-width=\"1\"/>\n"
                               cx y0 cx (+ y0 ph)))
              (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"11\" font-weight=\"600\" fill=\"#334155\" text-anchor=\"middle\">%d</text>\n"
                               cx (+ y0 ph 20) b)))
            (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"11.5\" font-weight=\"600\" fill=\"#475569\" text-anchor=\"middle\">Inner Solution Proposal Budget (log2 scale)</text>\n"
                             (+ x0 (/ pw 2.0)) (+ y0 ph 40)))
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
    (println "Wrote SVG: docs/figures/budget_scaling_ablation.svg")))
