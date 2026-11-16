(ns fetch-nasdaq50
  "Reconstruct data/nasdaq50_2012_2026.edn from Yahoo Finance daily closing prices
  for the 50 NASDAQ equities spanning 2012-01-03 through 2026-02-27.

  Usage:
    ./clojure -M scripts/fetch-nasdaq50.clj
    .\\clojure.cmd -M scripts/fetch-nasdaq50.clj [--out data/nasdaq50_2012_2026.edn]"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringReader]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Instant LocalDate ZoneId]
           [java.time.format DateTimeFormatter]))

(def tickers
  ["AAPL" "MSFT" "AMZN" "NVDA" "GOOGL" "ADBE" "AMD" "AMGN" "ADI" "ADP"
   "AMAT" "ASML" "ADSK" "BIIB" "BKNG" "CDNS" "CMCSA" "COST" "CSCO" "CSX"
   "CTAS" "CTSH" "DLTR" "EBAY" "EXC" "FAST" "FITB" "GILD" "HBAN" "HON"
   "IDXX" "ILMN" "INTC" "INTU" "ISRG" "JBHT" "KLAC" "LRCX" "MAR" "MCHP"
   "MDLZ" "MNST" "MU" "NTAP" "NFLX" "ORLY" "PAYX" "PCAR" "PEP" "QCOM"])

(def ^:private period-start "2012-01-03")
(def ^:private period-end   "2026-02-27")
;; 2012-01-03 00:00Z .. 2026-02-28 06:00Z ensures inclusive capture of 2026-02-27 close
(def ^:private epoch-start  1325548800)
(def ^:private epoch-end    1772258400)
(def ^:private ny-zone      (ZoneId/of "America/New_York"))
(def ^:private date-fmt     DateTimeFormatter/ISO_LOCAL_DATE)

;; Minimal dependency-free JSON reader sufficient for Yahoo Finance v8 responses
(defn- skip-ws [^PushbackReader r]
  (loop [ch (.read r)]
    (when (not= ch -1)
      (let [c (char ch)]
        (if (or (= c \space) (= c \tab) (= c \newline) (= c \return))
          (recur (.read r))
          (.unread r ch))))))

(declare read-json-val)

(defn- read-json-string [^PushbackReader r]
  (let [sb (StringBuilder.)]
    (loop [ch (.read r)]
      (when (= ch -1) (throw (ex-info "Unterminated JSON string" {})))
      (let [c (char ch)]
        (cond
          (= c \") (.toString sb)
          (= c \\)
          (let [esc (char (.read r))]
            (case esc
              \" (.append sb \")
              \\ (.append sb \\)
              \/ (.append sb \/)
              \b (.append sb \backspace)
              \f (.append sb \formfeed)
              \n (.append sb \newline)
              \r (.append sb \return)
              \t (.append sb \tab)
              \u (let [hex (apply str (repeatedly 4 #(char (.read r))))]
                   (.append sb (char (Integer/parseInt hex 16))))
              (.append sb esc))
            (recur (.read r)))
          :else (do (.append sb c) (recur (.read r))))))))

(defn- read-json-number [^PushbackReader r first-char]
  (let [sb (StringBuilder.)]
    (.append sb first-char)
    (loop [ch (.read r)]
      (when (not= ch -1)
        (let [c (char ch)]
          (if (re-matches #"[0-9eE.+\-]" (str c))
            (do (.append sb c) (recur (.read r)))
            (.unread r ch)))))
    (let [s (.toString sb)]
      (if (or (str/includes? s ".") (str/includes? s "e") (str/includes? s "E"))
        (Double/parseDouble s)
        (Long/parseLong s)))))

(defn- read-json-array [^PushbackReader r]
  (skip-ws r)
  (let [ch (.read r)]
    (if (= (char ch) \])
      []
      (do (.unread r ch)
          (loop [acc (transient [])]
            (let [v (read-json-val r)
                  acc (conj! acc v)]
              (skip-ws r)
              (let [sep (char (.read r))]
                (case sep
                  \, (recur acc)
                  \] (persistent! acc)
                  (throw (ex-info "Expected ',' or ']'" {:char sep}))))))))))

(defn- read-json-object [^PushbackReader r]
  (skip-ws r)
  (let [ch (.read r)]
    (if (= (char ch) \})
      {}
      (do (.unread r ch)
          (loop [acc (transient {})]
            (skip-ws r)
            (when (not= (char (.read r)) \")
              (throw (ex-info "Expected JSON string key" {})))
            (let [k (read-json-string r)]
              (skip-ws r)
              (when (not= (char (.read r)) \:)
                (throw (ex-info "Expected ':' after JSON key" {:key k})))
              (let [v (read-json-val r)
                    acc (assoc! acc k v)]
                (skip-ws r)
                (let [sep (char (.read r))]
                  (case sep
                    \, (recur acc)
                    \} (persistent! acc)
                    (throw (ex-info "Expected ',' or '}'" {:char sep})))))))))))

(defn- read-json-val [^PushbackReader r]
  (skip-ws r)
  (let [ch (.read r)]
    (when (= ch -1) (throw (ex-info "Unexpected EOF" {})))
    (let [c (char ch)]
      (case c
        \{ (read-json-object r)
        \[ (read-json-array r)
        \" (read-json-string r)
        \t (do (dotimes [_ 3] (.read r)) true)
        \f (do (dotimes [_ 4] (.read r)) false)
        \n (do (dotimes [_ 3] (.read r)) nil)
        (read-json-number r c)))))

(defn- parse-json [^String s]
  (read-json-val (PushbackReader. (StringReader. s))))

(defn- fetch-ticker-closes [^HttpClient client ^String ticker]
  (let [url (format "https://query1.finance.yahoo.com/v8/finance/chart/%s?period1=%d&period2=%d&interval=1d&events=div%%2Csplit"
                    ticker epoch-start epoch-end)
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.header "User-Agent" "Mozilla/5.0")
                (.GET)
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    (when (not= 200 (.statusCode resp))
      (throw (ex-info "Yahoo Finance HTTP error" {:ticker ticker :status (.statusCode resp)})))
    (let [parsed (parse-json (.body resp))
          result (first (get-in parsed ["chart" "result"]))
          timestamps (get result "timestamp")
          closes (get-in result ["indicators" "quote" 0 "close"])]
      (when (or (empty? timestamps) (not= (count timestamps) (count closes)))
        (throw (ex-info "Malformed Yahoo chart series" {:ticker ticker})))
      (into (sorted-map)
            (keep (fn [[ts px]]
                    (when (and ts (number? px) (pos? (double px)) (Double/isFinite (double px)))
                      (let [d (.format date-fmt (.toLocalDate (.atZone (Instant/ofEpochSecond (long ts)) ny-zone)))]
                        (when (and (<= (compare period-start d) 0)
                                   (<= (compare d period-end) 0))
                          [d (double px)])))))
            (map vector timestamps closes)))))

(defn- build-dataset [client]
  (let [series (mapv (fn [t]
                       (printf "Fetching %-6s ... " t)
                       (flush)
                       (let [m (fetch-ticker-closes client t)]
                         (println (count m) "days")
                         {:ticker t :prices m}))
                     tickers)
        common-dates (->> series
                          (map #(set (keys (:prices %))))
                          (reduce set/intersection)
                          sort
                          vec)
        return-dates (subvec common-dates 1)
        assets (mapv (fn [{:keys [ticker prices]}]
                       (let [pxs (mapv #(get prices %) common-dates)
                             rets (mapv (fn [p0 p1] (/ (- (double p1) (double p0)) (double p0)))
                                        pxs (rest pxs))]
                         {:id (keyword ticker)
                          :ticker ticker
                          :returns rets}))
                     series)]
    {:dataset-id :nasdaq-50-daily-2012-2026
     :source "Yahoo Finance (NASDAQ-listed equities, daily closing prices)"
     :period-start (first common-dates)
     :period-end (peek common-dates)
     :price-dates-count (count common-dates)
     :return-dates return-dates
     :assets assets}))

(defn -main [& args]
  (let [arg-map (apply hash-map args)
        out-path (get arg-map "--out" "data/nasdaq50_2012_2026.edn")
        client (HttpClient/newHttpClient)]
    (println (format "Downloading %d NASDAQ-50 equities (%s to %s)..."
                     (count tickers) period-start period-end))
    (let [ds (build-dataset client)
          out-file (io/file out-path)]
      (io/make-parents out-file)
      (spit out-file (pr-str ds))
      (println (format "Wrote %s (%d price dates, %d return dates, %d assets)."
                       (.getPath out-file)
                       (:price-dates-count ds)
                       (count (:return-dates ds))
                       (count (:assets ds)))))))

(apply -main *command-line-args*)
