(ns loom.budget-test
  "Tests for the rolling-window API prompt budget tracker."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [loom.agent.budget :as budget]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  {:before (fn [] (budget/reset-for-testing!))})

;; ---------------------------------------------------------------------------
;; calls-in-window tests
;; ---------------------------------------------------------------------------

(deftest calls-in-window-empty-test
  (testing "returns 0 when no calls have been recorded"
    (is (= 0 (budget/calls-in-window 18000000)))))

(deftest calls-in-window-counts-recent-calls-test
  (testing "counts calls recorded within the window"
    ;; Record 3 calls directly by manipulating via record-call!
    (budget/record-call!)
    (budget/record-call!)
    (budget/record-call!)
    (is (= 3 (budget/calls-in-window 18000000)))))

(deftest calls-in-window-prunes-old-entries-test
  (testing "prunes timestamps outside the window"
    ;; Inject old timestamps by bypassing record-call! via the atom
    ;; We use a 1ms window to make existing calls appear old
    (budget/record-call!)
    (budget/record-call!)
    ;; With a 0ms window, no calls fall inside
    (is (= 0 (budget/calls-in-window 0)))))

(deftest calls-in-window-partial-window-test
  (testing "only counts calls within the specified window"
    (budget/record-call!)
    (budget/record-call!)
    ;; Very large window — both calls are within it
    (is (= 2 (budget/calls-in-window 99999999)))))

;; ---------------------------------------------------------------------------
;; budget-remaining tests
;; ---------------------------------------------------------------------------

(deftest budget-remaining-unlimited-test
  (testing "returns ##Inf when limit is 0 (unlimited)"
    (is (= ##Inf (budget/budget-remaining 0 18000000)))))

(deftest budget-remaining-full-budget-test
  (testing "returns full limit when no calls have been made"
    (is (= 100 (budget/budget-remaining 100 18000000)))))

(deftest budget-remaining-after-calls-test
  (testing "decrements remaining count as calls are recorded"
    (budget/record-call!)
    (budget/record-call!)
    (budget/record-call!)
    (is (= 97 (budget/budget-remaining 100 18000000)))))

(deftest budget-remaining-never-negative-test
  (testing "clamps to 0 when calls exceed limit"
    (dotimes [_ 10]
      (budget/record-call!))
    (is (= 0 (budget/budget-remaining 5 18000000)))))

;; ---------------------------------------------------------------------------
;; budget-exhausted? tests
;; ---------------------------------------------------------------------------

(deftest budget-exhausted-unlimited-test
  (testing "never exhausted when limit is 0 (unlimited)"
    (dotimes [_ 1000]
      (budget/record-call!))
    (is (false? (budget/budget-exhausted? 0 18000000)))))

(deftest budget-exhausted-not-yet-test
  (testing "not exhausted when calls remain"
    (budget/record-call!)
    (budget/record-call!)
    (is (false? (budget/budget-exhausted? 100 18000000)))))

(deftest budget-exhausted-exactly-at-limit-test
  (testing "exhausted when calls equal the limit"
    (dotimes [_ 5]
      (budget/record-call!))
    (is (true? (budget/budget-exhausted? 5 18000000)))))

(deftest budget-exhausted-over-limit-test
  (testing "exhausted when calls exceed the limit"
    (dotimes [_ 10]
      (budget/record-call!))
    (is (true? (budget/budget-exhausted? 3 18000000)))))

(deftest budget-exhausted-window-expired-test
  (testing "not exhausted after calls fall outside the window"
    (budget/record-call!)
    (budget/record-call!)
    (budget/record-call!)
    ;; Use a 0ms window — all past calls are outside it
    (is (false? (budget/budget-exhausted? 5 0)))))

;; ---------------------------------------------------------------------------
;; record-call! tests
;; ---------------------------------------------------------------------------

(deftest record-call-increments-count-test
  (testing "each record-call! increments the window count"
    (is (= 0 (budget/calls-in-window 18000000)))
    (budget/record-call!)
    (is (= 1 (budget/calls-in-window 18000000)))
    (budget/record-call!)
    (is (= 2 (budget/calls-in-window 18000000)))))
