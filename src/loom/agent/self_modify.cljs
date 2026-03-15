(ns loom.agent.self-modify
  "Self-modification tools for Prime: spawn Labs, poll status, promote/rollback."
  (:require [clojure.string :as str]
            [loom.shared.http-client :as client]))

(def ^:dynamic supervisor-url
  (or (some-> js/process .-env .-LOOM_SUPERVISOR_URL)
      "http://localhost:8400"))

;; ---------------------------------------------------------------------------
;; Internal polling
;; ---------------------------------------------------------------------------

(def ^:dynamic poll-interval-ms 5000)
(def ^:dynamic poll-timeout-ms  300000)  ;; 5 minutes

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
   POSTs to Supervisor /promote."
  [{:keys [generation]}]
  (-> (client/post-json (str supervisor-url "/promote")
                        {:generation generation})
      (.then (fn [result]
               (if (:error result)
                 (str "Error promoting generation " generation ": "
                      (or (:message result) (pr-str result)))
                 (str "Generation " generation " promoted successfully.\n"
                      "Status: " (:status result)))))))

(defn rollback-generation
  "Rollback a Lab generation: discard branch, mark as failed.
   POSTs to Supervisor /rollback."
  [{:keys [generation]}]
  (-> (client/post-json (str supervisor-url "/rollback")
                        {:generation generation})
      (.then (fn [result]
               (if (:error result)
                 (str "Error rolling back generation " generation ": "
                      (or (:message result) (pr-str result)))
                 (str "Generation " generation " rolled back.\n"
                      "Status: " (:status result)))))))

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

(defn verify-generation
  "Independently verify a Lab generation's work: get diff, checkout branch,
   run tests, always return to master, report results with change summary.
   Input: {:generation N :repo_path \"/path/to/repo\"}"
  [{:keys [generation repo_path]}]
  (let [branch (str "lab/gen-" generation)
        repo   (or repo_path ".")]
    ;; Step 1: Get diff stat from master (before checkout, so we don't leave repo dirty)
    (-> (git-exec repo "diff" "--stat" (str "master..." branch))
        (.then (fn [diff-stat-result]
                 ;; Step 2: Get abbreviated diff for review
                 (-> (git-exec repo "diff" (str "master..." branch))
                     (.then (fn [diff-result]
                              {:diff-stat (when (:ok diff-stat-result) (:output diff-stat-result))
                               :diff      (when (:ok diff-result) (:output diff-result))})))))
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
                                                          (str (:diff-stat diffs)
                                                               "\n"
                                                               (format-test-summary
                                                                generation (:ok test-result)
                                                                (:exit test-result) (:output test-result))
                                                               "\n\nDiff:\n"
                                                               (let [d (:diff diffs)]
                                                                 (if (> (count d) 5000)
                                                                   (str (subs d 0 5000) "\n... (truncated)")
                                                                   d))))))))
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
