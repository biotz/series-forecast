(ns series-forecast.series-forecast
  (:gen-class)
  (:require [clojure.java.io :as io]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [clojure.string :as str])
  (:import (java.sql DriverManager)))

;; Store sales dataset:
;; https://www.kaggle.com/competitions/store-sales-time-series-forecasting/data
(def dataset-url
  "https://storage.googleapis.com/kaggle-competitions-data/kaggle-v2/29781/2887556/bundle/archive.zip?GoogleAccessId=web-data@kaggle-161607.iam.gserviceaccount.com&Expires=1776428547&Signature=iEbo6pSXgzPc%2F7qGZMPo2mATd%2F0MqwAlVl2ik8br5o3VW7THDjmnIILYluCobD3H1YuiI0eQPaDG%2FSbi%2Bi4ZI7tQf01is8zhuSK%2BTeQ2w%2FKhCQFJ0QwLJWfilQfYsMTzKKmbutxRMKD86RGLICKMaLun%2FMfplrsTJ2oTSi17ZuXJ4FEBLHrYXMc8y0hS2oMSNOPxWUEKioYOjL4T87WbMoib5hggtaY4jwekfw0BizJedSfCykkRskfaI%2Bgr%2BjwDUnb%2BAsG7K5DSh0gwaRJb80w%2B%2FRh45nNm4KboMw8edLyQQ%2Fy8mkom92ri9kP5vEaslEkIPMblX75Q%2FV1LUOByAA%3D%3D&response-content-disposition=attachment%3B+filename%3Dstore-sales-time-series-forecasting.zip")

(def dataset-filename "store-sales-time-series-forecasting.zip")
(def duckdb-url "jdbc:duckdb:store_sales.duckdb")
(def memory-duckdb-url "jdbc:duckdb::memory:")

(def stores-query {:select :* :from [[[:raw "stores.csv"]]]})

(defn make-query [filename] {:select :* :from [[[:raw filename]]]})

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
  ([] (query-stores! (connect! memory-duckdb-url)))
  ([conn]
   (jdbc/execute! conn (sql/format stores-query))))

(def datasets ["oil.csv"
               "stores.csv"
               "test.csv"
               "train.csv"
               "transactions.csv"
               "sample_submission.csv"])

(def joined-datasets ["oil.csv"
                      "stores.csv"
                      "holidays_events.csv"])

(def conn (delay (connect!)))

(defn create-contantenated-records
  [files row-name]
  {:create-table [row-name]
   :select :*
   :from [[[:raw (format  "read_csv([%s] , union_by_name = true)" (str/join ", " (map #(format "'%s'" %) files)))]]]})

; (sql/format (create-contantenated-records joined-datasets "new"))

(defn fill-nil [filename]
  {:select [:date
            [[:raw "LAST_VALUE(dcoilwtico IGNORE NULLS) OVER (ORDER BY date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"]
             :dcoilwtico_ffill]]
   :from   [[[:raw filename]]]
   :order-by [:date]})

(def  train-data (delay (jdbc/execute! @conn (sql/format (make-query (nth datasets 3))))))

(defn lag-features [num] (format "LAG(sales, %s) OVER ( PARTITION BY store_nbr, family ORDER BY date) AS lag_%s" num num))

(defn rolling-features [start end] (format "AVG(sales) OVER (PARTITION BY store_nbr, family ORDER BY date ROWS BETWEEN %s PRECEDING AND %s PRECEDING) AS rolling_mean_7" start end))

(defn create-vocabulary [table column]
  (format
   "CREATE OR REPLACE TABLE %s_vocab AS
    SELECT %s,
           ROW_NUMBER() OVER (ORDER BY %s) - 1 AS %s_idx
    FROM (
      SELECT DISTINCT %s
      FROM %s
    ) t;"
   column
   column
   column
   column
   column
   table))

(defn -main
  "I don't do a whole lot ... yet."
  [& _]
  (println "greetings"))


