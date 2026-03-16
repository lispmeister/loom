(ns loom.agent.self-modify
  "Self-modification tools for Prime: spawn Labs, poll status, promote/rollback."
  (:require [clojure.string :as str]
            [loom.shared.http-client :as client]
            [loom.agent.claude :as claude]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:dynamic supervisor-url
  (or (some-> js/process .-env .-LOOM_SUPERVISOR_URL)
      "http://localhost:8400"))

;; ---------------------------------------------------------------------------
;; Internal polling
;; ---------------------------------------------------------------------------

(def ^:dynamic poll-interval-ms 5000)
(def ^:dynamic poll-timeout-ms
  (let [t (some-> js/process .-env .-LOOM_LAB_TIMEOUT_MS)]
    (if t (js/parseInt t 10) 300000)))

(defn- poll-until-done
  "Poll a Lab /status URL every poll-interval-ms until status is done/failed
   or poll-timeout-ms elapses. Returns promise of final status string."
  [status-url start-time]
  (-> (client/get-json status-url :timeout 5000)
      (.catch (fn [_] {:error true :message "connection refused"}))
      (.then (fn [result]
               (let [elapsed    (- (.now js/Date) start-time)
                     ;; HTTP errors have {:error true :message "..."}.
                     ;; Lab status has {:status "..." :error "reason"} where error is a string.
                     http-err?  (true? (:error result))
                     status     (when-not http-err? (:status result))
                     lab-error  (when-not http-err? (:error result))]
                 (cond
                   ;; Lab finished
                   (= status "done")
                   (str "Lab completed successfully.\n"
                        "Progress: " (:progress result) "\n"
                        "Elapsed: " (Math/round (/ elapsed 1000)) "s")

                   ;; Lab failed
                   (= status "failed")
                   (str "Lab failed.\n"
                        "Error: " lab-error "\n"
                        "Progress: " (:progress result) "\n"
                        "Elapsed: " (Math/round (/ elapsed 1000)) "s")

                   ;; Timed out waiting
                   (>= elapsed poll-timeout-ms)
                   (str "Lab polling timed out after " (Math/round (/ elapsed 1000)) "s.\n"
                        "Last status: " (or status "unreachable") "\n"
                        "Last progress: " (:progress result ""))

                   ;; Still running or not ready — wait and retry
                   :else
                   (js/Promise.
                    (fn [resolve _]
                      (js/setTimeout
                       (fn [] (resolve (poll-until-done status-url start-time)))
                       poll-interval-ms)))))))))

;; Atom to store the last verification result for reports.
;; Defined early because promote/rollback read it to include in reports.
(defonce last-verification (atom nil))

;; ---------------------------------------------------------------------------
;; Tool implementations
;; ---------------------------------------------------------------------------

(defn spawn-lab
  "Spawn a Lab container with a program.md task spec.
   POSTs to Supervisor /spawn, then polls Lab /status until done/failed/timeout.
   Returns the final result — no separate polling tool call needed."
  [{:keys [program_md]}]
  (-> (client/post-json (str supervisor-url "/spawn")
                        {:program_md program_md})
      (.then (fn [result]
               (if (:error result)
                 (str "Error spawning Lab: " (:message result))
                 (let [gen-num  (:generation result)
                       branch   (:branch result)
                       port     (or (:host_port result) (:hostPort result) (:host-port result))
                       status-url (str "http://localhost:" port "/status")]
                   (println (str "[spawn_lab] Gen " gen-num " spawned, polling " status-url))
                   (-> (poll-until-done status-url (.now js/Date))
                       (.then (fn [poll-result]
                                (str "Generation: " gen-num "\n"
                                     "Branch: " branch "\n"
                                     poll-result))))))))))

(defn promote-generation
  "Promote a Lab generation: merge branch into main, tag, delete branch.
   POSTs to Supervisor /promote. Includes verification data from last-verification
   atom so the report contains test-results, diff-stats, and LLM verdict."
  [{:keys [generation]}]
  (let [verification @last-verification
        body (cond-> {:generation generation}
               (and verification (= generation (:generation verification)))
               (assoc :verification (dissoc verification :generation)))]
    (-> (client/post-json (str supervisor-url "/promote") body)
        (.then (fn [result]
                 (if (:error result)
                   (str "Error promoting generation " generation ": "
                        (or (:message result) (pr-str result)))
                   (str "Generation " generation " promoted successfully.\n"
                        "Status: " (:status result))))))))

(defn rollback-generation
  "Rollback a Lab generation: discard branch, mark as failed.
   POSTs to Supervisor /rollback. Includes verification data so the report
   records why the generation was rejected."
  [{:keys [generation]}]
  (let [verification @last-verification
        body (cond-> {:generation generation}
               (and verification (= generation (:generation verification)))
               (assoc :verification (dissoc verification :generation)))]
    (-> (client/post-json (str supervisor-url "/rollback") body)
        (.then (fn [result]
                 (if (:error result)
                   (str "Error rolling back generation " generation ": "
                        (or (:message result) (pr-str result)))
                   (str "Generation " generation " rolled back.\n"
                        "Status: " (:status result))))))))

;; ---------------------------------------------------------------------------
;; verify-generation helpers
;; ---------------------------------------------------------------------------

(def ^:private cp (js/require "node:child_process"))

(defn- git-exec
  "Run a git command in repo. Returns promise of {:ok true :output ...} or {:error true ...}."
  [repo & args]
  (js/Promise.
   (fn [resolve _]
     (.execFile cp "git" (clj->js args) #js {:cwd repo :maxBuffer (* 10 1024 1024)}
                (fn [err stdout stderr]
                  (if err
                    (resolve {:error true :message (str stderr)})
                    (resolve {:ok true :output (str stdout)})))))))

(defn- run-tests
  "Compile and run tests in repo. Returns promise of {:ok bool :output ... :exit N}."
  [repo]
  (js/Promise.
   (fn [resolve _]
     (.exec cp "npm test 2>&1 && node out/test.js 2>&1"
            #js {:cwd repo :timeout 120000 :maxBuffer (* 10 1024 1024)}
            (fn [err stdout _stderr]
              (resolve {:ok   (nil? err)
                        :output (str stdout)
                        :exit   (if err (or (.-code err) 1) 0)}))))))

(defn- return-to-master
  "Always checkout master. Best-effort — never rejects."
  [repo]
  (js/Promise.
   (fn [resolve _]
     (.execFile cp "git" #js ["checkout" "master"] #js {:cwd repo}
                (fn [_err _stdout _stderr] (resolve nil))))))

(defn- parse-test-counts
  "Extract structured test results from test output.
   Returns {:tests-run N :assertions N :failures N :errors N :passed? bool}."
  [output passed?]
  (let [ran-match  (re-find #"Ran (\d+) tests containing (\d+) assertions" output)
        fail-match (re-find #"(\d+) failures" output)
        err-match  (re-find #"(\d+) errors" output)]
    {:tests-run  (if ran-match (js/parseInt (nth ran-match 1) 10) 0)
     :assertions (if ran-match (js/parseInt (nth ran-match 2) 10) 0)
     :failures   (if fail-match (js/parseInt (nth fail-match 1) 10) 0)
     :errors     (if err-match (js/parseInt (nth err-match 1) 10) 0)
     :passed?    passed?}))

(defn- format-test-summary
  "Format test results into a readable string."
  [generation passed? exit output]
  (let [lines   (.split output "\n")
        summary (->> lines
                     (filter #(re-find #"Ran \d+ tests|failures|errors" %))
                     (str/join "\n"))]
    (str "Verification of gen-" generation ":\n"
         "Result: " (if passed? "PASS" "FAIL") "\n"
         "Exit code: " exit "\n"
         (when (seq summary) (str "Summary:\n" summary "\n"))
         (when-not passed?
           (str "\nFull output (last 50 lines):\n"
                (->> lines (take-last 50) (str/join "\n")))))))

(defn- parse-shortstat
  "Parse git diff --shortstat output into {:files-changed N :insertions N :deletions N}."
  [output]
  (let [files (if-let [m (re-find #"(\d+) files? changed" output)]
                (js/parseInt (nth m 1) 10) 0)
        ins   (if-let [m (re-find #"(\d+) insertions?" output)]
                (js/parseInt (nth m 1) 10) 0)
        dels  (if-let [m (re-find #"(\d+) deletions?" output)]
                (js/parseInt (nth m 1) 10) 0)]
    {:files-changed files :insertions ins :deletions dels}))

;; ---------------------------------------------------------------------------
;; LLM-powered verification (Sub-phase A)
;; ---------------------------------------------------------------------------

(defn- load-program-md
  "Load programs/gen-N.md from the programs directory.
   Returns the program text or nil if not found."
  [repo generation]
  (let [programs-dir (.join path (.dirname path
                                           (.join path repo "tmp" "generations.edn"))
                            "programs")
        filepath (.join path programs-dir (str "gen-" generation ".md"))]
    (try
      (.readFileSync fs filepath "utf8")
      (catch :default _e nil))))

(defn- build-review-prompt
  "Build the system + user messages for LLM diff review.
   The reviewer checks whether the diff completely and correctly implements
   the program.md contract."
  [diff program-md]
  {:system "You are a code reviewer for the Loom self-modification system.
Your job is to verify whether a Lab's code changes completely and correctly
implement the task described in program.md.

You MUST respond with valid JSON matching this schema:
{\"approved\": boolean,
 \"issues\": [\"description of each issue\"],
 \"confidence\": \"high\" | \"medium\" | \"low\",
 \"summary\": \"one sentence summary of your assessment\"}

Be strict. Check for:
- Completeness: did the Lab address ALL requirements in program.md?
- Correctness: are the changes logically correct?
- Missing edits: are there locations that should have been changed but weren't?
- Regressions: do the changes break existing behavior?

If the diff is empty or trivially cosmetic (whitespace, comments only), reject it."
   :messages [{:role "user"
               :content (str "## program.md (task spec)\n\n"
                             program-md
                             "\n\n## Diff (changes made by Lab)\n\n```\n"
                             (if (> (count diff) 8000)
                               (str (subs diff 0 8000) "\n... (truncated)")
                               diff)
                             "\n```\n\n"
                             "Does this diff completely and correctly implement the program.md task? "
                             "Respond with JSON only.")}]})

(defn- parse-verdict
  "Parse the LLM's JSON verdict. Returns the verdict map or a fallback on parse failure.
   Verdict: {:approved bool :issues [str] :confidence :high/:medium/:low :summary str}"
  [response-text]
  (try
    (let [;; Strip markdown code fences if present
          cleaned (-> response-text
                      (str/replace #"^```json?\s*" "")
                      (str/replace #"\s*```\s*$" "")
                      str/trim)
          parsed  (js->clj (js/JSON.parse cleaned) :keywordize-keys true)]
      {:approved   (boolean (:approved parsed))
       :issues     (vec (or (:issues parsed) []))
       :confidence (keyword (or (:confidence parsed) "low"))
       :summary    (or (:summary parsed) "")})
    (catch :default _e
      {:approved   false
       :issues     [(str "Failed to parse LLM verdict: " response-text)]
       :confidence :low
       :summary    "Verdict parse failure — treating as rejection"})))

(defn- llm-review-diff
  "Send a diff + program.md to Claude for code review.
   Returns a promise resolving to a verdict map:
   {:approved bool :issues [str] :confidence :high/:medium/:low :summary str}
   On API error, returns a rejection verdict with the error as an issue."
  [diff program-md]
  (let [api-key (.. js/process -env -ANTHROPIC_API_KEY)
        model   (or (.. js/process -env -LOOM_MODEL)
                    "claude-sonnet-4-20250514")
        {:keys [system messages]} (build-review-prompt diff program-md)]
    (if-not api-key
      (js/Promise.resolve
       {:approved   false
        :issues     ["No ANTHROPIC_API_KEY set — cannot perform LLM review"]
        :confidence :low
        :summary    "Skipped: no API key"})
      (-> (claude/send-message
           {:api-key    api-key
            :model      model
            :system     system
            :messages   messages
            :max-tokens 1024})
          (.then (fn [response]
                   (if (:error response)
                     {:approved   false
                      :issues     [(str "LLM review API error: "
                                        (or (:message response) (:body response)))]
                      :confidence :low
                      :summary    "API error — treating as rejection"}
                     (let [text (claude/extract-text response)]
                       (parse-verdict text)))))
          (.catch (fn [err]
                    {:approved   false
                     :issues     [(str "LLM review exception: " (.-message err))]
                     :confidence :low
                     :summary    "Exception — treating as rejection"}))))))

(defn verify-generation
  "Independently verify a Lab generation's work: get diff, checkout branch,
   run tests, always return to master, report results with change summary.
   Returns a string for Prime AND stores structured results in the
   :last-verification atom for the Supervisor to read.
   Input: {:generation N :repo_path \"/path/to/repo\"}"
  [{:keys [generation repo_path]}]
  (let [branch (str "lab/gen-" generation)
        repo   (or repo_path ".")]
    ;; Step 1: Get diff stat from master (before checkout, so we don't leave repo dirty)
    (-> (git-exec repo "diff" "--stat" (str "master..." branch))
        (.then (fn [diff-stat-result]
                 ;; Step 2: Get abbreviated diff + shortstat for review
                 (-> (git-exec repo "diff" (str "master..." branch))
                     (.then (fn [diff-result]
                              (-> (git-exec repo "diff" "--shortstat" (str "master..." branch))
                                  (.then (fn [shortstat-result]
                                           {:diff-stat  (when (:ok diff-stat-result) (:output diff-stat-result))
                                            :diff       (when (:ok diff-result) (:output diff-result))
                                            :diff-stats (when (:ok shortstat-result)
                                                          (parse-shortstat (:output shortstat-result)))}))))))))
        (.then (fn [diffs]
                 ;; Step 3: Checkout lab branch
                 (-> (git-exec repo "checkout" branch)
                     (.then (fn [checkout-result]
                              (if (:error checkout-result)
                                ;; Checkout failed — no cleanup needed, still on master
                                (str "Verification failed: cannot checkout " branch ": "
                                     (:message checkout-result))
                                ;; Step 4: Run tests (always return to master after, via .then+.catch)
                                (-> (run-tests repo)
                                    (.then (fn [test-result]
                                             (-> (return-to-master repo)
                                                 (.then (fn [_]
                                                          (let [test-counts (parse-test-counts
                                                                             (:output test-result)
                                                                             (:ok test-result))
                                                                program-md (load-program-md repo generation)
                                                                diff-text  (or (:diff diffs) "")
                                                                test-summary (format-test-summary
                                                                              generation (:ok test-result)
                                                                              (:exit test-result) (:output test-result))
                                                                base-result (str (:diff-stat diffs) "\n"
                                                                                 test-summary
                                                                                 "\n\nDiff:\n"
                                                                                 (let [d diff-text]
                                                                                   (if (> (count d) 5000)
                                                                                     (str (subs d 0 5000) "\n... (truncated)")
                                                                                     d)))]
                                                            ;; If tests failed, skip LLM review
                                                            (if-not (:ok test-result)
                                                              (do
                                                                (reset! last-verification
                                                                        {:generation   generation
                                                                         :test-results test-counts
                                                                         :diff-stats   (:diff-stats diffs)})
                                                                base-result)
                                                              ;; Tests passed — run LLM review
                                                              (if-not program-md
                                                                (do
                                                                  (reset! last-verification
                                                                          {:generation   generation
                                                                           :test-results test-counts
                                                                           :diff-stats   (:diff-stats diffs)
                                                                           :llm-verdict  {:approved false
                                                                                          :issues ["program.md not found — cannot review"]
                                                                                          :confidence :low
                                                                                          :summary "Skipped: program.md not found"}})
                                                                  (str base-result "\n\nLLM Review: SKIPPED (program.md not found)"))
                                                                (-> (llm-review-diff diff-text program-md)
                                                                    (.then (fn [verdict]
                                                                             (reset! last-verification
                                                                                     {:generation   generation
                                                                                      :test-results test-counts
                                                                                      :diff-stats   (:diff-stats diffs)
                                                                                      :llm-verdict  verdict})
                                                                             (str base-result
                                                                                  "\n\nLLM Review: "
                                                                                  (if (:approved verdict) "APPROVED" "REJECTED")
                                                                                  " (confidence: " (name (:confidence verdict)) ")"
                                                                                  "\nSummary: " (:summary verdict)
                                                                                  (when (seq (:issues verdict))
                                                                                    (str "\nIssues:\n"
                                                                                         (str/join "\n" (map #(str "  - " %) (:issues verdict)))))))))))))))))
                                    (.catch (fn [err]
                                              ;; Tests threw unexpectedly — still return to master
                                              (-> (return-to-master repo)
                                                  (.then (fn [_]
                                                           (str "Verification failed: unexpected error: "
                                                                (.-message err)))))))))))))))))

;; ---------------------------------------------------------------------------
;; Tool definitions and registry
;; ---------------------------------------------------------------------------

(def tool-definitions
  [{:name "spawn_lab"
    :description "Spawn a Lab container to execute a task. Blocks until the Lab finishes (done/failed) or times out (5 min). Returns the final status — no need to poll separately."
    :input_schema {:type "object"
                   :properties {:program_md {:type "string"
                                             :description "The program.md content: task spec, acceptance criteria, success conditions"}}
                   :required ["program_md"]}}
   {:name "promote_generation"
    :description "Promote a successful Lab generation: merge its branch into main, create a git tag, delete the lab branch."
    :input_schema {:type "object"
                   :properties {:generation {:type "integer" :description "Generation number to promote"}}
                   :required ["generation"]}}
   {:name "rollback_generation"
    :description "Rollback a failed Lab generation: discard its branch and mark as failed."
    :input_schema {:type "object"
                   :properties {:generation {:type "integer" :description "Generation number to rollback"}}
                   :required ["generation"]}}
   {:name "verify_generation"
    :description "Independently verify a Lab generation's work: show diff stat, checkout branch, run tests, always return to master. Reports changes made AND test results. Use after spawn_lab returns done, before promoting."
    :input_schema {:type "object"
                   :properties {:generation {:type "integer" :description "Generation number to verify"}
                                :repo_path {:type "string" :description "Path to the repo (default: current dir)"}}
                   :required ["generation"]}}])

(def registry
  {"spawn_lab"           spawn-lab
   "promote_generation"  promote-generation
   "rollback_generation" rollback-generation
   "verify_generation"   verify-generation})
