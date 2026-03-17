(ns loom.supervisor.fitness
  "Fitness score calculation for generation reports.
   Determines whether a generation represents an improvement."
  (:require [cljs.reader :as reader]
            ["node:fs" :as fs]
            ["node:path" :as path]))

;; ---------------------------------------------------------------------------
;; Config loading
;; ---------------------------------------------------------------------------

(def ^:private default-config
  {:test-weight 10
   :assertion-weight 1
   :token-penalty-divisor 1000})

(defn load-config
  "Read config/fitness.edn relative to config-path (or project root).
   Returns the merged config map, falling back to defaults on any error."
  ([] (load-config nil))
  ([config-path]
   (let [filepath (if config-path
                    config-path
                    (.join path "config" "fitness.edn"))]
     (try
       (let [content (.readFileSync fs filepath "utf8")
             parsed  (reader/read-string content)]
         (merge default-config parsed))
       (catch :default _e
         default-config)))))

(defonce ^:private cached-config (atom nil))

(defn current-config
  "Return the active fitness config. Reads from disk once and caches it.
   Config is loaded from config/fitness.edn relative to the process cwd,
   with fallback to defaults."
  []
  (when (nil? @cached-config)
    (reset! cached-config (load-config)))
  @cached-config)

;; ---------------------------------------------------------------------------
;; Fitness calculations
;; ---------------------------------------------------------------------------

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
  "Compute fitness score from a generation report using configured weights.
   score = (tests-run * test-weight) + (assertions * assertion-weight)
           - (total-tokens / token-penalty-divisor)
   Returns a number. Higher is better."
  ([report] (fitness-score report (current-config)))
  ([{:keys [test-results token-usage]} config]
   (let [{:keys [test-weight assertion-weight token-penalty-divisor]} config
         tests      (:tests-run test-results 0)
         assertions (:assertions test-results 0)
         tokens     (+ (:input token-usage 0) (:output token-usage 0))]
     (- (+ (* tests test-weight) (* assertions assertion-weight))
        (/ tokens token-penalty-divisor)))))

(defn user-task-success-rate
  "Given a vector of fitness log entries already filtered to user-source generations,
   count promoted vs total and return the success rate.
   Returns {:total N :promoted N :rate float-or-nil}.
   Rate is nil when total is 0 (avoids division by zero)."
  [entries]
  (let [total    (count entries)
        promoted (count (filter :promoted? entries))]
    {:total    total
     :promoted promoted
     :rate     (when (pos? total) (/ promoted total))}))

(defn improved?
  "Is this generation an improvement over the previous one?
   Returns {:improved? bool :current-score N :previous-score N :safe? bool :reason \"...\"}."
  [current-report previous-report]
  (let [config   (current-config)
        safety   (safety-check (:test-results current-report)
                               (:test-results previous-report))
        current  (fitness-score current-report config)
        previous (if previous-report (fitness-score previous-report config) 0)]
    {:improved?      (and (:safe? safety) (>= current previous))
     :current-score  current
     :previous-score previous
     :safe?          (:safe? safety)
     :reason         (or (:reason safety)
                         (when (< current previous)
                           (str "Score regressed: " current " < " previous)))}))
