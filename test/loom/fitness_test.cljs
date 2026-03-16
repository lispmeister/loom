(ns loom.fitness-test
  (:require [cljs.test :refer [deftest is testing]]
            [loom.supervisor.fitness :as fitness]))

(def ^:private good-tests
  {:tests-run 94 :assertions 226 :failures 0 :errors 0 :passed? true})

(def ^:private failed-tests
  {:tests-run 94 :assertions 226 :failures 2 :errors 0 :passed? false})

(def ^:private regressed-tests
  {:tests-run 90 :assertions 200 :failures 0 :errors 0 :passed? true})

(deftest safety-check-passing
  (testing "safe when tests pass and count doesn't regress"
    (is (:safe? (fitness/safety-check good-tests good-tests)))
    (is (:safe? (fitness/safety-check good-tests nil)))))

(deftest safety-check-failing
  (testing "not safe when tests fail"
    (let [result (fitness/safety-check failed-tests good-tests)]
      (is (false? (:safe? result)))
      (is (re-find #"failures" (:reason result))))))

(deftest safety-check-regression
  (testing "not safe when test count drops"
    (let [result (fitness/safety-check regressed-tests good-tests)]
      (is (false? (:safe? result)))
      (is (re-find #"regressed" (:reason result))))))

(deftest safety-check-nil
  (testing "not safe when no test results"
    (is (false? (:safe? (fitness/safety-check nil nil))))))

(deftest fitness-score-calculation
  (testing "score = tests*10 + assertions - tokens/1000"
    (let [report {:test-results good-tests
                  :token-usage {:input 4000 :output 1000}}
          score (fitness/fitness-score report)]
      ;; 94*10 + 226 - 5000/1000 = 940 + 226 - 5 = 1161
      (is (= 1161 score)))))

(deftest improved-basic
  (testing "improved when score increases and safe"
    (let [current  {:test-results good-tests
                    :token-usage {:input 3000 :output 500}}
          previous {:test-results {:tests-run 90 :assertions 200 :failures 0 :errors 0 :passed? true}
                    :token-usage {:input 4000 :output 1000}}
          result (fitness/improved? current previous)]
      (is (:improved? result))
      (is (:safe? result))
      (is (> (:current-score result) (:previous-score result))))))

(deftest improved-not-when-unsafe
  (testing "not improved when tests fail even if score would be higher"
    (let [current  {:test-results failed-tests
                    :token-usage {:input 100 :output 50}}
          previous {:test-results good-tests
                    :token-usage {:input 10000 :output 5000}}
          result (fitness/improved? current previous)]
      (is (false? (:improved? result)))
      (is (false? (:safe? result))))))
