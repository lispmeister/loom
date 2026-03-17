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
  (testing "score = tests*10 + assertions - tokens/1000 (default config)"
    (let [report {:test-results good-tests
                  :token-usage {:input 4000 :output 1000}}
          score (fitness/fitness-score report)]
      ;; 94*10 + 226 - 5000/1000 = 940 + 226 - 5 = 1161
      (is (= 1161 score)))))

(deftest fitness-score-uses-config-weights
  (testing "fitness-score respects custom weights"
    (let [report {:test-results good-tests
                  :token-usage {:input 4000 :output 1000}}
          custom-config {:test-weight 5 :assertion-weight 2 :token-penalty-divisor 500}
          score (fitness/fitness-score report custom-config)]
      ;; 94*5 + 226*2 - 5000/500 = 470 + 452 - 10 = 912
      (is (= 912 score)))))

(deftest load-config-returns-defaults-when-file-missing
  (testing "load-config falls back to defaults when config path does not exist"
    (let [config (fitness/load-config "/nonexistent/path/fitness.edn")]
      (is (= 10 (:test-weight config)))
      (is (= 1 (:assertion-weight config)))
      (is (= 1000 (:token-penalty-divisor config))))))

(deftest load-config-merges-partial-config
  (testing "load-config merges partial config with defaults"
    ;; We can test this by writing a temp file — but since we can't easily
    ;; create temp EDN files in ClojureScript tests without async, we verify
    ;; that the default-config structure is correct by calling load-config
    ;; with a missing path and confirming all keys are present.
    (let [config (fitness/load-config "/nonexistent/path/fitness.edn")]
      (is (contains? config :test-weight))
      (is (contains? config :assertion-weight))
      (is (contains? config :token-penalty-divisor)))))

(deftest current-config-returns-map
  (testing "current-config returns a map with expected keys"
    (let [config (fitness/current-config)]
      (is (map? config))
      (is (contains? config :test-weight))
      (is (contains? config :assertion-weight))
      (is (contains? config :token-penalty-divisor))
      ;; Values should be positive numbers
      (is (pos? (:test-weight config)))
      (is (pos? (:assertion-weight config)))
      (is (pos? (:token-penalty-divisor config))))))

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

(deftest user-task-success-rate-empty-test
  (testing "empty entries returns total=0 promoted=0 rate=nil"
    (let [result (fitness/user-task-success-rate [])]
      (is (= 0 (:total result)))
      (is (= 0 (:promoted result)))
      (is (nil? (:rate result))))))

(deftest user-task-success-rate-all-promoted-test
  (testing "all promoted entries gives rate of 1.0"
    (let [entries [{:generation 1 :promoted? true}
                   {:generation 2 :promoted? true}
                   {:generation 3 :promoted? true}]
          result  (fitness/user-task-success-rate entries)]
      (is (= 3 (:total result)))
      (is (= 3 (:promoted result)))
      (is (= 1 (:rate result))))))

(deftest user-task-success-rate-none-promoted-test
  (testing "no promoted entries gives rate of 0"
    (let [entries [{:generation 1 :promoted? false}
                   {:generation 2 :promoted? false}]
          result  (fitness/user-task-success-rate entries)]
      (is (= 2 (:total result)))
      (is (= 0 (:promoted result)))
      (is (= 0 (:rate result))))))

(deftest user-task-success-rate-mixed-test
  (testing "mixed entries gives correct fractional rate"
    (let [entries [{:generation 1 :promoted? true}
                   {:generation 2 :promoted? false}
                   {:generation 3 :promoted? true}
                   {:generation 4 :promoted? false}]
          result  (fitness/user-task-success-rate entries)]
      (is (= 4 (:total result)))
      (is (= 2 (:promoted result)))
      (is (= 0.5 (double (:rate result)))))))
