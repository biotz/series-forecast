(ns series-forecast.series-forecast-test
  (:require [clojure.test :refer [deftest is testing]]
            [series-forecast.series-forecast :refer [greet]]))

(deftest greet-test
  (testing "prints a greeting"
    (is (= "Hello, Clojure!\n"
           (with-out-str (greet {:name "Clojure"}))))))
