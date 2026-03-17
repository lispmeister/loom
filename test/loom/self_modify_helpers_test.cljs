(ns loom.self-modify-helpers-test
  (:require [cljs.test :refer [deftest is testing]]
            [loom.agent.self-modify :as sm]))

;; ---------------------------------------------------------------------------
;; parse-test-counts
;; ---------------------------------------------------------------------------

(deftest parse-test-counts-passing
  (testing "parses passing test output correctly"
    (let [output "Ran 42 tests containing 100 assertions.\n0 failures, 0 errors."
          result (sm/parse-test-counts output true)]
      (is (= 42 (:tests-run result)))
      (is (= 100 (:assertions result)))
      (is (= 0 (:failures result)))
      (is (= 0 (:errors result)))
      (is (true? (:passed? result))))))

(deftest parse-test-counts-failing
  (testing "parses failing test output correctly"
    (let [output "Ran 10 tests containing 25 assertions.\n2 failures, 1 errors."
          result (sm/parse-test-counts output false)]
      (is (= 10 (:tests-run result)))
      (is (= 25 (:assertions result)))
      (is (= 2 (:failures result)))
      (is (= 1 (:errors result)))
      (is (false? (:passed? result))))))

(deftest parse-test-counts-no-match
  (testing "returns zeroes for garbage input"
    (let [result (sm/parse-test-counts "some garbage output" false)]
      (is (= 0 (:tests-run result)))
      (is (= 0 (:assertions result)))
      (is (= 0 (:failures result)))
      (is (= 0 (:errors result)))
      (is (false? (:passed? result))))))

;; ---------------------------------------------------------------------------
;; parse-shortstat
;; ---------------------------------------------------------------------------

(deftest parse-shortstat-normal
  (testing "parses normal shortstat output with files, insertions, and deletions"
    (let [output " 3 files changed, 37 insertions(+), 11 deletions(-)"
          result (sm/parse-shortstat output)]
      (is (= 3 (:files-changed result)))
      (is (= 37 (:insertions result)))
      (is (= 11 (:deletions result))))))

(deftest parse-shortstat-insertions-only
  (testing "parses shortstat with only insertions (no deletions)"
    (let [output " 1 file changed, 5 insertions(+)"
          result (sm/parse-shortstat output)]
      (is (= 1 (:files-changed result)))
      (is (= 5 (:insertions result)))
      (is (= 0 (:deletions result))))))

(deftest parse-shortstat-no-match
  (testing "returns zeroes for empty/garbage input"
    (let [result (sm/parse-shortstat "")]
      (is (= 0 (:files-changed result)))
      (is (= 0 (:insertions result)))
      (is (= 0 (:deletions result))))))
