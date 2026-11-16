(ns niki.optimizer.portfolio-data
  "Point-in-time window auditing, cross-split footprint verification, and
  deterministic synthetic multi-task portfolio suite generation for G5/G7."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [niki.optimizer.portfolio :as portfolio])
  (:import [java.time LocalDate]
           [java.util Random]))

(defn- ensure! [condition code message data]
  (when-not condition
    (throw (ex-info message (assoc data :error code)))))

(defn- parse-date ^LocalDate [s field]
  (ensure! (and (string? s) (boolean (re-matches #"\d{4}-\d{2}-\d{2}" s)))
           :invalid-date (str "Invalid ISO-8601 date for " field) {:field field :value s})
  (try
    (LocalDate/parse s)
    (catch Exception e
      (throw (ex-info (str "Unparseable date for " field) {:error :invalid-date :field field :value s} e)))))

(defn audit-task-window
  "Verify point-in-time non-leakage for a task descriptor:
   - start-date < end-date <= decision-date
   - if :panel is present, validates panel via portfolio/prepare-instance and checks
     all panel dates are strictly increasing within [start-date, end-date]."
  [task]
  (ensure! (map? task) :invalid-task "Task descriptor must be a map" {})
  (let [task-id (:task-id task)
        split (:split task)
        start-d (parse-date (:start-date task) :start-date)
        end-d (parse-date (:end-date task) :end-date)
        decision-d (parse-date (:decision-date task) :decision-date)]
    (ensure! (or (keyword? task-id) (and (string? task-id) (not (str/blank? task-id))))
             :invalid-task-id "Task requires a non-blank :task-id" {})
    (ensure! (contains? #{:train :validation :test} split)
             :invalid-split "Task :split must be :train, :validation, or :test" {:split split})
    (ensure! (.isBefore start-d end-d)
             :invalid-window "Task :start-date must precede :end-date"
             {:task-id task-id :start-date (str start-d) :end-date (str end-d)})
    (ensure! (not (.isAfter end-d decision-d))
             :future-information-leakage "Task :end-date exceeds :decision-date (future leakage)"
             {:task-id task-id :end-date (str end-d) :decision-date (str decision-d)})
    (when-let [panel (:panel task)]
      (let [prepared (portfolio/prepare-instance panel)
            dates (mapv #(parse-date % :panel-date) (:dates prepared))]
        (when-let [expected (:expected-returns-count task)]
          (ensure! (= expected (count dates))
                   :return-count-mismatch "Panel return count does not match :expected-returns-count"
                   {:task-id task-id :expected expected :actual (count dates)}))
        (doseq [[d1 d2] (partition 2 1 dates)]
          (ensure! (.isBefore ^LocalDate d1 ^LocalDate d2)
                   :non-monotonic-dates "Panel dates must be strictly increasing"
                   {:task-id task-id :d1 (str d1) :d2 (str d2)}))
        (ensure! (not (.isBefore ^LocalDate (first dates) start-d))
                 :window-underflow "First panel date precedes :start-date"
                 {:task-id task-id :first-date (str (first dates)) :start-date (str start-d)})
        (ensure! (not (.isAfter ^LocalDate (peek dates) end-d))
                 :window-overflow "Last panel date exceeds :end-date"
                 {:task-id task-id :last-date (str (peek dates)) :end-date (str end-d)})
        prepared))
    {:task-id task-id
     :split split
     :start-date (str start-d)
     :end-date (str end-d)
     :decision-date (str decision-d)
     :point-in-time-valid? true}))

(defn audit-split-footprints
  "Audit a {:train [...] :validation [...] :test [...]} task manifest for:
   1. Per-task point-in-time validity (no future leakage).
   2. Strict cross-split footprint separation (latest train < earliest validation,
      and latest validation < earliest test)."
  [manifest]
  (ensure! (map? manifest) :invalid-manifest "Manifest must be a map" {})
  (doseq [k [:train :validation :test]]
    (ensure! (and (vector? (get manifest k)) (seq (get manifest k)))
             :empty-split (str "Manifest split " k " must be a non-empty vector") {:split k}))
  (let [train-audits (mapv #(audit-task-window (assoc % :split :train)) (:train manifest))
        val-audits (mapv #(audit-task-window (assoc % :split :validation)) (:validation manifest))
        test-audits (mapv #(audit-task-window (assoc % :split :test)) (:test manifest))
        all-ids (map :task-id (concat train-audits val-audits test-audits))
        _ (ensure! (= (count all-ids) (count (distinct all-ids)))
                   :duplicate-task-id "Duplicate :task-id across splits" {})
        max-date (fn [xs field] (reduce (fn [^LocalDate a ^LocalDate b] (if (.isAfter b a) b a))
                                        (map #(parse-date (get % field) field) xs)))
        min-date (fn [xs field] (reduce (fn [^LocalDate a ^LocalDate b] (if (.isBefore b a) b a))
                                        (map #(parse-date (get % field) field) xs)))
        train-latest (max-date train-audits :end-date)
        val-earliest (min-date val-audits :start-date)
        val-latest (max-date val-audits :end-date)
        test-earliest (min-date test-audits :start-date)]
    (ensure! (.isBefore train-latest val-earliest)
             :cross-split-leakage "Training footprint overlaps validation footprint"
             {:train-latest (str train-latest) :validation-earliest (str val-earliest)})
    (ensure! (.isBefore val-latest test-earliest)
             :cross-split-leakage "Validation footprint overlaps test footprint"
             {:validation-latest (str val-latest) :test-earliest (str test-earliest)})
    {:point-in-time-valid? true
     :cross-split-disjoint? true
     :counts {:train (count train-audits)
              :validation (count val-audits)
              :test (count test-audits)}
     :footprints {:train [(str (min-date train-audits :start-date)) (str train-latest)]
                  :validation [(str val-earliest) (str val-latest)]
                  :test [(str test-earliest) (str (max-date test-audits :end-date))]}}))

(defn generate-synthetic-panel
  "Generate a deterministic synthetic portfolio panel with exact binary64-safe returns
  (rounded to 1e-6 increments) and nontrivial factor trade-offs."
  [{:keys [fixture-id n k t start-date seed]
    :or {fixture-id "synthetic-portfolio" n 15 k 5 t 30 start-date "2019-01-01" seed 0}}]
  (let [rng (Random. (long seed))
        base-date (parse-date start-date :start-date)
        dates (mapv #(str (.plusDays base-date (long %))) (range t))
        market-shocks (mapv (fn [_] (- (* 0.04 (.nextDouble rng)) 0.02)) (range t))
        sector-shocks (mapv (fn [_] (- (* 0.03 (.nextDouble rng)) 0.015)) (range t))
        assets (mapv (fn [i]
                       (let [drift (+ 0.0002 (* 0.003 (/ (double i) (double (max 1 (dec n))))))
                             beta (+ 0.3 (* 1.4 (.nextDouble rng)))
                             sector-sign (if (even? i) 1.0 -1.0)
                             idio-scale (+ 0.005 (* 0.025 (/ (double i) (double (max 1 (dec n))))))
                             returns (mapv (fn [day]
                                             (let [m (nth market-shocks day)
                                                   s (nth sector-shocks day)
                                                   eps (- (* 2.0 idio-scale (.nextDouble rng)) idio-scale)
                                                   raw (+ drift (* beta m) (* 0.5 sector-sign s) eps)
                                                   clamped (max -0.25 (min 0.25 raw))]
                                               ;; Round to integer / 10000.0 so double is exact and clean
                                               (/ (double (Math/round (* clamped 10000.0))) 10000.0)))
                                           (range t))]
                         {:id (str "SYN-" i)
                          :dates dates
                          :returns returns}))
                     (range n))]
    {:fixture-id fixture-id
     :source :synthetic-development-only
     :k (long k)
     :dates dates
     :assets assets}))

(defn generate-synthetic-suite
  "Generate a deterministic multi-task synthetic portfolio benchmark suite with
  strictly disjoint train (6), validation (6), and test (24) calendar footprints."
  [{:keys [n k t base-seed]
    :or {n 15 k 5 t 20 base-seed 20261108}}]
  (let [make-split (fn [split count-tasks base-year seed-offset]
                     (let [start-root (LocalDate/of (int base-year) 1 1)]
                       (mapv (fn [idx]
                               (let [s-date (.plusDays start-root (* idx 30))
                                     e-date (.plusDays s-date (dec t))
                                     id (str "port-" (name split) "-" idx)
                                     panel (generate-synthetic-panel
                                            {:fixture-id id
                                             :n n :k k :t t
                                             :start-date (str s-date)
                                             :seed (+ base-seed seed-offset idx)})]
                                 {:task-id id
                                  :split split
                                  :start-date (str s-date)
                                  :end-date (str e-date)
                                  :decision-date (str e-date)
                                  :expected-returns-count t
                                  :panel panel}))
                             (range count-tasks))))
        suite {:train (make-split :train 6 2018 100)
               :validation (make-split :validation 6 2021 200)
               :test (make-split :test 24 2023 1000)}]
    (audit-split-footprints suite)
    suite))

(defn load-nasdaq-suite
  "Load the frozen 50-asset NASDAQ historical daily return dataset (2012-01-03 to 2026-02-27)
  from data/nasdaq50_2012_2026.edn and partition it into 36 strictly disjoint chronological
  windows: 6 training (2012-2014), 6 validation (2014-2016), and 24 test (2016-2025)."
  ([] (load-nasdaq-suite {}))
  ([{:keys [path k window-days gap-days]
     :or {path "data/nasdaq50_2012_2026.edn" k 5 window-days 90 gap-days 8}}]
   (let [f (io/file path)]
     (ensure! (.exists f) :missing-dataset-file "Frozen NASDAQ dataset file not found" {:path path})
     (let [raw (edn/read-string (slurp f))
           all-dates (:return-dates raw)
           raw-assets (:assets raw)
           stride (+ (long window-days) (long gap-days))
           make-task (fn [split local-idx global-idx]
                       (let [start-idx (* global-idx stride)
                             end-idx (+ start-idx (long window-days))
                             w-dates (subvec all-dates start-idx end-idx)
                             s-date (first w-dates)
                             e-date (peek w-dates)
                             id (str "nasdaq-" (name split) "-" local-idx)
                             panel {:fixture-id id
                                    :source :nasdaq-historical-2012-2026
                                    :k (long k)
                                    :dates w-dates
                                    :assets (mapv (fn [a]
                                                    {:id (name (:id a))
                                                     :dates w-dates
                                                     :returns (subvec (:returns a) start-idx end-idx)})
                                                  raw-assets)}]
                         {:task-id id
                          :split split
                          :start-date s-date
                          :end-date e-date
                          :decision-date e-date
                          :expected-returns-count (long window-days)
                          :panel panel}))
           suite {:train (mapv #(make-task :train % %) (range 6))
                  :validation (mapv #(make-task :validation % (+ 6 %)) (range 6))
                  :test (mapv #(make-task :test % (+ 12 %)) (range 24))}]
       (audit-split-footprints suite)
       suite))))
