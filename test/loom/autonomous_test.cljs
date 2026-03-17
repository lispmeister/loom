(ns loom.autonomous-test
  "Tests for the autonomous loop driver: stopping conditions and fitness log."
  (:require [cljs.test :refer [deftest is testing]]
            [loom.agent.autonomous :as auto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-tmpdir []
  (.mkdtempSync fs (.join path (.tmpdir os) "loom-auto-test-")))

(defn- cleanup [dir]
  (.rmSync fs dir #js {:recursive true :force true}))

;; ---------------------------------------------------------------------------
;; Stopping condition tests
;; ---------------------------------------------------------------------------

(deftest stop-on-generation-cap-test
  (testing "stops when generation cap is reached"
    (binding [auto/max-generations 3]
      (let [reason (#'auto/check-stop-conditions
                    {:generations-run 3 :total-tokens 0 :recent-scores []})]
        (is (some? reason))
        (is (re-find #"Generation cap" reason))))))

(deftest no-stop-under-cap-test
  (testing "does not stop when under generation cap"
    (binding [auto/max-generations 5]
      (let [reason (#'auto/check-stop-conditions
                    {:generations-run 3 :total-tokens 0 :recent-scores []})]
        (is (nil? reason))))))

(deftest stop-on-token-budget-test
  (testing "stops when token budget is exhausted"
    (binding [auto/token-budget 100000]
      (let [reason (#'auto/check-stop-conditions
                    {:generations-run 1 :total-tokens 100000 :recent-scores []})]
        (is (some? reason))
        (is (re-find #"Token budget" reason))))))

(deftest no-stop-unlimited-budget-test
  (testing "does not stop when budget is 0 (unlimited)"
    (binding [auto/token-budget 0]
      (let [reason (#'auto/check-stop-conditions
                    {:generations-run 1 :total-tokens 999999 :recent-scores []})]
        (is (nil? reason))))))

(deftest stop-on-plateau-test
  (testing "stops when fitness plateaus"
    (binding [auto/plateau-window 3
              auto/max-generations 0
              auto/token-budget 0]
      (let [reason (#'auto/check-stop-conditions
                    {:generations-run 5 :total-tokens 0
                     :recent-scores [100.0 100.0 100.0]})]
        (is (some? reason))
        (is (re-find #"plateau" reason))))))

(deftest no-stop-improving-test
  (testing "does not stop when fitness is improving"
    (binding [auto/plateau-window 3
              auto/max-generations 0
              auto/token-budget 0]
      (let [reason (#'auto/check-stop-conditions
                    {:generations-run 5 :total-tokens 0
                     :recent-scores [100.0 110.0 120.0]})]
        (is (nil? reason))))))

(deftest no-stop-insufficient-data-test
  (testing "does not stop when not enough scores for plateau detection"
    (binding [auto/plateau-window 3
              auto/max-generations 0
              auto/token-budget 0]
      (let [reason (#'auto/check-stop-conditions
                    {:generations-run 2 :total-tokens 0
                     :recent-scores [100.0]})]
        (is (nil? reason))))))

;; ---------------------------------------------------------------------------
;; Fitness log tests
;; ---------------------------------------------------------------------------

(deftest fitness-log-roundtrip-test
  (testing "append and read fitness log entries"
    (let [dir  (make-tmpdir)
          tmp  (.join path dir "tmp")]
      (.mkdirSync fs tmp #js {:recursive true})
      (auto/append-fitness-log dir {:generation 1 :outcome "promoted" :fitness-score 42.0})
      (auto/append-fitness-log dir {:generation 2 :outcome "rolled-back" :fitness-score 38.5})
      (let [entries (auto/read-fitness-log dir)]
        (is (= 2 (count entries)))
        (is (= 1 (:generation (first entries))))
        (is (= "promoted" (:outcome (first entries))))
        (is (= 42.0 (:fitness-score (first entries))))
        (is (= 2 (:generation (second entries)))))
      (cleanup dir))))

(deftest fitness-log-empty-test
  (testing "read returns empty vec when file doesn't exist"
    (let [entries (auto/read-fitness-log "/nonexistent/path")]
      (is (= [] entries)))))

;; ---------------------------------------------------------------------------
;; parse-verify-result tests
;; ---------------------------------------------------------------------------

(deftest parse-verify-pass-approved-test
  (testing "parses a passing, approved verification"
    (let [result (#'auto/parse-verify-result
                  "Result: PASS\nRan 10 tests containing 25 assertions.\n0 failures, 0 errors.\nLLM Review: APPROVED (confidence: high)")]
      (is (true? (:passed? result)))
      (is (true? (:llm-approved? result)))
      (is (= 10 (get-in result [:test-results :tests-run])))
      (is (= 25 (get-in result [:test-results :assertions])))
      (is (= 0 (get-in result [:test-results :failures]))))))

(deftest parse-verify-fail-test
  (testing "parses a failing verification"
    (let [result (#'auto/parse-verify-result
                  "Result: FAIL\nRan 8 tests containing 20 assertions.\n2 failures, 1 errors.")]
      (is (false? (:passed? result)))
      (is (false? (:llm-approved? result)))
      (is (= 2 (get-in result [:test-results :failures])))
      (is (= 1 (get-in result [:test-results :errors]))))))

(deftest parse-verify-pass-rejected-test
  (testing "parses tests passing but LLM rejecting"
    (let [result (#'auto/parse-verify-result
                  "Result: PASS\nRan 10 tests containing 25 assertions.\n0 failures, 0 errors.\nLLM Review: REJECTED (confidence: high)")]
      (is (true? (:passed? result)))
      (is (false? (:llm-approved? result))))))

;; ---------------------------------------------------------------------------
;; Fitness log new-field tests
;; ---------------------------------------------------------------------------

(deftest fitness-log-new-fields-roundtrip-test
  (testing "fitness log preserves failure-reason, cycle-duration-ms, phase-timing, program-summary"
    (let [dir (make-tmpdir)
          tmp (.join path dir "tmp")]
      (.mkdirSync fs tmp #js {:recursive true})
      (auto/append-fitness-log dir {:generation        3
                                    :outcome           "rolled-back"
                                    :fitness-score     nil
                                    :failure-reason    "tests-failed"
                                    :cycle-duration-ms 4200
                                    :phase-timing      {:reflect-ms 1000
                                                        :spawn-ms   2000
                                                        :verify-ms  1200
                                                        :total-ms   4200}
                                    :program-summary   "Improve test coverage"
                                    :timestamp         "2026-03-17T00:00:00.000Z"})
      (let [entries (auto/read-fitness-log dir)
            entry   (first entries)]
        (is (= 1 (count entries)))
        (is (= "tests-failed" (:failure-reason entry)))
        (is (= 4200 (:cycle-duration-ms entry)))
        (is (= "Improve test coverage" (:program-summary entry)))
        (is (= 1000 (get-in entry [:phase-timing :reflect-ms])))
        (is (= 4200 (get-in entry [:phase-timing :total-ms]))))
      (cleanup dir))))

(deftest fitness-log-promoted-no-failure-reason-test
  (testing "fitness log records nil failure-reason for promoted cycles"
    (let [dir (make-tmpdir)
          tmp (.join path dir "tmp")]
      (.mkdirSync fs tmp #js {:recursive true})
      (auto/append-fitness-log dir {:generation        1
                                    :outcome           "promoted"
                                    :fitness-score     95.0
                                    :failure-reason    nil
                                    :cycle-duration-ms 3000
                                    :program-summary   "Add new feature"
                                    :timestamp         "2026-03-17T00:00:00.000Z"})
      (let [entries (auto/read-fitness-log dir)
            entry   (first entries)]
        (is (= 1 (count entries)))
        (is (nil? (:failure-reason entry)))
        (is (= "Add new feature" (:program-summary entry))))
      (cleanup dir))))

;; ---------------------------------------------------------------------------
;; Lessons log tests
;; ---------------------------------------------------------------------------

(deftest lessons-log-roundtrip-test
  (testing "append and read lessons log entries"
    (let [dir (make-tmpdir)
          tmp (.join path dir "tmp")]
      (.mkdirSync fs tmp #js {:recursive true})
      (auto/append-lesson dir {:generation        1
                               :outcome           "promoted"
                               :failure-reason    nil
                               :task-summary      "Add caching layer"
                               :cycle-duration-ms 5000
                               :what-worked       "Task completed successfully, all tests passed, LLM review approved"
                               :what-didnt        nil
                               :timestamp         "2026-03-17T00:00:00.000Z"})
      (auto/append-lesson dir {:generation        2
                               :outcome           "rolled-back"
                               :failure-reason    "tests-failed"
                               :task-summary      "Refactor auth module"
                               :cycle-duration-ms 3000
                               :what-worked       "Lab completed work but tests regressed"
                               :what-didnt        "Test failures: 3 failures, 1 errors"
                               :timestamp         "2026-03-17T01:00:00.000Z"})
      (let [entries (auto/read-lessons dir)]
        (is (= 2 (count entries)))
        (is (= 1 (:generation (first entries))))
        (is (= "promoted" (:outcome (first entries))))
        (is (= "Add caching layer" (:task-summary (first entries))))
        (is (= "Task completed successfully, all tests passed, LLM review approved"
               (:what-worked (first entries))))
        (is (nil? (:what-didnt (first entries))))
        (is (= 2 (:generation (second entries))))
        (is (= "rolled-back" (:outcome (second entries))))
        (is (= "Test failures: 3 failures, 1 errors" (:what-didnt (second entries)))))
      (cleanup dir))))

(deftest lessons-log-empty-test
  (testing "read-lessons returns empty vec when file doesn't exist"
    (let [entries (auto/read-lessons "/nonexistent/path")]
      (is (= [] entries)))))

;; ---------------------------------------------------------------------------
;; derive-lesson-fields tests
;; ---------------------------------------------------------------------------

(deftest derive-lesson-promoted-test
  (testing "promoted outcome produces correct what-worked/what-didnt"
    (let [{:keys [what-worked what-didnt]}
          (auto/derive-lesson-fields {:outcome "promoted" :failure-reason nil :test-results nil})]
      (is (= "Task completed successfully, all tests passed, LLM review approved" what-worked))
      (is (nil? what-didnt)))))

(deftest derive-lesson-tests-failed-test
  (testing "rolled-back with tests-failed produces failure counts"
    (let [{:keys [what-worked what-didnt]}
          (auto/derive-lesson-fields {:outcome        "rolled-back"
                                      :failure-reason "tests-failed"
                                      :test-results   {:failures 2 :errors 1}})]
      (is (= "Lab completed work but tests regressed" what-worked))
      (is (= "Test failures: 2 failures, 1 errors" what-didnt)))))

(deftest derive-lesson-llm-rejected-test
  (testing "rolled-back with llm-rejected outcome"
    (let [{:keys [what-worked what-didnt]}
          (auto/derive-lesson-fields {:outcome        "rolled-back"
                                      :failure-reason "llm-rejected"
                                      :test-results   {:failures 0 :errors 0}})]
      (is (= "Tests passed but code quality/correctness was rejected" what-worked))
      (is (= "LLM review rejected the changes" what-didnt)))))

(deftest derive-lesson-spawn-failed-test
  (testing "spawn-failed outcome"
    (let [{:keys [what-worked what-didnt]}
          (auto/derive-lesson-fields {:outcome        "spawn-failed"
                                      :failure-reason "lab-failed"
                                      :test-results   nil})]
      (is (nil? what-worked))
      (is (= "Spawn failed: lab-failed" what-didnt)))))

(deftest derive-lesson-reflect-failed-test
  (testing "reflect-failed outcome"
    (let [{:keys [what-worked what-didnt]}
          (auto/derive-lesson-fields {:outcome        "reflect-failed"
                                      :failure-reason "reflect-failed"
                                      :test-results   nil})]
      (is (nil? what-worked))
      (is (= "Reflect step failed to produce a valid program.md" what-didnt)))))

(deftest derive-lesson-tests-failed-zero-counts-test
  (testing "tests-failed with nil test-results defaults to 0 counts"
    (let [{:keys [what-didnt]}
          (auto/derive-lesson-fields {:outcome        "rolled-back"
                                      :failure-reason "tests-failed"
                                      :test-results   nil})]
      (is (= "Test failures: 0 failures, 0 errors" what-didnt)))))
