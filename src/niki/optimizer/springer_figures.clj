(ns niki.optimizer.springer-figures
  "Reusable, deterministic SVG figure generator adhering to Springer (LNCS / GPEM / Springer Nature)
   artwork and typography guidelines:
   - Font family: Helvetica, Arial, sans-serif (Core-14 PDF/PostScript safe)
   - Print sizing: 122 mm (LNCS full textwidth) / 174 mm (journal double-column) via physical mm
     attributes and calibrated viewBox user units (axis labels 8.5-9 pt, ticks/legends 7.5-8 pt,
     bold lowercase panel labels (a), (b), (c)).
   - Caption-ready layout: no redundant top-banner titles in print mode (:print-mode? true);
     all titles/subtitles are emitted into the companion LaTeX file `springer_figures.tex`.
   - Colorblind-safe & grayscale-distinguishable Okabe-Ito palette paired with distinct
     geometric marker shapes (circle, triangle, square, diamond) and stroke dash styles."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [niki.optimizer.portfolio :as portfolio]
            [niki.optimizer.portfolio-data :as port-data]
            [niki.optimizer.portfolio-reference :as port-ref]
            [niki.optimizer.portfolio-runtime :as port-runtime]
            [niki.optimizer.scheduling :as scheduling]
            [niki.optimizer.scheduling-runtime :as sched-runtime]
            [niki.optimizer.pareto :as pareto]
            [niki.optimizer.runtime :as runtime])
  (:import [java.util Random Locale]))

(defn fmt [pattern & args]
  (String/format Locale/US pattern (to-array args)))

(defn svg-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

;; Classic Matplotlib (tab10) Academic Palette matching Penchev & Marchev Jr. (AIMSA)
(def palette
  {:emerald    "#1f77b4" ; Classic Matplotlib Blue (Focal GP-Mixed / Evolved DSL)
   :emerald-dk "#145380"
   :sky        "#ff7f0e" ; Classic Matplotlib Orange (GP-Mutation-Only)
   :sky-dk     "#b85800"
   :amber      "#d62728" ; Classic Matplotlib Red (v1 Prior / Initial Random)
   :amber-dk   "#8c1919"
   :blue       "#2ca02c" ; Classic Matplotlib Green (Formula-Only / SPT)
   :blue-dk    "#1b661b"
   :purple     "#9467bd" ; Classic Matplotlib Purple (Configured Conventional / EDD)
   :purple-dk  "#5e3c7d"
   :vermilion  "#d62728" ; Classic Matplotlib Red (Knockouts / Untyped)
   :verm-dk    "#8c1919"
   :slate      "#7f7f7f" ; Classic Matplotlib Grey (Dominated cloud / Random Search)
   :slate-dk   "#4d4d4d"
   :ink        "#000000"
   :muted      "#111111"
   :grid       "#e0e0e0"
   :border     "#000000"
   :panel-bg   "#ffffff"})

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

(defn draw-marker
  "Draw a grayscale-distinguishable geometric marker (:circle, :triangle, :square, :diamond, :cross)
   centered at (cx, cy) with radius r."
  [shape cx cy r fill stroke stroke-w opacity]
  (let [cx (double cx)
        cy (double cy)
        r (double r)
        op-attr (if (< (double opacity) 0.999) (fmt " fill-opacity=\"%.2f\"" (double opacity)) "")]
    (case shape
      :circle
      (fmt "  <circle cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\" fill=\"%s\"%s stroke=\"%s\" stroke-width=\"%.2f\"/>\n"
           cx cy r fill op-attr stroke (double stroke-w))
      :square
      (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" fill=\"%s\"%s stroke=\"%s\" stroke-width=\"%.2f\"/>\n"
           (- cx r) (- cy r) (* 2.0 r) (* 2.0 r) fill op-attr stroke (double stroke-w))
      :diamond
      (fmt "  <polygon points=\"%.2f,%.2f %.2f,%.2f %.2f,%.2f %.2f,%.2f\" fill=\"%s\"%s stroke=\"%s\" stroke-width=\"%.2f\"/>\n"
           cx (- cy (* 1.25 r)) (+ cx (* 1.25 r)) cy cx (+ cy (* 1.25 r)) (- cx (* 1.25 r)) cy
           fill op-attr stroke (double stroke-w))
      :triangle
      (fmt "  <polygon points=\"%.2f,%.2f %.2f,%.2f %.2f,%.2f\" fill=\"%s\"%s stroke=\"%s\" stroke-width=\"%.2f\"/>\n"
           cx (- cy (* 1.2 r)) (+ cx (* 1.1 r)) (+ cy (* 0.9 r)) (- cx (* 1.1 r)) (+ cy (* 0.9 r))
           fill op-attr stroke (double stroke-w))
      :cross
      (fmt "  <path d=\"M %.2f,%.2f L %.2f,%.2f M %.2f,%.2f L %.2f,%.2f\" stroke=\"%s\" stroke-opacity=\"%.2f\" stroke-width=\"%.2f\"/>\n"
           (- cx r) (- cy r) (+ cx r) (+ cy r) (- cx r) (+ cy r) (+ cx r) (- cy r)
           fill (double opacity) (double stroke-w)))))

(defn render-pareto-panel!
  "Render a single Pareto front subplot inside `sb` at `(x0, y0, pw, ph)`."
  [^StringBuilder sb {:keys [x0 y0 pw ph panel-label title x-label y-label
                             dominated-points initial-points pareto-points extra-markers
                             x-range y-range ref-point hv-value x-decimals y-decimals]}]
  (let [[x-min x-max] x-range
        [y-min y-max] y-range
        x-span (max 1.0e-9 (- (double x-max) (double x-min)))
        y-span (max 1.0e-9 (- (double y-max) (double y-min)))
        px (fn [x] (+ x0 (* (/ (- (double x) (double x-min)) x-span) pw)))
        py (fn [y] (+ y0 (- ph (* (/ (- (double y) (double y-min)) y-span) ph))))
        sorted-front (vec (sort-by first pareto-points))
        [rx ry] ref-point
        stair-coords
        (when (seq sorted-front)
          (let [first-pt (first sorted-front)
                steps (loop [idx 0
                             acc [[(px (first first-pt)) (py ry)]
                                  [(px (first first-pt)) (py (second first-pt))]]]
                        (if (= idx (dec (count sorted-front)))
                          (let [last-pt (peek sorted-front)]
                            (conj acc [(px rx) (py (second last-pt))] [(px rx) (py ry)]))
                          (let [cur (nth sorted-front idx)
                                nxt (nth sorted-front (inc idx))]
                            (recur (inc idx)
                                   (conj acc
                                         [(px (first nxt)) (py (second cur))]
                                         [(px (first nxt)) (py (second nxt))])))))]
            (str/join " " (map (fn [[x y]] (fmt "%.2f,%.2f" x y)) steps))))
        smooth-path
        (when (seq sorted-front)
          (monotone-cubic-svg-path (mapv (fn [[x y]] [(px x) (py y)]) sorted-front)))
        x-fmt (str "%." (or x-decimals 4) "f")
        y-fmt (str "%." (or y-decimals 4) "f")]
    ;; Panel header: bold (a)/(b) + concise header
    (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"11.5\" font-weight=\"700\" fill=\"%s\">%s <tspan font-weight=\"600\">%s</tspan></text>\n"
                     x0 (- y0 8) (:ink palette) (svg-escape panel-label) (svg-escape title)))
    ;; Plot box
    (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.0\"/>\n"
                     x0 y0 pw ph (:panel-bg palette) (:border palette)))
    ;; Grid & ticks (5 intervals)
    (doseq [i (range 6)
            :let [xv (+ x-min (* (/ (double i) 5.0) x-span))
                  cx (px xv)
                  yv (+ y-min (* (/ (double i) 5.0) y-span))
                  cy (py yv)]]
      (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%d\" x2=\"%.2f\" y2=\"%d\" stroke=\"%s\" stroke-width=\"0.8\"/>\n"
                       cx y0 cx (+ y0 ph) (:grid palette)))
      (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"9.5\" fill=\"%s\" text-anchor=\"middle\">%s</text>\n"
                       cx (+ y0 ph 14) (:muted palette) (fmt x-fmt xv)))
      (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"0.8\"/>\n"
                       x0 cy (+ x0 pw) cy (:grid palette)))
      (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9.5\" fill=\"%s\" text-anchor=\"end\" dominant-baseline=\"middle\">%s</text>\n"
                       (- x0 6) cy (:muted palette) (fmt y-fmt yv))))
    ;; Shaded Hypervolume region bounded by dashed orthogonal step function
    (when stair-coords
      (.append sb (fmt "  <polygon points=\"%s\" fill=\"%s\" fill-opacity=\"0.13\" stroke=\"%s\" stroke-opacity=\"0.55\" stroke-dasharray=\"3.5,2.5\" stroke-width=\"1.1\"/>\n"
                       stair-coords (:emerald palette) (:emerald-dk palette))))
    ;; Dominated candidate cloud (small muted grey dots)
    (doseq [[x y] dominated-points]
      (.append sb (fmt "  <circle cx=\"%.2f\" cy=\"%.2f\" r=\"2.2\" fill=\"%s\" fill-opacity=\"0.40\"/>\n"
                       (px x) (py y) (:slate palette))))
    ;; Initial random solutions (distinct Amber Triangles for grayscale & colorblind clarity!)
    (doseq [[x y] initial-points]
      (.append sb (draw-marker :triangle (px x) (py y) 3.8 (:amber palette) (:amber-dk palette) 1.0 0.92)))
    ;; Smooth monotone cubic Bezier trade-off curve
    (when smooth-path
      (.append sb (fmt "  <path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"2.2\" stroke-linecap=\"round\"/>\n"
                       smooth-path (:emerald-dk palette))))
    ;; Nondominated Pareto front points (filled emerald circles)
    (doseq [[x y] sorted-front]
      (.append sb (draw-marker :circle (px x) (py y) 4.0 (:emerald palette) (:emerald-dk palette) 1.3 1.0)))
    ;; Extra markers (SPT / EDD heuristics as diamonds/squares)
    (doseq [{:keys [point label color stroke shape]} extra-markers
            :let [[x y] point
                  cx (px x)
                  cy (py y)]]
      (.append sb (draw-marker (or shape :diamond) cx cy 3.8 color (or stroke "#000000") 0.9 1.0))
      (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"9\" fill=\"%s\">%s</text>\n"
                       (+ cx 6.0) (- cy 4.0) "#000000" (svg-escape label))))
    ;; Axis labels
    (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"10\" fill=\"%s\" text-anchor=\"middle\">%s</text>\n"
                     (+ x0 (/ pw 2.0)) (+ y0 ph 32) (:ink palette) (svg-escape x-label)))
    (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"10\" fill=\"%s\" text-anchor=\"middle\" transform=\"rotate(-90 %d %.2f)\">%s</text>\n"
                     (- x0 46) (+ y0 (/ ph 2.0)) (:ink palette) (- x0 46) (+ y0 (/ ph 2.0)) (svg-escape y-label)))))

(defn generate-fig1-architecture!
  "Generate Fig 1: Clean black-and-white academic block diagram of the Typed S-Expression Pipeline."
  [out-path]
  (let [w 760 h 290
        sb (StringBuilder.)]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"174mm\" height=\"66.4mm\" font-family=\"Arial, Helvetica, sans-serif\">\n" w h))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\"/>\n" w h))
    (.append sb "  <defs>\n    <marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"5\" markerHeight=\"5\" orient=\"auto-start-reverse\">\n      <path d=\"M 0 1.5 L 8 5 L 0 8.5 z\" fill=\"#000000\"/>\n    </marker>\n  </defs>\n")

    ;; Box 1: Typed S-Expression DSL (Left)
    (.append sb "  <rect x=\"14\" y=\"16\" width=\"232\" height=\"258\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n")
    (.append sb "  <line x1=\"14\" y1=\"38\" x2=\"246\" y2=\"38\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n")
    (.append sb "  <text x=\"130\" y=\"31\" font-size=\"10\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\">(a) Typed S-Expression DSL (6 Roles)</text>\n")
    (doseq [[idx [role desc]] (map-indexed vector
                                           [[":operation"   "(seq | repeat | if | search | step)"]
                                            [":predicate"   "(archive-changed | budget-left | less)"]
                                            [":score"       "(add | multiply | feature | constant)"]
                                            [":count"       "Loop bounds {2, 4, 8, 12, 16, 24, 32, 64}"]
                                            [":initializer" "(:random | :first)"]
                                            [":operator"    "(:swap | :restart | :insert | :reverse)"]])]
      (let [y (+ 56 (* idx 21))]
        (.append sb (fmt "  <text x=\"22\" y=\"%d\" font-size=\"8.5\" font-family=\"Consolas, monospace\" font-weight=\"700\" fill=\"#000000\">%s</text>\n" y role))
        (.append sb (fmt "  <text x=\"88\" y=\"%d\" font-size=\"8\" fill=\"#222222\">%s</text>\n" y (svg-escape desc)))))
    (.append sb "  <line x1=\"22\" y1=\"182\" x2=\"238\" y2=\"182\" stroke=\"#cccccc\" stroke-width=\"0.7\"/>\n")
    (.append sb "  <text x=\"22\" y=\"196\" font-size=\"8.5\" font-weight=\"700\" fill=\"#000000\">Quoted Program Tree (Code as Data):</text>\n")
    (.append sb "  <text x=\"22\" y=\"211\" xml:space=\"preserve\" font-size=\"8\" font-family=\"Consolas, monospace\" fill=\"#111111\">'(:program :scheduling</text>\n")
    (.append sb "  <text x=\"22\" y=\"224\" xml:space=\"preserve\" font-size=\"8\" font-family=\"Consolas, monospace\" fill=\"#111111\">  (:budget (:seq</text>\n")
    (.append sb "  <text x=\"22\" y=\"237\" xml:space=\"preserve\" font-size=\"8\" font-family=\"Consolas, monospace\" fill=\"#111111\">    (:initialize (:initializer :first))</text>\n")
    (.append sb "  <text x=\"22\" y=\"250\" xml:space=\"preserve\" font-size=\"8\" font-family=\"Consolas, monospace\" fill=\"#111111\">    (:repeat (:count 32) (:search (:count 8)</text>\n")
    (.append sb "  <text x=\"22\" y=\"263\" xml:space=\"preserve\" font-size=\"8\" font-family=\"Consolas, monospace\" fill=\"#111111\">      (:operator :insert) &lt;score-expr&gt;)))))</text>\n")

    ;; Arrow 1 -> 2
    (.append sb "  <line x1=\"246\" y1=\"145\" x2=\"270\" y2=\"145\" stroke=\"#000000\" stroke-width=\"1.0\" marker-end=\"url(#arrow)\"/>\n")

    ;; Box 2: Meta-Evolution & Bounded Interpreter (Center)
    (.append sb "  <rect x=\"272\" y=\"16\" width=\"224\" height=\"258\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n")
    (.append sb "  <line x1=\"272\" y1=\"38\" x2=\"496\" y2=\"38\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n")
    (.append sb "  <text x=\"384\" y=\"31\" font-size=\"10\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\">(b) Meta-Evolution &amp; Interpreter</text>\n")
    (.append sb "  <rect x=\"282\" y=\"48\" width=\"204\" height=\"96\" fill=\"#ffffff\" stroke=\"#555555\" stroke-width=\"0.7\"/>\n")
    (.append sb "  <text x=\"290\" y=\"63\" font-size=\"9\" font-weight=\"700\" fill=\"#000000\">Outer Meta-Evolution (30 Seeds)</text>\n")
    (.append sb "  <text x=\"290\" y=\"78\" font-size=\"8.5\" fill=\"#111111\">• 256 candidate programs / seed</text>\n")
    (.append sb "  <text x=\"290\" y=\"93\" font-size=\"8.5\" fill=\"#111111\">• 32 initial + 7 × 32 offspring batches</text>\n")
    (.append sb "  <text x=\"290\" y=\"108\" font-size=\"8.5\" fill=\"#111111\">• Role-safe mutation &amp; subtree crossover</text>\n")
    (.append sb "  <text x=\"290\" y=\"123\" font-size=\"8.5\" fill=\"#111111\">• Train (6) → Val (6) → Frozen Test (24)</text>\n")

    (.append sb "  <rect x=\"282\" y=\"156\" width=\"204\" height=\"106\" fill=\"#ffffff\" stroke=\"#555555\" stroke-width=\"0.7\"/>\n")
    (.append sb "  <text x=\"290\" y=\"171\" font-size=\"9\" font-weight=\"700\" fill=\"#000000\">Base-Level Restricted Interpreter</text>\n")
    (.append sb "  <text x=\"290\" y=\"186\" font-size=\"8.5\" fill=\"#111111\">• Budget: B = 256 proposals, 4,096 steps</text>\n")
    (.append sb "  <text x=\"290\" y=\"201\" font-size=\"8.5\" fill=\"#111111\">• Cache hits still charge 1 proposal</text>\n")
    (.append sb "  <text x=\"290\" y=\"216\" font-size=\"8.5\" fill=\"#111111\">• Non-dominated Pareto archive update</text>\n")
    (.append sb "  <text x=\"290\" y=\"231\" font-size=\"8.5\" fill=\"#111111\">• Normalized 2D Hypervolume in [0, 1]</text>\n")
    (.append sb "  <text x=\"290\" y=\"246\" font-size=\"8.5\" fill=\"#111111\">• Wall-clock runtime recorded (ms / s)</text>\n")

    ;; Arrows 2 -> 3a and 3b
    (.append sb "  <line x1=\"496\" y1=\"80\" x2=\"522\" y2=\"80\" stroke=\"#000000\" stroke-width=\"1.0\" marker-end=\"url(#arrow)\"/>\n")
    (.append sb "  <line x1=\"496\" y1=\"210\" x2=\"522\" y2=\"210\" stroke=\"#000000\" stroke-width=\"1.0\" marker-end=\"url(#arrow)\"/>\n")

    ;; Box 3a: Domain 1 Adapter (Top Right)
    (.append sb "  <rect x=\"524\" y=\"16\" width=\"222\" height=\"120\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n")
    (.append sb "  <line x1=\"524\" y1=\"38\" x2=\"746\" y2=\"38\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n")
    (.append sb "  <text x=\"635\" y=\"31\" font-size=\"9.5\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\">(c) Domain 1: NASDAQ-50 Portfolio</text>\n")
    (.append sb "  <text x=\"532\" y=\"54\" font-size=\"8.5\" fill=\"#111111\">• 50 NASDAQ stocks (2012–2026)</text>\n")
    (.append sb "  <text x=\"532\" y=\"69\" font-size=\"8.5\" fill=\"#111111\">• Exact-K subset (K = 5, T = 90 days)</text>\n")
    (.append sb "  <text x=\"532\" y=\"84\" font-size=\"8.5\" fill=\"#111111\">• Objectives: (-mean return, daily risk)</text>\n")
    (.append sb "  <text x=\"532\" y=\"99\" font-size=\"8.5\" fill=\"#111111\">• Operators: {:swap, :restart}</text>\n")
    (.append sb "  <text x=\"532\" y=\"114\" font-size=\"8.5\" fill=\"#111111\">• 6 Train / 6 Val / 24 Test quarters</text>\n")

    ;; Box 3b: Domain 2 Adapter (Bottom Right)
    (.append sb "  <rect x=\"524\" y=\"154\" width=\"222\" height=\"120\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n")
    (.append sb "  <line x1=\"524\" y1=\"176\" x2=\"746\" y2=\"176\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n")
    (.append sb "  <text x=\"635\" y=\"169\" font-size=\"9.5\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\">(d) Domain 2: OR-Library Scheduling</text>\n")
    (.append sb "  <text x=\"532\" y=\"192\" font-size=\"8.5\" fill=\"#111111\">• Beasley OR-Library wt40 (40 jobs)</text>\n")
    (.append sb "  <text x=\"532\" y=\"207\" font-size=\"8.5\" fill=\"#111111\">• Single-machine job permutations</text>\n")
    (.append sb "  <text x=\"532\" y=\"222\" font-size=\"8.5\" fill=\"#111111\">• Objectives: (mean completion, tardiness)</text>\n")
    (.append sb "  <text x=\"532\" y=\"237\" font-size=\"8.5\" fill=\"#111111\">• Operators: {:swap, :restart, :insert, :reverse}</text>\n")
    (.append sb "  <text x=\"532\" y=\"252\" font-size=\"8.5\" fill=\"#111111\">• Initializers: {:first (SPT), :random}</text>\n")

    (.append sb "</svg>\n")
    (io/make-parents out-path)
    (spit out-path (.toString sb) :encoding "UTF-8")
    (println "Wrote Springer SVG:" out-path)))

(defn generate-fig2-pareto-fronts!
  "Generate Fig 2: Two-panel (a) Portfolio & (b) Scheduling Pareto Fronts with
   initial random solutions (amber triangles), dominated cloud (grey dots),
   nondominated Pareto front (emerald circles), smooth monotone cubic curve,
   and dashed orthogonal hypervolume step boundary."
  [out-path]
  (let [w 760 h 345
        sb (StringBuilder.)
        ;; Panel (a) data: Real NASDAQ N=15, K=5 exact space (first 15 tickers of first test window) + initial random
        nasdaq-suite (port-data/load-nasdaq-suite)
        first-test-panel (:panel (first (:test nasdaq-suite)))
        panel {:k 5
               :assets (vec (take 15 (:assets first-test-panel)))
               :returns (mapv #(vec (take 15 %)) (:returns first-test-panel))}
        p-inst (portfolio/prepare-instance panel)
        p-norm (pareto/portfolio-normalization p-inst)
        p-adapter (port-runtime/make-adapter p-inst)
        all-choices (vec (port-ref/selections (count (:assets panel)) (:k panel)))
        all-pts (mapv #(:objectives (portfolio/evaluate p-inst %)) all-choices)
        p-front (pareto/front all-pts)
        p-front-set (set p-front)
        p-dom (filterv #(not (contains? p-front-set %)) all-pts)
        p-rng (Random. 0)
        p-init (mapv (fn [_] (:objectives (portfolio/evaluate p-inst ((:initialize p-adapter) :random p-rng)))) (range 16))
        p-hv (pareto/normalized-hypervolume p-front p-norm)
        pxs (map first all-pts) pys (map second all-pts)
        px-min (apply min pxs) px-max (apply max pxs)
        py-min (apply min pys) py-max (apply max pys)
        px-pad (* 0.06 (- px-max px-min)) py-pad (* 0.08 (- py-max py-min))

        ;; Panel (b) data: Scheduling OR-Library wt40 40-job test instance
        s-suite (scheduling/load-orlib-wt-suite)
        s-inst (scheduling/prepare-instance (first (:test s-suite)))
        s-norm (scheduling/scheduling-normalization s-inst)
        s-adapter (sched-runtime/make-adapter s-inst)
        evolved-prog [:program :scheduling
                      [:budget
                       [:seq [:initialize [:initializer :random]]
                        [:repeat [:count 16]
                         [:search [:count 16] [:operator :insert]
                          [:add [:add [:feature :objective-0] [:feature :objective-1]]
                           [:feature :objective-0]]]]]]]
        s-run (runtime/run evolved-prog s-adapter {:solution-budget 256 :step-budget 4096 :seed 1001 :cache? true :trace? true})
        s-cands (mapv :objectives (filter #(= :candidate (:event %)) (:trace s-run)))
        s-init (vec (take 16 s-cands))
        s-front (:archive s-run)
        s-front-set (set s-front)
        s-dom (filterv #(not (contains? s-front-set %)) (drop 16 s-cands))
        spt-pt (:objectives (scheduling/evaluate s-inst (sched-runtime/spt-schedule s-inst)))
        edd-pt (:objectives (scheduling/evaluate s-inst (sched-runtime/edd-schedule s-inst)))
        s-hv (pareto/normalized-hypervolume s-front s-norm)
        s-all (conj (into s-cands [spt-pt edd-pt]) (first s-front))
        sxs (map first s-all) sys (map second s-all)
        sx-min (apply min sxs) sx-max (apply max sxs)
        sy-min (apply min sys) sy-max (apply max sys)
        sx-pad (* 0.08 (- sx-max sx-min)) sy-pad (* 0.08 (- sy-max sy-min))]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"174mm\" height=\"79.0mm\" font-family=\"Helvetica, Arial, sans-serif\">\n" w h))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\"/>\n" w h))
    ;; Panel (a)
    (render-pareto-panel!
     sb {:x0 64 :y0 26 :pw 292 :ph 232
         :panel-label "(a)"
         :title "NASDAQ Portfolio (N=15, K=5, T=90)"
         :x-label "Obj. 1: Negative Mean Daily Return -mu_p (min)"
         :y-label "Obj. 2: Daily Risk sigma_p (min)"
         :dominated-points p-dom
         :initial-points p-init
         :pareto-points p-front
         :extra-markers []
         :x-range [(- px-min px-pad) (+ px-max px-pad)]
         :y-range [(- py-min py-pad) (+ py-max py-pad)]
         :ref-point [(+ px-max (* 0.03 (- px-max px-min))) (+ py-max (* 0.04 (- py-max py-min)))]
         :hv-value p-hv
         :x-decimals 4 :y-decimals 4})
    ;; Panel (b)
    (render-pareto-panel!
     sb {:x0 442 :y0 26 :pw 292 :ph 232
         :panel-label "(b)"
         :title "OR-Library wt40 Scheduling (40 Jobs)"
         :x-label "Obj. 1: Mean Completion Time (min)"
         :y-label "Obj. 2: Total Tardiness (min)"
         :dominated-points s-dom
         :initial-points s-init
         :pareto-points s-front
         :extra-markers [{:point spt-pt :label "SPT" :color (:blue palette) :stroke (:blue-dk palette) :shape :diamond}
                         {:point edd-pt :label "EDD" :color (:purple palette) :stroke (:purple-dk palette) :shape :square}]
         :x-range [(- sx-min sx-pad) (+ sx-max sx-pad)]
         :y-range [(max 0.0 (- sy-min sy-pad)) (+ sy-max sy-pad)]
         :ref-point [(+ sx-max (* 0.04 (- sx-max sx-min))) (+ sy-max (* 0.04 (- sy-max sy-min)))]
         :hv-value s-hv
         :x-decimals 1 :y-decimals 0})
    ;; Shared Legend Bar at Bottom
    (let [ly 304]
      (.append sb (fmt "  <rect x=\"64\" y=\"%d\" width=\"670\" height=\"30\" rx=\"4\" fill=\"%s\" stroke=\"%s\" stroke-width=\"0.8\"/>\n"
                       ly (:panel-bg palette) (:border palette)))
      ;; Item 1: Initial Random (Amber Triangle)
      (.append sb (fmt "  <rect x=\"64\" y=\"%d\" width=\"670\" height=\"26\" fill=\"#ffffff\" stroke=\"#cccccc\" stroke-width=\"0.8\"/>\n"
                       ly))
      ;; Item 1: Initial Random
      (.append sb (draw-marker :triangle 82 (+ ly 13) 3.4 (:amber palette) (:amber-dk palette) 0.9 0.95))
      (.append sb (fmt "  <text x=\"92\" y=\"%d\" font-size=\"9\" fill=\"#000000\" dominant-baseline=\"middle\">Initial Random Solutions</text>\n"
                       (+ ly 14)))
      ;; Item 2: Dominated Cloud
      (.append sb (fmt "  <circle cx=\"224\" cy=\"%d\" r=\"2.8\" fill=\"%s\" fill-opacity=\"0.65\"/>\n" (+ ly 13) (:slate palette)))
      (.append sb (fmt "  <text x=\"233\" y=\"%d\" font-size=\"9\" fill=\"#000000\" dominant-baseline=\"middle\">Dominated Solutions</text>\n"
                       (+ ly 14)))
      ;; Item 3: Nondominated Front
      (.append sb (fmt "  <line x1=\"340\" y1=\"%d\" x2=\"364\" y2=\"%d\" stroke=\"%s\" stroke-width=\"1.8\"/>\n"
                       (+ ly 13) (+ ly 13) (:emerald palette)))
      (.append sb (draw-marker :circle 352 (+ ly 13) 3.2 (:emerald palette) (:emerald-dk palette) 0.9 1.0))
      (.append sb (fmt "  <text x=\"370\" y=\"%d\" font-size=\"9\" fill=\"#000000\" dominant-baseline=\"middle\">Pareto Front</text>\n"
                       (+ ly 14)))
      ;; Item 4: Shaded Step Hypervolume
      (.append sb (fmt "  <rect x=\"460\" y=\"%d\" width=\"14\" height=\"10\" fill=\"%s\" fill-opacity=\"0.18\" stroke=\"%s\" stroke-dasharray=\"2,2\" stroke-width=\"0.8\"/>\n"
                       (+ ly 8) (:emerald palette) (:emerald-dk palette)))
      (.append sb (fmt "  <text x=\"479\" y=\"%d\" font-size=\"9\" fill=\"#000000\" dominant-baseline=\"middle\">Hypervolume Region</text>\n"
                       (+ ly 14)))
      ;; Item 5: Heuristics
      (.append sb (draw-marker :diamond 608 (+ ly 13) 3.4 (:blue palette) (:blue-dk palette) 0.9 1.0))
      (.append sb (fmt "  <text x=\"618\" y=\"%d\" font-size=\"9\" fill=\"#000000\" dominant-baseline=\"middle\">SPT / EDD Rules</text>\n"
                       (+ ly 14))))
    (.append sb "</svg>\n")
    (io/make-parents out-path)
    (spit out-path (.toString sb) :encoding "UTF-8")
    (println "Wrote Springer SVG:" out-path)))

(defn generate-fig3-arms-and-prior!
  "Generate Fig 3: Five Search Arms & Generator Prior Ablation (v1 vs v2) from evidence EDN."
  [two-rep-edn out-path]
  (let [w 760 h 330
        sb (StringBuilder.)
        render-panel
        (fn [x0 y0 pw ph panel-lbl title y-min y-max summaries]
          (let [get-s (fn [arm prior] (first (filter #(and (= arm (:arm %)) (= prior (:generator-prior %))) summaries)))
                arms [[:gp-mixed "A1: GP-Mix"]
                      [:gp-mutation-only "A2: GP-Mut"]
                      [:random-dsl "A3: Rand-DSL"]
                      [:formula-only "A4: Formula"]
                      [:configured-conventional "A5: Conv."]]
                y-span (- (double y-max) (double y-min))
                py (fn [v] (+ y0 (- ph (* (/ (- (double (max y-min v)) (double y-min)) y-span) ph))))
                col-w (/ (double pw) (count arms))]
            (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"10.5\" font-weight=\"700\" fill=\"#000000\">%s <tspan font-weight=\"600\">%s</tspan></text>\n"
                             x0 (- y0 8) panel-lbl (svg-escape title)))
            (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n"
                             x0 y0 pw ph))
            (doseq [i (range 6)
                    :let [yv (+ y-min (* (/ (double i) 5.0) y-span))
                          cy (py yv)]]
              (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"0.7\"/>\n"
                               x0 cy (+ x0 pw) cy (:grid palette)))
              (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9\" fill=\"#111111\" text-anchor=\"end\" dominant-baseline=\"middle\">%.3f</text>\n"
                               (- x0 6) cy yv)))
            (let [draw-ci-or-pts
                  (fn [bx summary shape stroke-col]
                    (let [hvs (:replication-test-hvs summary)
                          n (count hvs)]
                      (if (<= n 4)
                        (doseq [rv hvs]
                          (.append sb (draw-marker shape bx (py rv) 2.3 "#ffffff" stroke-col 0.9 1.0)))
                        (let [m (double (:mean-test-hv summary))
                              ci (double (or (:ci95-halfwidth summary) 0.0))
                              y-hi (py (+ m ci))
                              y-lo (py (- m ci))]
                          (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n"
                                           bx y-hi bx y-lo))
                          (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n"
                                           (- bx 3.0) y-hi (+ bx 3.0) y-hi))
                          (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n"
                                           (- bx 3.0) y-lo (+ bx 3.0) y-lo))))))]
              (doseq [[idx [arm label]] (map-indexed vector arms)
                      :let [cx (+ x0 (* (+ idx 0.5) col-w))
                            s-v1 (get-s arm :development-only-v1)
                            s-v2 (get-s arm :budget-aware-v2)]]
                (if s-v1
                  (let [bw 18.0
                        v1-m (:mean-test-hv s-v1)
                        v2-m (:mean-test-hv s-v2)
                        v2-ci (double (or (:ci95-halfwidth s-v2) 0.0))
                        y1 (py v1-m)
                        y2 (py v2-m)
                        y2-top (py (+ (double v2-m) v2-ci))
                        delta (- (double v2-m) (double v1-m))]
                    (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" fill-opacity=\"0.85\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n"
                                     (- cx bw 1.0) y1 bw (max 2.0 (- (+ y0 ph) y1)) (:amber palette)))
                    (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" fill-opacity=\"0.90\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n"
                                     (+ cx 1.0) y2 bw (max 2.0 (- (+ y0 ph) y2)) (:emerald palette)))
                    (draw-ci-or-pts (- cx (/ bw 2.0) 1.0) s-v1 :triangle "#000000")
                    (draw-ci-or-pts (+ cx (/ bw 2.0) 1.0) s-v2 :circle "#000000")
                    (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"8\" fill=\"#000000\" text-anchor=\"middle\">%+.3f</text>\n"
                                     cx (- (min y1 y2-top) 5.0) delta)))
                  (let [bw 20.0
                        v2-m (:mean-test-hv s-v2)
                        v2-ci (double (or (:ci95-halfwidth s-v2) 0.0))
                        y2 (py v2-m)
                        y2-top (py (+ (double v2-m) v2-ci))
                        col (if (= arm :formula-only) (:blue palette) (:purple palette))]
                    (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" fill-opacity=\"0.85\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n"
                                     (- cx (/ bw 2.0)) y2 bw (max 2.0 (- (+ y0 ph) y2)) col))
                    (draw-ci-or-pts cx s-v2 :diamond "#000000")
                    (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"8\" fill=\"#000000\" text-anchor=\"middle\">%.3f</text>\n"
                                     cx (- y2-top 5.0) (double v2-m)))))
                (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" text-anchor=\"middle\">%s</text>\n"
                                 cx (+ y0 ph 15) label))))
            (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9.5\" fill=\"#000000\" text-anchor=\"middle\" transform=\"rotate(-90 %d %.2f)\">Mean Test Normalized HV</text>\n"
                             (- x0 42) (+ y0 (/ ph 2.0)) (- x0 42) (+ y0 (/ ph 2.0))))))]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"174mm\" height=\"75.5mm\" font-family=\"Helvetica, Arial, sans-serif\">\n" w h))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\"/>\n" w h))
    (let [p-sums (get-in two-rep-edn [:domains :portfolio :arm-summaries])
          p-vals (mapcat :replication-test-hvs p-sums)
          p-min (max 0.0 (- (apply min p-vals) 0.03))
          p-max (min 1.0 (+ (apply max p-vals) 0.025))
          s-sums (get-in two-rep-edn [:domains :scheduling :arm-summaries])
          s-vals (mapcat :replication-test-hvs s-sums)
          s-min (max 0.0 (- (apply min s-vals) 0.015))
          s-max (min 1.0 (+ (apply max s-vals) 0.015))]
      (render-panel 58 26 305 236 "(a)" "NASDAQ-50 Portfolio (N=50, K=5, T=90)" p-min p-max p-sums)
      (render-panel 435 26 305 236 "(b)" "OR-Library wt40 Scheduling (40 Jobs)" s-min s-max s-sums))
    ;; Shared Legend
    (let [ly 290]
      (.append sb (fmt "  <rect x=\"58\" y=\"%d\" width=\"682\" height=\"26\" fill=\"#ffffff\" stroke=\"#cccccc\" stroke-width=\"0.8\"/>\n"
                       ly))
      (.append sb (fmt "  <rect x=\"72\" y=\"%d\" width=\"11\" height=\"11\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n" (+ ly 7) (:amber palette)))
      (.append sb (fmt "  <text x=\"88\" y=\"%d\" font-size=\"9\" fill=\"#000000\" dominant-baseline=\"middle\">Baseline Prior (v1)</text>\n" (+ ly 14)))
      (.append sb (fmt "  <rect x=\"215\" y=\"%d\" width=\"11\" height=\"11\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n" (+ ly 7) (:emerald palette)))
      (.append sb (fmt "  <text x=\"231\" y=\"%d\" font-size=\"9\" fill=\"#000000\" dominant-baseline=\"middle\">Budget-Aware Prior (v2, 30 seeds, 95%% CI)</text>\n" (+ ly 14)))
      (.append sb (fmt "  <rect x=\"480\" y=\"%d\" width=\"11\" height=\"11\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n" (+ ly 7) (:blue palette)))
      (.append sb (fmt "  <rect x=\"495\" y=\"%d\" width=\"11\" height=\"11\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n" (+ ly 7) (:purple palette)))
      (.append sb (fmt "  <text x=\"512\" y=\"%d\" font-size=\"9\" fill=\"#000000\" dominant-baseline=\"middle\">Formula-Only (A4) / Conventional (A5)</text>\n" (+ ly 14))))
    (.append sb "</svg>\n")
    (io/make-parents out-path)
    (spit out-path (.toString sb) :encoding "UTF-8")
    (println "Wrote Springer SVG:" out-path)))

(defn generate-fig4-budget-scaling!
  "Generate Fig 4: Anytime Proposal-Budget Scaling [16, 32, 64, 128, 256] with distinct
   grayscale-safe marker shapes and dash patterns."
  [scaling-edn out-path]
  (let [w 760 h 335
        budgets [16 32 64 128 256]
        sb (StringBuilder.)
        render-panel
        (fn [x0 y0 pw ph panel-lbl title y-min y-max domain-map]
          (let [arm-curves (:arm-curves domain-map)
                ctrl-curves (:control-curves domain-map)
                find-arm (fn [arm prior] (first (filter #(and (= arm (:arm %)) (= prior (:generator-prior %))) arm-curves)))
                find-ctrl (fn [ctrl] (first (filter #(= ctrl (:control %)) ctrl-curves)))
                y-span (- (double y-max) (double y-min))
                py (fn [v] (+ y0 (- ph (* (/ (- (double v) (double y-min)) y-span) ph))))
                px (fn [b]
                     (let [idx (/ (- (/ (Math/log (double b)) (Math/log 2.0)) 4.0) 4.0)]
                       (+ x0 22.0 (* idx (- pw 44.0)))))
                series
                [{:label "A1: GP-Mixed (v2)"   :color (:emerald palette) :stroke (:emerald-dk palette) :dash nil     :shape :circle   :data (find-arm :gp-mixed :budget-aware-v2)}
                 {:label "A2: GP-Mut (v2)"     :color (:sky palette)     :stroke (:sky-dk palette)     :dash "6,2"   :shape :square   :data (find-arm :gp-mutation-only :budget-aware-v2)}
                 {:label "A4: Formula-Only"    :color (:blue palette)    :stroke (:blue-dk palette)    :dash "2,2"   :shape :diamond  :data (find-arm :formula-only :budget-aware-v2)}
                 {:label "A5: Conventional"    :color (:purple palette)  :stroke (:purple-dk palette)  :dash "5,2,1,2" :shape :triangle :data (find-arm :configured-conventional :budget-aware-v2)}
                 {:label "A1: GP-Mixed (v1)"   :color (:amber palette)   :stroke (:amber-dk palette)   :dash "3,3"   :shape :triangle :data (find-arm :gp-mixed :development-only-v1)}
                 {:label "Random Search"       :color (:slate palette)   :stroke (:slate-dk palette)   :dash "1.5,2.5" :shape :cross  :data (find-ctrl :random-search)}]]
            (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"10.5\" font-weight=\"700\" fill=\"#000000\">%s <tspan font-weight=\"600\">%s</tspan></text>\n"
                             x0 (- y0 8) panel-lbl (svg-escape title)))
            (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n"
                             x0 y0 pw ph))
            (doseq [i (range 6)
                    :let [yv (+ y-min (* (/ (double i) 5.0) y-span))
                          cy (py yv)]]
              (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"0.7\"/>\n"
                               x0 cy (+ x0 pw) cy (:grid palette)))
              (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9\" fill=\"#111111\" text-anchor=\"end\" dominant-baseline=\"middle\">%.3f</text>\n"
                               (- x0 6) cy yv)))
            (doseq [b budgets
                    :let [cx (px b)]]
              (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%d\" x2=\"%.2f\" y2=\"%d\" stroke=\"%s\" stroke-width=\"0.7\"/>\n"
                               cx y0 cx (+ y0 ph) (:grid palette)))
              (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"9\" fill=\"#000000\" text-anchor=\"middle\">%d</text>\n"
                               cx (+ y0 ph 15) b)))
            (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"9.5\" fill=\"#000000\" text-anchor=\"middle\">Solution Evaluations B (log scale)</text>\n"
                             (+ x0 (/ pw 2.0)) (+ y0 ph 31)))
            (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9.5\" fill=\"#000000\" text-anchor=\"middle\" transform=\"rotate(-90 %d %.2f)\">Mean Test Normalized HV</text>\n"
                             (- x0 42) (+ y0 (/ ph 2.0)) (- x0 42) (+ y0 (/ ph 2.0))))
            (doseq [{:keys [color stroke dash shape data]} series
                    :when data
                    :let [pts (mapv (fn [b] [(px b) (py (get-in data [:by-budget b :mean-hv]))]) budgets)
                          poly (str/join " " (map (fn [[x y]] (fmt "%.2f,%.2f" x y)) pts))]]
              (.append sb (fmt "  <polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.6\"%s/>\n"
                               poly color (if dash (fmt " stroke-dasharray=\"%s\"" dash) "")))
              (doseq [[x y] pts]
                (.append sb (draw-marker shape x y 2.9 color stroke 0.9 1.0))))))]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"174mm\" height=\"76.7mm\" font-family=\"Helvetica, Arial, sans-serif\">\n" w h))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\"/>\n" w h))
    (let [all-curve-hvs (fn [dmap]
                          (concat
                           (for [r (:arm-curves dmap) b budgets] (get-in r [:by-budget b :mean-hv]))
                           (for [c (:control-curves dmap) b budgets] (get-in c [:by-budget b :mean-hv]))))
          p-map (get-in scaling-edn [:domains :portfolio])
          p-hvs (remove nil? (all-curve-hvs p-map))
          p-min (max 0.0 (- (apply min p-hvs) 0.02))
          p-max (min 1.0 (+ (apply max p-hvs) 0.02))
          s-map (get-in scaling-edn [:domains :scheduling])
          s-hvs (remove nil? (all-curve-hvs s-map))
          s-min (max 0.0 (- (apply min s-hvs) 0.015))
          s-max (min 1.0 (+ (apply max s-hvs) 0.015))]
      (render-panel 58 26 305 228 "(a)" "NASDAQ-50 Portfolio (N=50, K=5, T=90)" p-min p-max p-map)
      (render-panel 435 26 305 228 "(b)" "OR-Library wt40 Scheduling (40 Jobs)" s-min s-max s-map))
    ;; Shared Legend
    (let [ly 295
          items [["A1: GP-Mixed (v2)" (:emerald palette) (:emerald-dk palette) :circle]
                 ["A2: GP-Mut (v2)" (:sky palette) (:sky-dk palette) :square]
                 ["A4: Formula-Only" (:blue palette) (:blue-dk palette) :diamond]
                 ["A5: Conventional" (:purple palette) (:purple-dk palette) :triangle]
                 ["A1: GP-Mixed (v1)" (:amber palette) (:amber-dk palette) :triangle]
                 ["Random Search" (:slate palette) (:slate-dk palette) :cross]]]
      (.append sb (fmt "  <rect x=\"58\" y=\"%d\" width=\"682\" height=\"26\" fill=\"#ffffff\" stroke=\"#cccccc\" stroke-width=\"0.8\"/>\n"
                       ly))
      (doseq [[idx [lbl col stk shp]] (map-indexed vector items)
              :let [lx (+ 72 (* idx 112))]]
        (.append sb (draw-marker shp lx (+ ly 13) 3.0 col stk 0.9 1.0))
        (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" dominant-baseline=\"middle\">%s</text>\n"
                         (+ lx 8) (+ ly 14) lbl))))
    (.append sb "</svg>\n")
    (io/make-parents out-path)
    (spit out-path (.toString sb) :encoding "UTF-8")
    (println "Wrote Springer SVG:" out-path)))

(defn generate-fig5-mechanism-ablations!
  "Generate Fig 5: Three-panel Secondary Mechanism Ablations:
   (a) Typed vs Untyped+Validated Attempt Throughput
   (b) Surgical AST Knockouts (K0..K6 on Portfolio)
   (c) Inner Cache-On vs Cache-Off Evaluation Savings."
  [typed-edn knockout-edn cache-edn out-path]
  (let [w 760 h 305
        sb (StringBuilder.)]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"174mm\" height=\"69.8mm\" font-family=\"Helvetica, Arial, sans-serif\">\n" w h))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\"/>\n" w h))

    ;; Panel (a): Typed vs Untyped Per-Attempt Hit Rate (%)
    (let [x0 48 y0 26 pw 198 ph 210
          py (fn [pct] (+ y0 (- ph (* (/ (double pct) 100.0) ph))))
          micro (get-in typed-edn [:microbenchmarks :portfolio])
          find-m (fn [op mode] (first (filter #(and (= op (:operator %)) (= mode (:mode %))) micro)))
          ops [[:generate "Generate"] [:mutate "Mutate"] [:crossover "Crossover"]]
          col-w (/ (double pw) 3.0)]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"10\" font-weight=\"700\" fill=\"#000000\">(a) <tspan font-weight=\"600\">Typed vs. Untyped Hit Rate</tspan></text>\n"
                       x0 (- y0 8)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n"
                       x0 y0 pw ph))
      (doseq [i (range 6) :let [yv (* i 20.0) cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"0.7\"/>\n" x0 cy (+ x0 pw) cy (:grid palette)))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"8.5\" fill=\"#111111\" text-anchor=\"end\" dominant-baseline=\"middle\">%.0f%%</text>\n" (- x0 5) cy yv)))
      (doseq [[idx [op lbl]] (map-indexed vector ops)
              :let [cx (+ x0 (* (+ idx 0.5) col-w))
                    bw 15.0
                    mt (or (find-m op :typed-budget-aware-v2) (find-m op :typed-v1-v2))
                    mu (find-m op :untyped-validated)
                    rt (:attempt-success-rate-pct mt)
                    ru (:attempt-success-rate-pct mu)
                    yt (py rt) yu (py ru)]]
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n"
                         (- cx bw 1.0) yt bw (max 2.0 (- (+ y0 ph) yt)) (:emerald palette)))
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n"
                         (+ cx 1.0) yu bw (max 2.0 (- (+ y0 ph) yu)) (:vermilion palette)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"7.5\" fill=\"#000000\" text-anchor=\"middle\">%.0f%%</text>\n" (- cx (/ bw 2.0)) (- yt 4.0) rt))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"7.5\" fill=\"#000000\" text-anchor=\"middle\">%.0f%%</text>\n" (+ cx (/ bw 2.0)) (- yu 4.0) ru))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" text-anchor=\"middle\">%s</text>\n" cx (+ y0 ph 15) lbl))))

    ;; Panel (b): Surgical AST Knockouts (K0..K6 on Portfolio GP-Mixed & GP-Mutation)
    (let [x0 286 y0 26 pw 228 ph 210
          rows (get-in knockout-edn [:domains :portfolio])
          gp-m (first (filter #(= :gp-mixed (:arm %)) rows))
          ks (:knockout-summaries gp-m)
          y-min 0.0
          y-max (min 1.0 (* 1.15 (double (apply max (map :mean-hv ks)))))
          py (fn [v] (+ y0 (- ph (* (/ (- (double (max y-min v)) y-min) (- y-max y-min)) ph))))
          labels ["K0" "K1" "K2" "K3" "K4" "K5" "K6"]
          col-w (/ (double pw) (count ks))]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"10\" font-weight=\"700\" fill=\"#000000\">(b) <tspan font-weight=\"600\">Surgical AST Knockouts (HV)</tspan></text>\n"
                       x0 (- y0 8)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n"
                       x0 y0 pw ph))
      (doseq [i (range 5) :let [yv (* i 0.18) cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"0.7\"/>\n" x0 cy (+ x0 pw) cy (:grid palette)))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"8.5\" fill=\"#111111\" text-anchor=\"end\" dominant-baseline=\"middle\">%.2f</text>\n" (- x0 5) cy yv)))
      (doseq [[idx k] (map-indexed vector ks)
              :let [cx (+ x0 (* (+ idx 0.5) col-w))
                    bw 18.0
                    v (:mean-hv k)
                    yk (py v)]]
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n"
                         (- cx (/ bw 2.0)) yk bw (max 2.0 (- (+ y0 ph) yk))
                         (if (zero? idx) (:emerald palette) (:sky palette))))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"7.5\" fill=\"#000000\" text-anchor=\"middle\">%.2f</text>\n"
                         cx (- yk 4.0) v))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" text-anchor=\"middle\">%s</text>\n"
                         cx (+ y0 ph 15) (nth labels idx)))))

    ;; Panel (c): Cache-Off vs Cache-On Physical Objective Evaluations (k)
    (let [x0 554 y0 26 pw 192 ph 210
          y-max 65.0
          py (fn [v] (+ y0 (- ph (* (/ (double v) y-max) ph))))
          port-v2 (filterv #(= :budget-aware-v2 (:generator-prior %)) (get-in cache-edn [:domains :portfolio]))
          arms [[:gp-mixed "A1"] [:gp-mutation-only "A2"] [:random-dsl "A3"] [:formula-only "A4"] [:configured-conventional "A5"]]
          col-w (/ (double pw) 5.0)]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"10\" font-weight=\"700\" fill=\"#000000\">(c) <tspan font-weight=\"600\">Cache Eval Savings (|&#916;HV|=0)</tspan></text>\n"
                       x0 (- y0 8)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n"
                       x0 y0 pw ph))
      (doseq [i (range 6) :let [yv (* i 13.0) cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"0.7\"/>\n" x0 cy (+ x0 pw) cy (:grid palette)))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"8.5\" fill=\"#111111\" text-anchor=\"end\" dominant-baseline=\"middle\">%.0fk</text>\n" (- x0 5) cy yv)))
      (doseq [[idx [arm lbl]] (map-indexed vector arms)
              :let [row (first (filter #(= arm (:arm %)) port-v2))
                    cx (+ x0 (* (+ idx 0.5) col-w))
                    bw 12.0
                    c-off (/ (double (:mean-obj-calcs-cache-off row)) 1000.0)
                    c-on (/ (double (:mean-obj-calcs-cache-on row)) 1000.0)
                    pct (:cache-hit-rate-pct row)
                    y-off (py c-off) y-on (py c-on)]]
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n"
                         (- cx bw 1.0) y-off bw (- (+ y0 ph) y-off) (:slate palette)))
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.7\"/>\n"
                         (+ cx 1.0) y-on bw (- (+ y0 ph) y-on) (:emerald palette)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"7.5\" fill=\"#000000\" text-anchor=\"middle\">-%.0f%%</text>\n"
                         cx (- (min y-off y-on) 4.0) pct))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" text-anchor=\"middle\">%s</text>\n"
                         cx (+ y0 ph 15) lbl))))

    ;; Shared Bottom Legend / Key for (a), (b), (c)
    (let [ly 264]
      (.append sb (fmt "  <rect x=\"48\" y=\"%d\" width=\"698\" height=\"26\" fill=\"#ffffff\" stroke=\"#cccccc\" stroke-width=\"0.8\"/>\n"
                       ly))
      (.append sb (fmt "  <text x=\"60\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" dominant-baseline=\"middle\"><tspan font-weight=\"700\">Knockout Key (b):</tspan> K0=Intact, K1=No Body (-0.38 HV), K2=Rand Init, K3=Blind Score (-0.11 HV), K4=Const=1, K5=Static Prog, K6=Obj0 Only (-0.02 HV)</text>\n"
                       (+ ly 14))))
    (.append sb "</svg>\n")
    (io/make-parents out-path)
    (spit out-path (.toString sb) :encoding "UTF-8")
    (println "Wrote Springer SVG:" out-path)))


(defn generate-fig6-evolution-and-ast-tally!
  "Generate Fig 6: (a) 30-Seed Outer Meta-Evolution Trajectory (Batch 0..7) and
   (b) Syntactic Operator & Primitive Frequency Tally Across the 30 Final Programs
   (directly continuing Figs. 5 & 7 of Penchev & Marchev's Springer LNCS paper)."
  [g8-edn out-path]
  (let [w 760 h 325
        sb (StringBuilder.)]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"174mm\" height=\"74.4mm\" font-family=\"Helvetica, Arial, sans-serif\">\n" w h))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\"/>\n" w h))

    ;; Panel (a): 30-Seed Meta-Evolution Best-So-Far Training HV Trajectory (Batches 0..7)
    (let [x0 58 y0 26 pw 302 ph 224
          port-summaries (get-in g8-edn [:domains :portfolio :arm-summaries])
          get-s (fn [arm] (first (filter #(and (= arm (:arm %)) (= :budget-aware-v2 (:generator-prior %))) port-summaries)))
          batch-means
          (fn [arm]
            (let [rs (filter :batch-trajectory (:replications (get-s arm)))]
              (when (seq rs)
                (mapv (fn [b]
                        (let [vals (map #(get-in % [:batch-trajectory b :best-train-hv-so-far]) rs)]
                          (/ (reduce + 0.0 vals) (double (count vals)))))
                      (range 8)))))
          all-bms (mapcat #(or (batch-means %) []) [:gp-mixed :gp-mutation-only :formula-only])
          y-min (if (seq all-bms) (- (apply min all-bms) 0.008) 0.595)
          y-max (if (seq all-bms) (+ (apply max all-bms) 0.008) 0.665)
          y-span (- y-max y-min)
          py (fn [v] (+ y0 (- ph (* (/ (- (double (max y-min v)) y-min) y-span) ph))))
          px (fn [b] (+ x0 16.0 (* (/ (double b) 7.0) (- pw 32.0))))
          series [[:gp-mixed "A1: GP-Mixed" (:emerald palette) (:emerald-dk palette) nil :circle]
                  [:gp-mutation-only "A2: GP-Mut" (:sky palette) (:sky-dk palette) "6,2" :square]
                  [:formula-only "A4: Formula" (:blue palette) (:blue-dk palette) "2,2" :diamond]]]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"10.5\" font-weight=\"700\" fill=\"#000000\">(a) <tspan font-weight=\"600\">Meta-Evolution Trajectory (30 Seeds)</tspan></text>\n"
                       x0 (- y0 8)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n"
                       x0 y0 pw ph))
      (doseq [i (range 6)
              :let [yv (+ y-min (* (/ (double i) 5.0) y-span))
                    cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"0.7\"/>\n"
                         x0 cy (+ x0 pw) cy (:grid palette)))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9\" fill=\"#111111\" text-anchor=\"end\" dominant-baseline=\"middle\">%.3f</text>\n"
                         (- x0 5) cy yv)))
      (doseq [b (range 8) :let [cx (px b)]]
        (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%d\" x2=\"%.2f\" y2=\"%d\" stroke=\"%s\" stroke-width=\"0.7\"/>\n"
                         cx y0 cx (+ y0 ph) (:grid palette)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"9\" fill=\"#000000\" text-anchor=\"middle\">%d</text>\n"
                         cx (+ y0 ph 15) b)))
      (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"9.5\" fill=\"#000000\" text-anchor=\"middle\">Generation / Batch (32 Programs / Batch)</text>\n"
                       (+ x0 (/ pw 2.0)) (+ y0 ph 31)))
      (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9.5\" fill=\"#000000\" text-anchor=\"middle\" transform=\"rotate(-90 %d %.2f)\">Best-So-Far Training Norm. HV</text>\n"
                       (- x0 42) (+ y0 (/ ph 2.0)) (- x0 42) (+ y0 (/ ph 2.0))))
      (doseq [[arm _ col stk dash shp] series
              :let [bms (batch-means arm)]
              :when bms
              :let [pts (mapv (fn [b] [(px b) (py (nth bms b))]) (range 8))
                    poly (str/join " " (map (fn [[x y]] (fmt "%.2f,%.2f" x y)) pts))]]
        (.append sb (fmt "  <polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.7\"%s/>\n"
                         poly col (if dash (fmt " stroke-dasharray=\"%s\"" dash) "")))
        (doseq [[x y] pts]
          (.append sb (draw-marker shp x y 2.9 col stk 0.9 1.0)))))

    ;; Panel (b): Syntactic Operator & Feature Tally Across 30 Final Evolved Programs (Portfolio vs Scheduling)
    (let [x0 448 y0 26 pw 294 ph 224
          port-gp (first (filter #(and (= :gp-mixed (:arm %)) (= :budget-aware-v2 (:generator-prior %)))
                                 (get-in g8-edn [:domains :portfolio :arm-summaries])))
          sched-gp (first (filter #(and (= :gp-mixed (:arm %)) (= :budget-aware-v2 (:generator-prior %)))
                                  (get-in g8-edn [:domains :scheduling :arm-summaries])))
          p-counts (get-in port-gp [:ast-tally :feature-counts])
          s-counts (get-in sched-gp [:ast-tally :feature-counts])
          features [[:objective-0 ":objective-0"]
                    [:objective-1 ":objective-1"]
                    [:add         ":add / :multiply"]
                    [:progress    ":progress (anneal)"]
                    [:swap        ":operator :swap"]
                    [:insert      ":operator :insert"]
                    [:restart     ":operator :restart"]
                    [:first       ":initializer :first"]]
          row-h (/ (double ph) (count features))
          px (fn [cnt] (+ x0 (* (/ (double cnt) 30.0) (- pw 24.0))))]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"10.5\" font-weight=\"700\" fill=\"#000000\">(b) <tspan font-weight=\"600\">Tallied Across the 30 Final Programs</tspan></text>\n"
                       x0 (- y0 8)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n"
                       x0 y0 pw ph))
      (doseq [cnt [0 10 20 30] :let [cx (px cnt)]]
        (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%d\" x2=\"%.2f\" y2=\"%d\" stroke=\"%s\" stroke-width=\"0.7\"/>\n"
                         cx y0 cx (+ y0 ph) (:grid palette)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"9\" fill=\"#111111\" text-anchor=\"middle\">%d</text>\n"
                         cx (+ y0 ph 15) cnt)))
      (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"9.5\" fill=\"#000000\" text-anchor=\"middle\">Number of Final Programs (of 30)</text>\n"
                       (+ x0 (/ pw 2.0)) (+ y0 ph 31)))
      (doseq [[idx [kw lbl]] (map-indexed vector features)
              :let [cy (+ y0 (* (+ idx 0.5) row-h))
                    bh 8.5
                    pv (long (or (get p-counts kw) 0))
                    sv (long (or (get s-counts kw) 0))
                    pw-bar (- (px pv) x0)
                    sw-bar (- (px sv) x0)]]
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"8.5\" font-family=\"Consolas, monospace\" fill=\"#000000\" text-anchor=\"end\" dominant-baseline=\"middle\">%s</text>\n"
                         (- x0 6) cy lbl))
        (.append sb (fmt "  <rect x=\"%d\" y=\"%.2f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n"
                         x0 (- cy bh 0.5) (max 1.5 pw-bar) bh (:emerald palette)))
        (.append sb (fmt "  <rect x=\"%d\" y=\"%.2f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n"
                         x0 (+ cy 0.5) (max 1.5 sw-bar) bh (:sky palette)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"7.5\" fill=\"#000000\">%d</text>\n"
                         (+ (px pv) 3.0) (- cy 2.0) pv))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"7.5\" fill=\"#000000\">%d</text>\n"
                         (+ (px sv) 3.0) (+ cy 8.0) sv))))

    ;; Shared Bottom Legend
    (let [ly 287]
      (.append sb (fmt "  <rect x=\"58\" y=\"%d\" width=\"684\" height=\"26\" fill=\"#ffffff\" stroke=\"#cccccc\" stroke-width=\"0.8\"/>\n"
                       ly))
      (.append sb (draw-marker :circle 76 (+ ly 13) 3.0 (:emerald palette) (:emerald-dk palette) 0.9 1.0))
      (.append sb (fmt "  <text x=\"85\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" dominant-baseline=\"middle\">(a) A1: GP-Mixed (+0.031 Train HV)</text>\n" (+ ly 14)))
      (.append sb (draw-marker :square 260 (+ ly 13) 3.0 (:sky palette) (:sky-dk palette) 0.9 1.0))
      (.append sb (fmt "  <text x=\"269\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" dominant-baseline=\"middle\">A2: GP-Mut (+0.031)</text>\n" (+ ly 14)))
      (.append sb (fmt "  <rect x=\"388\" y=\"%d\" width=\"11\" height=\"11\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n" (+ ly 7) (:emerald palette)))
      (.append sb (fmt "  <text x=\"404\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" dominant-baseline=\"middle\">(b) NASDAQ-50 Portfolio (30/30 Unique)</text>\n" (+ ly 14)))
      (.append sb (fmt "  <rect x=\"585\" y=\"%d\" width=\"11\" height=\"11\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n" (+ ly 7) (:sky palette)))
      (.append sb (fmt "  <text x=\"601\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" dominant-baseline=\"middle\">wt40 Scheduling (30/30)</text>\n" (+ ly 14))))
    (.append sb "</svg>\n")
    (io/make-parents out-path)
    (spit out-path (.toString sb) :encoding "UTF-8")
    (println "Wrote Springer SVG:" out-path)))

(defn generate-fig1b-workflow!
  "Generate clean ISO-5807 monochrome academic flowchart showing the two-level
   outer GP meta-evolution, static role/effect validator, bounded interpreter,
   validation selection, and single-shot held-out testing pipeline."
  [out-path]
  (let [w 760 h 392
        sb (StringBuilder.)
        box (fn [x y bw bh title sub & [fill dash]]
              (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"#111111\" stroke-width=\"1.0\"%s/>\n"
                               x y bw bh (or fill "#ffffff") (if dash (fmt " stroke-dasharray=\"%s\"" dash) "")))
              (if (and sub (not (str/blank? sub)))
                (do
                  (.append sb (fmt "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"9\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\">%s</text>\n"
                                   (+ x (/ bw 2.0)) (+ y (/ bh 2.0) -4.0) (svg-escape title)))
                  (.append sb (fmt "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"7.8\" fill=\"#222222\" text-anchor=\"middle\">%s</text>\n"
                                   (+ x (/ bw 2.0)) (+ y (/ bh 2.0) 9.0) (svg-escape sub))))
                (.append sb (fmt "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"9\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\" dominant-baseline=\"middle\">%s</text>\n"
                                 (+ x (/ bw 2.0)) (+ y (/ bh 2.0)) (svg-escape title)))))
        term (fn [x y bw bh title sub]
               (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"18\" ry=\"18\" fill=\"#ffffff\" stroke=\"#111111\" stroke-width=\"1.2\"/>\n"
                                x y bw bh))
               (.append sb (fmt "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"9\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\">%s</text>\n"
                                (+ x (/ bw 2.0)) (+ y (/ bh 2.0) -3.5) (svg-escape title)))
               (.append sb (fmt "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"7.8\" fill=\"#222222\" text-anchor=\"middle\">%s</text>\n"
                                (+ x (/ bw 2.0)) (+ y (/ bh 2.0) 8.5) (svg-escape sub))))
        diamond (fn [cx cy rx ry title]
                  (.append sb (fmt "  <polygon points=\"%d,%d %d,%d %d,%d %d,%d\" fill=\"#ffffff\" stroke=\"#111111\" stroke-width=\"1.0\"/>\n"
                                   cx (- cy ry) (+ cx rx) cy cx (+ cy ry) (- cx rx) cy))
                  (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"8.5\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\" dominant-baseline=\"middle\">%s</text>\n"
                                   cx cy (svg-escape title))))
        arrow (fn [pts-str]
                (.append sb (fmt "  <polyline points=\"%s\" fill=\"none\" stroke=\"#111111\" stroke-width=\"1.0\" marker-end=\"url(#flow-arrow)\"/>\n" pts-str)))]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"174mm\" height=\"89.7mm\" font-family=\"Arial, Helvetica, sans-serif\">\n" w h))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\"/>\n" w h))
    (.append sb "  <defs>\n    <marker id=\"flow-arrow\" viewBox=\"0 0 10 10\" refX=\"8.5\" refY=\"5\" markerWidth=\"5.5\" markerHeight=\"5.5\" orient=\"auto-start-reverse\">\n      <path d=\"M 0 1.5 L 8.5 5 L 0 8.5 z\" fill=\"#111111\"/>\n    </marker>\n  </defs>\n")

    ;; Top Outer Feedback Step (Batches 1..7, Slots 32..255)
    (box 286 14 252 46 "Select & Vary Next Batch (32 Slots)" "Binary tournament (k=2); crossover p=0.5 / mut")
    (arrow "286,37 254,37 254,124")

    ;; Main Horizontal Training Pipeline (Row 2)
    (term 14 105 76 38 "Start" "Seed 0–29")
    (arrow "90,124 108,124")
    (box 110 101 128 46 "Gen 0 (Slots 0–31)" "Sample v2 prior ASTs")
    (arrow "238,124 270,124")
    (box 272 101 140 46 "Static Validator" "6 Roles, Effects, ≤128 nodes")
    (arrow "412,124 438,124")
    (diamond 492 124 52 27 "Valid AST?")
    (.append sb "  <text x=\"560\" y=\"118\" font-size=\"8\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\">Yes</text>\n")
    (arrow "544,124 576,124")

    ;; Fallback branch below Valid AST?
    (arrow "492,151 492,167")
    (.append sb "  <text x=\"472\" y=\"161\" font-size=\"7.5\" fill=\"#111111\" text-anchor=\"middle\">No (>10)</text>\n")
    (box 428 169 128 28 "Parent Fallback" "Consumes 1 outer slot")
    (arrow "556,183 602,183 602,149")

    ;; Train Evaluation & Batch Loop Check
    (box 578 101 138 46 "Train Evaluation" "6 Train inst. × 2 seeds")
    (arrow "647,147 647,175")
    (diamond 647 204 52 27 "Batch < 7?")
    (.append sb "  <text x=\"722\" y=\"199\" font-size=\"8\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\">Yes</text>\n")
    (arrow "699,204 744,204 744,37 540,37")

    ;; Validation, Single-Shot Test, Statistical Inference, End
    (.append sb "  <text x=\"672\" y=\"245\" font-size=\"8\" font-weight=\"700\" fill=\"#000000\" text-anchor=\"middle\">No (256)</text>\n")
    (arrow "647,231 647,255")
    (box 562 257 172 44 "Validation (Pick Top-1)" "Top-8 Train → 6 Val × 2 seeds")
    (arrow "647,301 647,325")
    (box 562 327 172 44 "Frozen Held-Out Test" "Single-shot 24 Test × 10 seeds")
    (arrow "562,349 532,349")
    (box 356 327 174 44 "Statistical Analysis" "10k Bootstrap & Holm (α=0.05)")
    (arrow "356,349 324,349")
    (term 234 330 88 38 "End" "Final Fronts")

    ;; Dashed Inset Box: Inside Every Base-Level Interpreter Execution Call
    (.append sb "  <rect x=\"14\" y=\"212\" width=\"516\" height=\"102\" fill=\"#f7f7f7\" stroke=\"#333333\" stroke-width=\"0.9\" stroke-dasharray=\"4,3\"/>\n")
    (.append sb "  <text x=\"24\" y=\"229\" font-size=\"8.8\" font-weight=\"700\" fill=\"#000000\">Inside Each Train, Validation &amp; Test Run: Base-Level Bounded Interpreter</text>\n")
    (box 24 244 106 50 "1. Initialize" ":random / :first" "#ffffff" nil)
    (arrow "130,269 148,269")
    (box 150 244 126 50 "2. Search / Step" "B ≤ 256, steps ≤ 4,096" "#ffffff" nil)
    (arrow "276,269 294,269")
    (box 296 244 114 50 "3. Cache Check" "Hit charges 1 prop." "#ffffff" nil)
    (arrow "410,269 428,269")
    (box 430 244 90 50 "4. Pareto HV" "2D Archive" "#ffffff" nil)

    (.append sb "</svg>\n")
    (io/make-parents out-path)
    (spit out-path (.toString sb) :encoding "UTF-8")
    (println "Wrote Springer SVG:" out-path)))

(defn generate-fig3b-runtime!
  "Generate 2-panel Runtime Comparison across all 5 search arms:
   (a) Base-level per-instance execution time (ms, 240 held-out runs, cache ON),
   (b) One-time outer design / validation-tuning time per seed (s, Mean +/- SD, n=30)."
  [g8-edn cache-edn out-path]
  (let [w 760 h 325
        sb (StringBuilder.)
        arms [[:gp-mixed "A1: GP-Mix"]
              [:gp-mutation-only "A2: GP-Mut"]
              [:random-dsl "A3: Rand-DSL"]
              [:formula-only "A4: Formula"]
              [:configured-conventional "A5: Conv."]]
        mean-fn (fn [xs] (/ (reduce + 0.0 xs) (double (max 1 (count xs)))))
        sd-fn (fn [xs]
                (let [n (count xs) m (mean-fn xs)]
                  (if (> n 1)
                    (Math/sqrt (/ (reduce + 0.0 (map #(Math/pow (- (double %) m) 2) xs)) (double (dec n))))
                    0.0)))
        get-inner-ms (fn [domain arm]
                       (let [row (first (filter #(and (= arm (:arm %)) (= :budget-aware-v2 (:generator-prior %)))
                                                (get-in cache-edn [:domains domain])))]
                         (/ (double (:mean-elapsed-ms-cache-on row)) 240.0)))
        get-outer-s (fn [domain arm]
                      (let [row (first (filter #(and (= arm (:arm %)) (= :budget-aware-v2 (:generator-prior %)))
                                               (get-in g8-edn [:domains domain :arm-summaries])))
                            xs (mapv :elapsed-seconds (:replications row))]
                        [(mean-fn xs) (sd-fn xs)]))]
    (.append sb (fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"174mm\" height=\"74.4mm\" font-family=\"Arial, Helvetica, sans-serif\">\n" w h))
    (.append sb (fmt "  <rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#ffffff\"/>\n" w h))

    ;; Panel (a): Base-Level Per-Instance Execution Time (ms)
    (let [x0 58 y0 28 pw 302 ph 212
          y-max 750.0
          py (fn [v] (+ y0 (- ph (* (/ (min y-max (max 0.0 (double v))) y-max) ph))))
          grp-w (/ (double pw) (count arms))
          bw 21.0]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"10.5\" font-weight=\"700\" fill=\"#000000\">(a) <tspan font-weight=\"600\">Execution Time per Instance (ms)</tspan></text>\n"
                       x0 (- y0 8)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n"
                       x0 y0 pw ph))
      (doseq [yv [0 150 300 450 600 750] :let [cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"0.7\"/>\n"
                         x0 cy (+ x0 pw) cy (:grid palette)))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9\" fill=\"#111111\" text-anchor=\"end\" dominant-baseline=\"middle\">%d</text>\n"
                         (- x0 5) cy yv)))
      (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9.5\" fill=\"#000000\" text-anchor=\"middle\" transform=\"rotate(-90 %d %.2f)\">Runtime per Instance (ms)</text>\n"
                       (- x0 42) (+ y0 (/ ph 2.0)) (- x0 42) (+ y0 (/ ph 2.0))))
      (doseq [[idx [arm lbl]] (map-indexed vector arms)
              :let [cx (+ x0 (* (+ idx 0.5) grp-w))
                    p-ms (get-inner-ms :portfolio arm)
                    s-ms (get-inner-ms :scheduling arm)
                    x-p (- cx bw 1.5)
                    x-s (+ cx 1.5)
                    y-p (py p-ms)
                    y-s (py s-ms)]]
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"8.3\" fill=\"#000000\" text-anchor=\"middle\">%s</text>\n"
                         cx (+ y0 ph 14) lbl))
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n"
                         x-p y-p bw (- (+ y0 ph) y-p) (:emerald palette)))
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n"
                         x-s y-s bw (- (+ y0 ph) y-s) (:sky palette)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"7.3\" font-weight=\"600\" fill=\"#000000\" text-anchor=\"middle\">%d</text>\n"
                         (+ x-p (/ bw 2.0)) (- y-p 3.5) (Math/round p-ms)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"7.3\" font-weight=\"600\" fill=\"#000000\" text-anchor=\"middle\">%d</text>\n"
                         (+ x-s (/ bw 2.0)) (- y-s 3.5) (Math/round s-ms)))))

    ;; Panel (b): Outer Design / Tuning Time per Seed (s, Mean +/- SD)
    (let [x0 442 y0 28 pw 302 ph 212
          y-max 3000.0
          py (fn [v] (+ y0 (- ph (* (/ (min y-max (max 0.0 (double v))) y-max) ph))))
          grp-w (/ (double pw) (count arms))
          bw 21.0]
      (.append sb (fmt "  <text x=\"%d\" y=\"%d\" font-size=\"10.5\" font-weight=\"700\" fill=\"#000000\">(b) <tspan font-weight=\"600\">Outer Design Time per Seed (s)</tspan></text>\n"
                       x0 (- y0 8)))
      (.append sb (fmt "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"0.9\"/>\n"
                       x0 y0 pw ph))
      (doseq [yv [0 600 1200 1800 2400 3000] :let [cy (py yv)]]
        (.append sb (fmt "  <line x1=\"%d\" y1=\"%.2f\" x2=\"%d\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"0.7\"/>\n"
                         x0 cy (+ x0 pw) cy (:grid palette)))
        (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9\" fill=\"#111111\" text-anchor=\"end\" dominant-baseline=\"middle\">%d</text>\n"
                         (- x0 5) cy yv)))
      (.append sb (fmt "  <text x=\"%d\" y=\"%.2f\" font-size=\"9.5\" fill=\"#000000\" text-anchor=\"middle\" transform=\"rotate(-90 %d %.2f)\">Outer Design Time (s, Mean ± SD)</text>\n"
                       (- x0 42) (+ y0 (/ ph 2.0)) (- x0 42) (+ y0 (/ ph 2.0))))
      (doseq [[idx [arm lbl]] (map-indexed vector arms)
              :let [cx (+ x0 (* (+ idx 0.5) grp-w))
                    [pm psd] (get-outer-s :portfolio arm)
                    [sm ssd] (get-outer-s :scheduling arm)
                    x-p (- cx bw 1.5)
                    x-s (+ cx 1.5)
                    y-p (py pm)
                    y-s (py sm)
                    p-hi (py (+ pm psd))
                    p-lo (py (max 0.0 (- pm psd)))
                    s-hi (py (+ sm ssd))
                    s-lo (py (max 0.0 (- sm ssd)))
                    mx-p (+ x-p (/ bw 2.0))
                    mx-s (+ x-s (/ bw 2.0))]]
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%d\" font-size=\"8.3\" fill=\"#000000\" text-anchor=\"middle\">%s</text>\n"
                         cx (+ y0 ph 14) lbl))
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n"
                         x-p y-p bw (- (+ y0 ph) y-p) (:emerald palette)))
        (.append sb (fmt "  <rect x=\"%.2f\" y=\"%.2f\" width=\"%.1f\" height=\"%.2f\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n"
                         x-s y-s bw (- (+ y0 ph) y-s) (:sky palette)))
        ;; Error whiskers +/- 1 SD
        (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n" mx-p p-hi mx-p p-lo))
        (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n" (- mx-p 3.5) p-hi (+ mx-p 3.5) p-hi))
        (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n" (- mx-p 3.5) p-lo (+ mx-p 3.5) p-lo))
        (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n" mx-s s-hi mx-s s-lo))
        (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n" (- mx-s 3.5) s-hi (+ mx-s 3.5) s-hi))
        (.append sb (fmt "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n" (- mx-s 3.5) s-lo (+ mx-s 3.5) s-lo))
        ;; Clean labels just above upper whisker cap
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"6.9\" font-weight=\"600\" fill=\"#000000\" text-anchor=\"middle\">%d</text>\n"
                         mx-p (- p-hi 3.5) (Math/round pm)))
        (.append sb (fmt "  <text x=\"%.2f\" y=\"%.2f\" font-size=\"6.9\" font-weight=\"600\" fill=\"#000000\" text-anchor=\"middle\">%d</text>\n"
                         mx-s (- s-hi 3.5) (Math/round sm)))))

    ;; Shared Bottom Legend
    (let [ly 280]
      (.append sb (fmt "  <rect x=\"58\" y=\"%d\" width=\"686\" height=\"26\" fill=\"#ffffff\" stroke=\"#cccccc\" stroke-width=\"0.8\"/>\n" ly))
      (.append sb (fmt "  <rect x=\"102\" y=\"%d\" width=\"11\" height=\"11\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n" (+ ly 7) (:emerald palette)))
      (.append sb (fmt "  <text x=\"118\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" dominant-baseline=\"middle\">NASDAQ-50 Portfolio (K=5, T=90)</text>\n" (+ ly 14)))
      (.append sb (fmt "  <rect x=\"316\" y=\"%d\" width=\"11\" height=\"11\" fill=\"%s\" stroke=\"#000000\" stroke-width=\"0.6\"/>\n" (+ ly 7) (:sky palette)))
      (.append sb (fmt "  <text x=\"332\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" dominant-baseline=\"middle\">OR-Library wt40 Scheduling (J=40)</text>\n" (+ ly 14)))
      (.append sb (fmt "  <line x1=\"544\" y1=\"%d\" x2=\"544\" y2=\"%d\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n" (+ ly 6) (+ ly 20)))
      (.append sb (fmt "  <line x1=\"540\" y1=\"%d\" x2=\"548\" y2=\"%d\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n" (+ ly 6) (+ ly 6)))
      (.append sb (fmt "  <line x1=\"540\" y1=\"%d\" x2=\"548\" y2=\"%d\" stroke=\"#000000\" stroke-width=\"1.0\"/>\n" (+ ly 20) (+ ly 20)))
      (.append sb (fmt "  <text x=\"554\" y=\"%d\" font-size=\"8.5\" fill=\"#000000\" dominant-baseline=\"middle\">Error bars: ± 1 SD (n=30, Panel b)</text>\n" (+ ly 14))))
    (.append sb "</svg>\n")
    (io/make-parents out-path)
    (spit out-path (.toString sb) :encoding "UTF-8")
    (println "Wrote Springer SVG:" out-path)))
