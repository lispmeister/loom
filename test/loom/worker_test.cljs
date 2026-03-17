(ns loom.worker-test
  (:require [cljs.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; tool-stats accumulation tests
;;
;; These tests verify the swap! logic for tool-stats in isolation, without
;; spinning up the full worker. The logic is extracted here as pure functions
;; so it can be tested directly.
;; ---------------------------------------------------------------------------

(defn- inc-calls
  "Increment the :calls counter for tool-name in stats-map."
  [stats-map tool-name]
  (update stats-map tool-name
          (fn [s] (update (or s {:calls 0 :errors 0}) :calls inc))))

(defn- inc-errors
  "Increment the :errors counter for tool-name in stats-map."
  [stats-map tool-name]
  (update stats-map tool-name
          (fn [s] (update (or s {:calls 0 :errors 0}) :errors inc))))

(deftest test-tool-stats-first-call
  (testing "first call to a tool creates entry with calls=1 errors=0"
    (let [stats (-> {}
                    (inc-calls "read_file"))]
      (is (= {:calls 1 :errors 0} (get stats "read_file"))))))

(deftest test-tool-stats-multiple-calls
  (testing "repeated calls accumulate correctly"
    (let [stats (-> {}
                    (inc-calls "read_file")
                    (inc-calls "read_file")
                    (inc-calls "write_file")
                    (inc-calls "read_file"))]
      (is (= {:calls 3 :errors 0} (get stats "read_file")))
      (is (= {:calls 1 :errors 0} (get stats "write_file"))))))

(deftest test-tool-stats-error-increments
  (testing "error on a known tool increments errors without touching calls"
    (let [stats (-> {}
                    (inc-calls "read_file")
                    (inc-errors "read_file"))]
      (is (= {:calls 1 :errors 1} (get stats "read_file"))))))

(deftest test-tool-stats-error-on-unknown-tool
  (testing "error on a tool never previously called still creates a valid entry"
    (let [stats (-> {}
                    (inc-errors "bash"))]
      (is (= {:calls 0 :errors 1} (get stats "bash"))))))

(deftest test-tool-stats-atom-accumulation
  (testing "swap! on an atom accumulates as expected"
    (let [stats (atom {})]
      (swap! stats inc-calls "read_file")
      (swap! stats inc-calls "read_file")
      (swap! stats inc-calls "write_file")
      (swap! stats inc-errors "read_file")
      (is (= {:calls 2 :errors 1} (get @stats "read_file")))
      (is (= {:calls 1 :errors 0} (get @stats "write_file"))))))
