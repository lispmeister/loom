(ns loom.agent.autonomous
  "Autonomous loop driver: reflect → spawn → verify → promote/rollback → repeat.
   Runs until a stopping condition is met."
  (:require [clojure.string :as str]
            [loom.agent.reflect :as reflect]
            [loom.agent.self-modify :as sm]
            [loom.supervisor.fitness :as fitness]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]))

;; ---------------------------------------------------------------------------
;; Configuration from env
;; ---------------------------------------------------------------------------

(def ^:dynamic max-generations
  "Maximum generations to run. 0 = unlimited."
  (let [v (some-> js/process .-env .-LOOM_MAX_GENERATIONS)]
    (if v (js/parseInt v 10) 5)))

(def ^:dynamic token-budget
  "Maximum cumulative tokens (input + output) across all generations. 0 = unlimited."
  (let [v (some-> js/process .-env .-LOOM_TOKEN_BUDGET)]
    (if v (js/parseInt v 10) 0)))

(def ^:dynamic plateau-window
  "Stop if no fitness improvement over this many consecutive generations."
  (let [v (some-> js/process .-env .-LOOM_PLATEAU_WINDOW)]
    (if v (js/parseInt v 10) 3)))

;; ---------------------------------------------------------------------------
;; program.md helpers
;; ---------------------------------------------------------------------------

(defn- program-md-hash
  "Return the SHA-256 hex digest of a program.md string, or nil if text is nil."
  [text]
  (when text
    (-> (.createHash crypto "sha256")
        (.update text "utf8")
        (.digest "hex"))))

(defn- infer-task-type
  "Infer a task category from the content of a program.md string.
   Checks for keywords in priority order and returns a string label."
  [text]
  (let [lower (if text (str/lower-case text) "")]
    (cond
      (or (str/includes? lower "refactor") (str/includes? lower "clean"))       "refactor"
      (or (str/includes? lower "test")     (str/includes? lower "spec"))        "test-addition"
      (or (str/includes? lower "fix")      (str/includes? lower "bug"))         "bug-fix"
      (or (str/includes? lower "add")
          (str/includes? lower "new")
          (str/includes? lower "implement")
          (str/includes? lower "create"))                                        "new-feature"
      (or (str/includes? lower "edit")
          (str/includes? lower "modify")
          (str/includes? lower "update")
          (str/includes? lower "change"))                                        "file-edit"
      :else                                                                      "other")))

;; ---------------------------------------------------------------------------
;; Fitness log (append-only JSONL)
;; ---------------------------------------------------------------------------

(defn- fitness-log-path
  "Path to the fitness log file."
  [repo]
  (.join path repo "tmp" "fitness-log.jsonl"))

;; ---------------------------------------------------------------------------
;; Lessons log (append-only JSONL)
;; ---------------------------------------------------------------------------

(defn- lessons-log-path
  "Path to the lessons log file."
  [repo]
  (.join path repo "tmp" "lessons.jsonl"))

(defn derive-lesson-fields
  "Derive :what-worked and :what-didnt from structured cycle data.
   Returns {:what-worked str-or-nil :what-didnt str-or-nil}.
   No LLM call — derived purely from outcome, failure-reason, and test-results."
  [{:keys [outcome failure-reason test-results]}]
  (case outcome
    "promoted"
    {:what-worked "Task completed successfully, all tests passed, LLM review approved"
     :what-didnt  nil}

    "rolled-back"
    (case failure-reason
      "tests-failed"
      (let [failures (:failures test-results 0)
            errors   (:errors test-results 0)]
        {:what-worked "Lab completed work but tests regressed"
         :what-didnt  (str "Test failures: " failures " failures, " errors " errors")})

      "llm-rejected"
      {:what-worked "Tests passed but code quality/correctness was rejected"
       :what-didnt  "LLM review rejected the changes"}

      ;; fallback for other rolled-back reasons
      {:what-worked nil
       :what-didnt  (str "Rolled back: " (or failure-reason "unknown reason"))})

    "spawn-failed"
    {:what-worked nil
     :what-didnt  (str "Spawn failed: " (or failure-reason "unknown reason"))}

    "reflect-failed"
    {:what-worked nil
     :what-didnt  "Reflect step failed to produce a valid program.md"}

    ;; unknown outcome
    {:what-worked nil
     :what-didnt  (str "Unknown outcome: " (or outcome "nil"))}))

(defn append-lesson
  "Append one JSON line to the lessons log."
  [repo entry]
  (let [filepath (lessons-log-path repo)
        line     (str (js/JSON.stringify (clj->js entry)) "\n")]
    (try
      (.appendFileSync fs filepath line "utf8")
      (catch :default e
        (println (str "[autonomous] Warning: failed to write lessons log: " (.-message e)))))))

(defn read-lessons
  "Read the lessons log. Returns a vector of maps, or []."
  [repo]
  (let [filepath (lessons-log-path repo)]
    (try
      (let [content (.readFileSync fs filepath "utf8")
            lines   (filter seq (str/split-lines content))]
        (mapv (fn [line]
                (js->clj (js/JSON.parse line) :keywordize-keys true))
              lines))
      (catch :default _e []))))

(defn append-fitness-log
  "Append one JSON line to the fitness log."
  [repo entry]
  (let [filepath (fitness-log-path repo)
        line (str (js/JSON.stringify (clj->js entry)) "\n")]
    (try
      (.appendFileSync fs filepath line "utf8")
      (catch :default e
        (println (str "[autonomous] Warning: failed to write fitness log: " (.-message e)))))))

(defn read-fitness-log
  "Read the fitness log. Returns a vector of maps, or []."
  [repo]
  (let [filepath (fitness-log-path repo)]
    (try
      (let [content (.readFileSync fs filepath "utf8")
            lines   (filter seq (str/split-lines content))]
        (mapv (fn [line]
                (js->clj (js/JSON.parse line) :keywordize-keys true))
              lines))
      (catch :default _e []))))

;; ---------------------------------------------------------------------------
;; Stopping conditions
;; ---------------------------------------------------------------------------

(defn- check-stop-conditions
  "Check if the loop should stop. Returns nil to continue, or a reason string to stop."
  [{:keys [generations-run total-tokens recent-scores]}]
  (cond
    ;; Generation cap
    (and (pos? max-generations) (>= generations-run max-generations))
    (str "Generation cap reached (" max-generations ")")

    ;; Token budget
    (and (pos? token-budget) (>= total-tokens token-budget))
    (str "Token budget exhausted (" total-tokens "/" token-budget ")")

    ;; Fitness plateau — no improvement over plateau-window consecutive promoted generations
    (and (pos? plateau-window)
         (>= (count recent-scores) plateau-window)
         (let [scores (take-last plateau-window recent-scores)]
           (apply = scores)))
    (str "Fitness plateau: no improvement over " plateau-window " generations")))

;; ---------------------------------------------------------------------------
;; Single cycle: reflect → spawn → verify → promote/rollback
;; ---------------------------------------------------------------------------

(defn- parse-verify-result
  "Extract structured data from verify_generation's text output.
   Returns {:passed? bool :test-results map :llm-verdict map}."
  [verify-text]
  (let [passed?     (boolean (re-find #"Result: PASS" verify-text))
        approved?   (boolean (re-find #"LLM Review: APPROVED" verify-text))
        ;; Extract test counts from summary lines
        tests-match (re-find #"Ran (\d+) tests containing (\d+) assertions" verify-text)
        fail-match  (re-find #"(\d+) failures" verify-text)
        err-match   (re-find #"(\d+) errors" verify-text)]
    {:passed?      passed?
     :llm-approved? approved?
     :test-results {:tests-run  (if tests-match (js/parseInt (nth tests-match 1) 10) 0)
                    :assertions (if tests-match (js/parseInt (nth tests-match 2) 10) 0)
                    :failures   (if fail-match (js/parseInt (nth fail-match 1) 10) 0)
                    :errors     (if err-match (js/parseInt (nth err-match 1) 10) 0)
                    :passed?    passed?}}))

(defn- run-cycle
  "Run one reflect-spawn-verify-promote/rollback cycle.
   Returns a promise resolving to:
   {:generation N :outcome :promoted/:rolled-back/:spawn-failed/:reflect-failed
    :test-results map :program-md str :reflect-token-usage map
    :failure-reason str-or-nil :timing map :program-summary str-or-nil}"
  [repo lookback]
  (println "\n[autonomous] === Starting new cycle ===")
  (let [cycle-start (.now js/Date)]
    (-> (reflect/reflect-and-propose {:repo_path repo :lookback lookback})
        (.then
         (fn [reflect-result]
           (let [reflect-end  (.now js/Date)
                 reflect-tok  (when (map? reflect-result) (:token-usage reflect-result))
                 program-md   (if (map? reflect-result)
                                (:program-md reflect-result)
                                reflect-result)]
             (println (str "[autonomous] Reflect proposed:\n"
                           (subs program-md 0 (min 200 (count program-md)))
                           (when (> (count program-md) 200) "...")))
             (if (str/starts-with? program-md "Error:")
               {:generation          nil
                :outcome             :reflect-failed
                :failure-reason      "reflect-failed"
                :timing              {:reflect-ms (- reflect-end cycle-start)
                                      :spawn-ms   0
                                      :verify-ms  0
                                      :total-ms   (- reflect-end cycle-start)}
                :program-summary     nil
                :error               program-md
                :program-md          nil
                :reflect-token-usage reflect-tok}
               (let [program-summary (-> program-md
                                         str/split-lines
                                         first
                                         (str/replace #"^#\s*" ""))]
                 ;; Spawn the Lab
                 (-> (sm/spawn-lab {:program_md program-md :source "reflect"})
                     (.then
                      (fn [spawn-result]
                        (let [spawn-end (.now js/Date)]
                          (println (str "[autonomous] Spawn result: "
                                        (subs spawn-result 0 (min 100 (count spawn-result)))))
                          (let [gen-match (re-find #"Generation: (\d+)" spawn-result)]
                            (if-not gen-match
                              {:generation          nil
                               :outcome             :spawn-failed
                               :failure-reason      "spawn-error"
                               :timing              {:reflect-ms (- reflect-end cycle-start)
                                                     :spawn-ms   (- spawn-end reflect-end)
                                                     :verify-ms  0
                                                     :total-ms   (- spawn-end cycle-start)}
                               :program-summary     program-summary
                               :error               spawn-result
                               :program-md          program-md
                               :reflect-token-usage reflect-tok}
                              (let [gen     (js/parseInt (nth gen-match 1) 10)
                                    failed? (re-find #"Lab failed" spawn-result)]
                                (if failed?
                                  (do (println (str "[autonomous] Gen " gen " Lab failed, rolling back"))
                                      (-> (sm/rollback-generation {:generation gen})
                                          (.then
                                           (fn [_]
                                             {:generation          gen
                                              :outcome             :spawn-failed
                                              :failure-reason      "lab-failed"
                                              :timing              {:reflect-ms (- reflect-end cycle-start)
                                                                    :spawn-ms   (- spawn-end reflect-end)
                                                                    :verify-ms  0
                                                                    :total-ms   (- spawn-end cycle-start)}
                                              :program-summary     program-summary
                                              :error               spawn-result
                                              :program-md          program-md
                                              :reflect-token-usage reflect-tok}))))
                                  ;; Verify the generation
                                  (do (println (str "[autonomous] Gen " gen " done, verifying..."))
                                      (-> (sm/verify-generation {:generation gen :repo_path repo})
                                          (.then
                                           (fn [verify-text]
                                             (let [verify-end (.now js/Date)]
                                               (println (str "[autonomous] Verification:\n" verify-text))
                                               (let [{:keys [passed? llm-approved? test-results]}
                                                     (parse-verify-result verify-text)
                                                     should-promote? (and passed? llm-approved?)
                                                     timing {:reflect-ms (- reflect-end cycle-start)
                                                             :spawn-ms   (- spawn-end reflect-end)
                                                             :verify-ms  (- verify-end spawn-end)
                                                             :total-ms   (- verify-end cycle-start)}]
                                                 (if should-promote?
                                                   ;; Promote
                                                   (do (println (str "[autonomous] Gen " gen " PASSED — promoting"))
                                                       (-> (sm/promote-generation {:generation gen})
                                                           (.then
                                                            (fn [promote-result]
                                                              (println (str "[autonomous] " promote-result))
                                                              {:generation          gen
                                                               :outcome             :promoted
                                                               :failure-reason      nil
                                                               :timing              timing
                                                               :program-summary     program-summary
                                                               :test-results        test-results
                                                               :program-md          program-md
                                                               :reflect-token-usage reflect-tok}))))
                                                   ;; Rollback
                                                   (let [failure-reason (cond
                                                                          (not passed?)
                                                                          "tests-failed"
                                                                          (and passed? (not llm-approved?))
                                                                          "llm-rejected"
                                                                          :else
                                                                          "verify-failed")]
                                                     (println (str "[autonomous] Gen " gen
                                                                   " FAILED verification — rolling back"
                                                                   (when-not passed? " (tests failed)")
                                                                   (when (and passed? (not llm-approved?))
                                                                     " (LLM rejected)")))
                                                     (-> (sm/rollback-generation {:generation gen})
                                                         (.then
                                                          (fn [_]
                                                            {:generation          gen
                                                             :outcome             :rolled-back
                                                             :failure-reason      failure-reason
                                                             :timing              timing
                                                             :program-summary     program-summary
                                                             :test-results        test-results
                                                             :program-md          program-md
                                                             :reflect-token-usage reflect-tok})))))))))))))))))))))))))))

;; ---------------------------------------------------------------------------
;; Main loop
;; ---------------------------------------------------------------------------

(defonce ^:private shutdown-requested (atom false))

(defn run-loop
  "Run the autonomous improvement loop until a stopping condition is met.
   Returns a promise resolving to a summary map.
   Options:
     :repo     — path to repo (default \".\")
     :lookback — generations for reflect context (default 5)"
  [{:keys [repo lookback]
    :or   {repo "." lookback 5}}]
  ;; Trap SIGINT for graceful shutdown
  (let [handler (fn [] (println "\n[autonomous] Shutdown requested, finishing current cycle...")
                  (reset! shutdown-requested true))]
    (.on js/process "SIGINT" handler)
    (.on js/process "SIGTERM" handler))

  (println "[autonomous] Starting autonomous loop")
  (println (str "[autonomous] Max generations: " (if (pos? max-generations) max-generations "unlimited")))
  (println (str "[autonomous] Token budget: " (if (pos? token-budget) token-budget "unlimited")))
  (println (str "[autonomous] Plateau window: " plateau-window))

  (let [state (atom {:generations-run 0
                     :total-tokens    0
                     :promoted        0
                     :rolled-back     0
                     :failed          0
                     :recent-scores   []})]

    (letfn [(loop-step []
              ;; Check stopping conditions
              (let [stop-reason (or (check-stop-conditions @state)
                                    (when @shutdown-requested "User interrupt (SIGINT)"))]
                (if stop-reason
                  (do (println (str "\n[autonomous] Stopping: " stop-reason))
                      (println (str "[autonomous] Summary: "
                                    (:generations-run @state) " generations, "
                                    (:promoted @state) " promoted, "
                                    (:rolled-back @state) " rolled back, "
                                    (:failed @state) " failed"))
                      (js/Promise.resolve (assoc @state :stop-reason stop-reason)))

                  ;; Run one cycle
                  (-> (run-cycle repo lookback)
                      (.then (fn [result]
                               ;; Read token usage from the generation report (Lab-side)
                               (let [gen             (:generation result)
                                     report          (when gen (reflect/read-report repo gen))
                                     tok-usage       (or (:token-usage report) {:input 0 :output 0})
                                     gen-tokens      (+ (:input tok-usage 0) (:output tok-usage 0))
                                     ;; Reflect-step token usage
                                     reflect-tok     (:reflect-token-usage result)
                                     reflect-tokens  (+ (:input reflect-tok 0) (:output reflect-tok 0))
                                     ;; LLM review token usage (stored in last-verification atom)
                                     verification    @sm/last-verification
                                     review-tok      (when (and verification (= gen (:generation verification)))
                                                       (:review-token-usage verification))
                                     review-tokens   (+ (:input review-tok 0) (:output review-tok 0))
                                     ;; Total tokens this cycle
                                     cycle-tokens    (+ gen-tokens reflect-tokens review-tokens)
                                     test-res        (:test-results result)
                                     score           (when test-res
                                                       (fitness/fitness-score
                                                        {:test-results test-res
                                                         :token-usage  tok-usage}))]

                                 ;; Update state
                                 (swap! state (fn [s]
                                                (let [outcome-key (case (:outcome result)
                                                                    :promoted    :promoted
                                                                    :rolled-back :rolled-back
                                                                    :failed)]
                                                  (let [s1 (-> s
                                                               (update :generations-run inc)
                                                               (update :total-tokens + cycle-tokens)
                                                               (update outcome-key inc))]
                                                    (if (and score (= :promoted (:outcome result)))
                                                      (update s1 :recent-scores conj score)
                                                      s1)))))

                                 ;; Log to fitness log
                                 (append-fitness-log repo
                                                     {:generation          gen
                                                      :outcome             (name (or (:outcome result) :unknown))
                                                      :fitness-score       score
                                                      :tests-run           (:tests-run test-res)
                                                      :assertions          (:assertions test-res)
                                                      :token-usage         tok-usage
                                                      :reflect-token-usage reflect-tok
                                                      :review-token-usage  review-tok
                                                      :promoted?           (= :promoted (:outcome result))
                                                      :failure-reason      (:failure-reason result)
                                                      :cycle-duration-ms   (get-in result [:timing :total-ms])
                                                      :phase-timing        (:timing result)
                                                      :program-summary     (:program-summary result)
                                                      :program-md-hash     (program-md-hash (:program-md result))
                                                      :task-type           (infer-task-type (:program-md result))
                                                      :timestamp           (.toISOString (js/Date.))})

                                 ;; Log to lessons log
                                 (let [outcome-str  (name (or (:outcome result) :unknown))
                                       lesson-fields (derive-lesson-fields
                                                      {:outcome       outcome-str
                                                       :failure-reason (:failure-reason result)
                                                       :test-results   test-res})]
                                   (append-lesson repo
                                                  {:generation        gen
                                                   :outcome           outcome-str
                                                   :failure-reason    (:failure-reason result)
                                                   :task-summary      (:program-summary result)
                                                   :cycle-duration-ms (get-in result [:timing :total-ms])
                                                   :what-worked       (:what-worked lesson-fields)
                                                   :what-didnt        (:what-didnt lesson-fields)
                                                   :timestamp         (.toISOString (js/Date.))}))

                                 (println (str "[autonomous] Cycle complete: gen=" gen
                                               " outcome=" (name (or (:outcome result) :unknown))
                                               (when score (str " fitness=" (.toFixed score 1)))
                                               " total-tokens=" (:total-tokens @state))))))
                      (.catch (fn [err]
                                (println (str "[autonomous] Cycle error: " (.-message err)))
                                (swap! state (fn [s]
                                               (-> s
                                                   (update :generations-run inc)
                                                   (update :failed inc))))))
                      (.then (fn [_] (loop-step)))))))]
      (loop-step))))
