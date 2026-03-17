(ns loom.lab.worker
  "Lab container autonomous worker. Reads program.md, runs the agentic loop,
   exposes GET /status for Prime to poll, commits results on completion."
  (:require [loom.agent.loop :as loop]
            [loom.agent.tools :as tools]
            [loom.shared.http :as http]))

(def ^:private fs (js/require "node:fs"))
(def ^:private cp (js/require "node:child_process"))

;; ---------------------------------------------------------------------------
;; Status tracking
;; ---------------------------------------------------------------------------

(defonce state
  (atom {:status   "starting"
         :progress ""
         :error    nil}))

(defonce tool-stats (atom {}))

;; ---------------------------------------------------------------------------
;; Git helpers
;; ---------------------------------------------------------------------------

(defn- git-commit-all
  "Stage all changes and commit. Returns promise of:
   {:ok true :output ...}       — committed successfully
   {:nothing-to-commit true}    — no changes were staged
   {:error true :message ...}   — git error"
  [message]
  (js/Promise.
   (fn [resolve _]
     (.exec cp (str "git add -A && git commit -m " (js/JSON.stringify message))
            #js {:cwd "/workspace" :timeout 30000}
            (fn [err stdout stderr]
              (cond
                ;; Exit code 1 with "nothing to commit" means no changes
                (and err (= 1 (.-code err))
                     (re-find #"nothing to commit" (str stdout stderr)))
                (resolve {:nothing-to-commit true})

                ;; Other errors are real failures
                err
                (resolve {:error true :message (str stderr)})

                ;; Success
                :else
                (resolve {:ok true :output (str stdout)})))))))

;; ---------------------------------------------------------------------------
;; HTTP status endpoint
;; ---------------------------------------------------------------------------

(defn- status-handler [_req]
  (http/json-response 200 (assoc @state :tool-stats @tool-stats)))

(defn- start-status-server
  "Start HTTP server with GET /status. Returns promise of server."
  [port]
  (http/create-server
   {[:get "/status"] status-handler}
   :port port :host "0.0.0.0"))

;; ---------------------------------------------------------------------------
;; Agent event handler
;; ---------------------------------------------------------------------------

(defn- on-event
  "Track agent progress via events. Logs to stdout for container log inspection."
  [event]
  (case (:type event)
    :text       (do (println (str "[text] " (:text event)))
                    (swap! state assoc :progress (:text event)))
    :tool-calls (do (println (str "[tools] " (pr-str (mapv :name (:calls event)))))
                    (swap! state assoc :progress
                           (str "Using tools: "
                                (pr-str (mapv :name (:calls event)))))
                    (doseq [tc (:calls event)]
                      (swap! tool-stats update (:name tc)
                             (fn [s] (update (or s {:calls 0 :errors 0}) :calls inc)))))
    :tool-result (do (println (str "[result] tool=" (:tool-id event)
                                   " error?=" (:error? event)))
                     (when (:error? event)
                       (swap! tool-stats update (:tool-id event)
                              (fn [s] (update (or s {:calls 0 :errors 0}) :errors inc)))))
    :error      (do (println (str "[error] " (pr-str (:error event))))
                    (swap! state assoc :progress
                           (str "Error: " (pr-str (:error event)))))
    :warning    (println (str "[warning] " (:message event)))
    nil))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn main
  "Lab worker entry point. Reads ANTHROPIC_API_KEY and program.md from /workspace,
   starts a status HTTP server on PORT (default 8402), then runs the agentic loop
   to completion. On success, commits all changes and sets status to 'done'.
   On failure, sets status to 'failed' with an error message. Prime polls
   GET /status to detect completion."
  []
  (let [api-key (.-ANTHROPIC_API_KEY (.-env js/process))
        model   (or (.-LOOM_MODEL (.-env js/process)) "claude-haiku-4-5-20251001")
        port    (let [p (.-PORT (.-env js/process))]
                  (if p (js/parseInt p 10) 8402))]

    (when (nil? api-key)
      (println "Error: ANTHROPIC_API_KEY is required")
      (swap! state assoc :status "failed" :error "No API key")
      (.exit js/process 1))

    ;; Read program.md
    (let [program-md (try (.readFileSync fs "/workspace/program.md" "utf8")
                          (catch :default e
                            (println "Error reading program.md:" (.-message e))
                            (swap! state assoc :status "failed"
                                   :error (str "Cannot read program.md: " (.-message e)))
                            (.exit js/process 1)))
          ;; Create agent with only base tools (no self-modify or reflect for Labs)
          agent (loop/create-agent
                 {:api-key          api-key
                  :model            model
                  :tool-definitions tools/base-tool-definitions
                  :tool-registry    tools/base-registry
                  :system           "You are a Loom Lab worker. Execute the task in program.md.

IMPORTANT CONSTRAINTS:
- You have a LIMITED number of tool calls. Do NOT over-read. Read only the files
  you need to understand the change, then START WRITING immediately.
- Do NOT run npm test, npx shadow-cljs, or any build/compile commands.
  Prime will verify your work separately.
- Work in /workspace (a git repo on your lab branch).
- Changes are committed automatically when you finish.

WORKFLOW — follow this order:
1. Read program.md (already provided as your task).
2. Read at most 2-3 reference files to understand patterns.
3. Write or edit files to implement the task. This is your primary job.
4. When done, say so. Do not keep reading more files."
                  :max-tokens 4096})]

      ;; Start status server, then run the task
      (-> (start-status-server port)
          (.then (fn [server]
                   (println (str "Lab worker status server on port " (http/server-port server)))
                   (swap! state assoc :status "running" :progress "Starting task...")

                   ;; Run the agentic loop with program.md as the task
                   (println (str "Program.md loaded (" (count program-md) " chars), starting agent loop..."))
                   (-> (loop/run-turn agent program-md :on-event on-event)
                       (.then (fn [{:keys [response token-usage]}]
                                (println (str "[response] stop_reason=" (:stop_reason response)
                                              " content_types="
                                              (pr-str (mapv :type (:content response)))
                                              " tokens=" (pr-str token-usage)))
                                ;; Commit all changes
                                (-> (git-commit-all "Lab: completed task from program.md")
                                    (.then (fn [commit-result]
                                             (let [text (->> (:content response)
                                                             (filter #(= "text" (:type %)))
                                                             (map :text)
                                                             (apply str))]
                                               (cond
                                                 (:nothing-to-commit commit-result)
                                                 (do (println "Lab worker: no changes to commit — task failed")
                                                     (swap! state assoc
                                                            :status "failed"
                                                            :error "Agent completed but made no file changes"
                                                            :progress (or text "")
                                                            :token-usage token-usage))

                                                 (:error commit-result)
                                                 (do (println (str "Lab worker: git commit failed: " (:message commit-result)))
                                                     (swap! state assoc
                                                            :status "failed"
                                                            :error (str "Git commit failed: " (:message commit-result))
                                                            :progress (or text "")
                                                            :token-usage token-usage))

                                                 :else
                                                 (do (println "Lab worker: task completed, changes committed")
                                                     (swap! state assoc
                                                            :status "done"
                                                            :progress (or text "Task completed")
                                                            :token-usage token-usage)))))))))
                       (.catch (fn [err]
                                 (let [msg (str "Task failed: " (.-message err))]
                                   (println msg)
                                   (swap! state assoc :status "failed" :error msg)))))))))))
