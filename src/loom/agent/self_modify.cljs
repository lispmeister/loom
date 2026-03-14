(ns loom.agent.self-modify
  "Self-modification tools for Prime: spawn Labs, poll status, promote/rollback."
  (:require [clojure.string :as str]
            [loom.shared.http-client :as client]))

(def ^:dynamic supervisor-url
  (or (some-> js/process .-env .-LOOM_SUPERVISOR_URL)
      "http://localhost:8400"))

;; ---------------------------------------------------------------------------
;; Tool implementations
;; ---------------------------------------------------------------------------

(defn spawn-lab
  "Spawn a Lab container with a program.md task spec.
   POSTs to Supervisor /spawn, returns generation info as string."
  [{:keys [program_md]}]
  (-> (client/post-json (str supervisor-url "/spawn")
                        {:program_md program_md})
      (.then (fn [result]
               (if (:error result)
                 (str "Error spawning Lab: " (:message result))
                 (str "Lab spawned successfully.\n"
                      "Generation: " (:generation result) "\n"
                      "Branch: " (:branch result) "\n"
                      "Container: " (:container-name result) "\n"
                      "Host port: " (:host-port result) "\n"
                      "Status: " (:status result)))))))

(defn- retry-get
  "GET with connect-retry. Retries up to max-retries times with delay-ms between."
  [url max-retries delay-ms attempt]
  (-> (client/get-json url :timeout 5000)
      (.then (fn [result]
               (if (and (:error result) (< attempt max-retries))
                 (js/Promise.
                  (fn [resolve _]
                    (js/setTimeout
                     (fn [] (resolve (retry-get url max-retries delay-ms (inc attempt))))
                     delay-ms)))
                 result)))))

(defn check-lab-status
  "Check Lab container status. Retries on connection refused (up to 5 times, 2s apart).
   Input: {:host \"lab-gen-1\" :port 8402} or {:url \"http://...\"}"
  [{:keys [host port url]}]
  (let [target-url (or url (str "http://" host ":" (or port 8402) "/status"))]
    (-> (retry-get target-url 5 2000 0)
        (.then (fn [result]
                 (if (:error result)
                   (str "Error checking Lab status: " (:message result))
                   (str "Lab status:\n"
                        (js/JSON.stringify (clj->js result) nil 2))))))))

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

(defn verify-generation
  "Independently verify a Lab generation's work: checkout its branch,
   run tests, report results, return to master.
   Input: {:generation N :repo_path \"/path/to/repo\"}"
  [{:keys [generation repo_path]}]
  (let [branch (str "lab/gen-" generation)
        repo   (or repo_path ".")
        cp     (js/require "node:child_process")]
    (-> (js/Promise.
         (fn [resolve _]
           (.execFile cp "git" #js ["checkout" branch] #js {:cwd repo}
                      (fn [err _stdout stderr]
                        (if err
                          (resolve {:error true :step "checkout"
                                    :message (str "Failed to checkout " branch ": " stderr)})
                          (resolve {:ok true}))))))
        (.then (fn [result]
                 (if (:error result)
                   result
                   (js/Promise.
                    (fn [resolve _]
                      (.exec cp "npm test 2>&1 && node out/test.js 2>&1"
                             #js {:cwd repo :timeout 120000 :maxBuffer (* 10 1024 1024)}
                             (fn [err stdout _stderr]
                               (resolve {:ok     (nil? err)
                                         :step   "tests"
                                         :output (str stdout)
                                         :exit   (if err (or (.-code err) 1) 0)}))))))))
        (.then (fn [test-result]
                 ;; Always return to master, regardless of test outcome
                 (-> (js/Promise.
                      (fn [resolve _]
                        (.execFile cp "git" #js ["checkout" "master"] #js {:cwd repo}
                                   (fn [_err _stdout _stderr]
                                     (resolve test-result))))))))
        (.then (fn [result]
                 (if (:error result)
                   (str "Verification failed at " (:step result) ": " (:message result))
                   (let [passed? (:ok result)
                         output  (:output result)
                         ;; Extract summary line (last few lines with test counts)
                         lines   (.split output "\n")
                         summary (->> lines
                                      (filter #(re-find #"Ran \d+ tests|failures|errors" %))
                                      (str/join "\n"))]
                     (str "Verification of gen-" generation ":\n"
                          "Result: " (if passed? "PASS" "FAIL") "\n"
                          "Exit code: " (:exit result) "\n"
                          (when (seq summary) (str "Summary:\n" summary "\n"))
                          (when-not passed?
                            (str "\nFull output (last 50 lines):\n"
                                 (->> lines (take-last 50) (str/join "\n"))))))))))))

;; ---------------------------------------------------------------------------
;; Tool definitions and registry (for merging into agent tools)
;; ---------------------------------------------------------------------------

(def tool-definitions
  [{:name "spawn_lab"
    :description "Spawn a Lab container to execute a task defined in program_md. The Lab runs autonomously and commits results to its branch."
    :input_schema {:type "object"
                   :properties {:program_md {:type "string"
                                             :description "The program.md content: task spec, acceptance criteria, success conditions"}}
                   :required ["program_md"]}}
   {:name "check_lab_status"
    :description "Check the status of a running Lab container. Retries automatically if the Lab isn't ready yet."
    :input_schema {:type "object"
                   :properties {:host {:type "string" :description "Lab container hostname (e.g. lab-gen-1)"}
                                :port {:type "integer" :description "Lab container port (default 8402)"}
                                :url {:type "string" :description "Full URL to Lab /status (alternative to host+port)"}}
                   :required []}}
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
    :description "Independently verify a Lab generation's work: checkout its branch, compile and run all tests, report pass/fail, return to master. Use this after a Lab reports done, before promoting."
    :input_schema {:type "object"
                   :properties {:generation {:type "integer" :description "Generation number to verify"}
                                :repo_path {:type "string" :description "Path to the repo (default: current dir)"}}
                   :required ["generation"]}}])

(def registry
  {"spawn_lab"           spawn-lab
   "check_lab_status"    check-lab-status
   "promote_generation"  promote-generation
   "rollback_generation" rollback-generation
   "verify_generation"   verify-generation})
