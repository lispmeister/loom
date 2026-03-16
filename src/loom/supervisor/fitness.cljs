(ns loom.supervisor.fitness
  "Fitness score calculation for generation reports.
   Determines whether a generation represents an improvement.")

(defn safety-check
  "Check safety constraints: tests must pass and test count must not decrease.
   Returns {:safe? bool :reason \"...\" (when not safe)}."
  [test-results previous-test-results]
  (cond
    (nil? test-results)
    {:safe? false :reason "No test results available"}

    (not (:passed? test-results))
    {:safe? false :reason (str "Tests failed: " (:failures test-results) " failures, "
                               (:errors test-results) " errors")}

    (and previous-test-results
         (> (:tests-run previous-test-results 0) (:tests-run test-results 0)))
    {:safe? false :reason (str "Test count regressed: " (:tests-run test-results)
                               " < " (:tests-run previous-test-results))}

    :else
    {:safe? true}))

(defn fitness-score
  "Compute fitness score from a generation report.
   score = (tests-run * 10) + (assertions * 1) - (total-tokens / 1000)
   Returns a number. Higher is better."
  [{:keys [test-results token-usage]}]
  (let [tests      (:tests-run test-results 0)
        assertions (:assertions test-results 0)
        tokens     (+ (:input token-usage 0) (:output token-usage 0))]
    (- (+ (* tests 10) assertions)
       (/ tokens 1000))))

(defn improved?
  "Is this generation an improvement over the previous one?
   Returns {:improved? bool :current-score N :previous-score N :safe? bool :reason \"...\"}."
  [current-report previous-report]
  (let [safety   (safety-check (:test-results current-report)
                               (:test-results previous-report))
        current  (fitness-score current-report)
        previous (if previous-report (fitness-score previous-report) 0)]
    {:improved?      (and (:safe? safety) (>= current previous))
     :current-score  current
     :previous-score previous
     :safe?          (:safe? safety)
     :reason         (or (:reason safety)
                         (when (< current previous)
                           (str "Score regressed: " current " < " previous)))}))
