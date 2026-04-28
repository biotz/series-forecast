(ns series-forecast.series-forecast
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc])
  (:import (java.sql DriverManager)))

(def dataset-url
  "https://storage.googleapis.com/kaggle-competitions-data/kaggle-v2/29781/2887556/bundle/archive.zip?GoogleAccessId=web-data@kaggle-161607.iam.gserviceaccount.com&Expires=1776428547&Signature=iEbo6pSXgzPc%2F7qGZMPo2mATd%2F0MqwAlVl2ik8br5o3VW7THDjmnIILYluCobD3H1YuiI0eQPaDG%2FSbi%2Bi4ZI7tQf01is8zhuSK%2BTeQ2w%2FKhCQFJ0QwLJWfilQfYsMTzKKmbutxRMKD86RGLICKMaLun%2FMfplrsTJ2oTSi17ZuXJ4FEBLHrYXMc8y0hS2oMSNOPxWUEKioYOjL4T87WbMoib5hggtaY4jwekfw0BizJedSfCykkRskfaI%2Bgr%2BjwDUnb%2BAsG7K5DSh0gwaRJb80w%2B%2FRh45nNm4KboMw8edLyQQ%2Fy8mkom92ri9kP5vEaslEkIPMblX75Q%2FV1LUOByAA%3D%3D&response-content-disposition=attachment%3B+filename%3Dstore-sales-time-series-forecasting.zip")

(def dataset-filename "store-sales-time-series-forecasting.zip")
(def duckdb-url "jdbc:duckdb:store_sales.duckdb")
(def memory-duckdb-url "jdbc:duckdb::memory:")

(def datasets ["oil.csv"
               "stores.csv"
               "test.csv"
               "train.csv"
               "transactions.csv"
               "sample_submission.csv"])

(def joined-datasets ["oil.csv"
                      "stores.csv"
                      "holidays_events.csv"])

(def stores-query
  {:select :*
   :from [[[:raw "stores.csv"]]]
   :order-by [:store_nbr]})

(defn greet
  "Print a greeting for the supplied name."
  [{:keys [name] :or {name "World"}}]
  (println (str "Hello, " name "!")))

(defn make-random-embeddings [rows dimension]
  (vec
   (repeatedly rows
               #(double-array (repeatedly dimension (fn [] (Math/random)))))))

(defn make-query [filename]
  {:select :*
   :from [[[:raw filename]]]})

(defn download-dataset!
  ([] (download-dataset! dataset-url dataset-filename))
  ([url filename]
   (with-open [in (io/input-stream url)
               out (io/output-stream filename)]
     (io/copy in out))
   filename))

(defn connect!
  ([] (connect! duckdb-url))
  ([jdbc-url]
   (DriverManager/getConnection jdbc-url)))

(defn query-stores!
  ([] (with-open [conn (connect! memory-duckdb-url)]
        (query-stores! conn)))
  ([conn]
   (jdbc/execute! conn (sql/format stores-query))))

(def conn (delay (connect!)))

(defn- sql-string [value]
  (str/replace (str value) "'" "''"))

(defn create-concatenated-records [files table-name]
  {:create-table [(keyword table-name)]
   :select :*
   :from [[[:raw (format "read_csv([%s], union_by_name = true)"
                         (str/join ", " (map #(format "'%s'" (sql-string %)) files)))]]]})

(defn fill-nil [filename]
  {:select [:date
            [[:raw "LAST_VALUE(dcoilwtico IGNORE NULLS) OVER (ORDER BY date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"]
             :dcoilwtico_ffill]]
   :from   [[[:raw filename]]]
   :order-by [:date]})

(def train-data
  (delay (jdbc/execute! @conn (sql/format (make-query "train.csv")))))

(defn lag-features [num]
  [[[:over
     [:lag :sales num]
     {:partition-by [:store_nbr :family]
      :order-by [:date]}]
    (keyword (str "lag_" num))]])

(defn rolling-features [start end]
  [[[:over
     [:avg :sales]
     {:partition-by [:store_nbr :family]
      :order-by [:date]
      :rows [:between
             [:preceding start]
             [:preceding end]]}]
    :rolling_mean_7]])

(defn vocab-table-name [column]
  (keyword (str (name column) "_vocab")))

(defn vocab-idx-name [column]
  (keyword (str (name column) "_idx")))

(defn csv-table [path]
  [:raw (format "read_csv_auto('%s')" (sql-string path))])

(defn create-vocabulary-map [csv-path column]
  (let [col (keyword column)]
    {:create-table-as (vocab-table-name col)
     :with [[:vocab_src
             {:select-distinct [col]
              :from [[(csv-table csv-path)]]}]
            [:numbered
             {:select [col
                       [[:raw (format "ROW_NUMBER() OVER (ORDER BY %s ASC)"
                                       (name col))]
                        :rn]]
              :from [:vocab_src]}]]
     :select [col
              [[:- :rn 1] (vocab-idx-name col)]]
     :from [:numbered]}))

(defn drop-vocabulary-map [column]
  {:drop-table [:if-exists (vocab-table-name (keyword column))]})

(defn vocabulary-select-map [column]
  (let [col (keyword column)]
    {:select [:*]
     :from [(vocab-table-name col)]
     :order-by [(vocab-idx-name col)]}))


(defn recreate-vocabulary! [conn csv-path column]
  (jdbc/execute! conn (sql/format (drop-vocabulary-map column)))
  (jdbc/execute! conn (sql/format (create-vocabulary-map csv-path column)))
  (jdbc/execute! conn (sql/format (vocabulary-select-map column))))

(defn initialize-vocabulary!
  ([conn] (initialize-vocabulary! conn "train.csv" "family" 8))
  ([conn csv-path column dimension]
   (let [vocabulary (recreate-vocabulary! conn csv-path column)
        count-items (count vocabulary)
        embedding-matrix (make-random-embeddings count-items dimension)]
     (mapv assoc vocabulary (repeat :embedding) embedding-matrix))))

(def initialize-vocabualry initialize-vocabulary!)

(defn -main
  [& [name]]
  (greet {:name (or name "World")}))
