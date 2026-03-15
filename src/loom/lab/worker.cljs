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

;; ---------------------------------------------------------------------------
;; Git helpers
;; ---------------------------------------------------------------------------

(defn- git-commit-all
  "Stage all changes and commit. Returns promise of {:ok true} or {:error ...}."
  [message]
  (js/Promise.
   (fn [resolve _]
     (.exec cp (str "git add -A && git commit -m " (js/JSON.stringify message))
            #js {:cwd "/workspace" :timeout 30000}
            (fn [err stdout stderr]
              (if (and err (not= 1 (.-code err)))
                (resolve {:error true :message (str stderr)})
                (resolve {:ok true :output (str stdout)})))))))

;; ---------------------------------------------------------------------------
;; HTTP status endpoint
;; ---------------------------------------------------------------------------

(defn- status-handler [_req]
  (http/json-response 200 @state))

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
                                (pr-str (mapv :name (:calls event))))))
    :tool-result (println (str "[result] tool=" (:tool-id event)
                               " error?=" (:error? event)))
    :error      (do (println (str "[error] " (pr-str (:error event))))
                    (swap! state assoc :progress
                           (str "Error: " (pr-str (:error event)))))
    :warning    (println (str "[warning] " (:message event)))
    nil))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn main []
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
          ;; Create agent with only base tools (no self-modify for Labs)
          agent (loop/create-agent
                 {:api-key   api-key
                  :model     model
                  :system    "You are a Loom Lab worker. You have been given a task in program.md.
Execute the task autonomously using the tools available to you.
Read files before editing. Make minimal, focused changes.
Do NOT run npm test, npx shadow-cljs, or any compilation commands — the container
does not have the build toolchain. Prime will verify your work host-side.
Work in /workspace which is a git repo on your lab branch.
When done, ensure all changes are saved — they will be committed automatically."
                  :max-tokens 4096})]

      ;; Start status server, then run the task
      (-> (start-status-server port)
          (.then (fn [server]
                   (println (str "Lab worker status server on port " (http/server-port server)))
                   (swap! state assoc :status "running" :progress "Starting task...")

                   ;; Run the agentic loop with program.md as the task
                   (println (str "Program.md loaded (" (count program-md) " chars), starting agent loop..."))
                   (-> (loop/run-turn agent program-md :on-event on-event)
                       (.then (fn [{:keys [response]}]
                                (println (str "[response] stop_reason=" (:stop_reason response)
                                              " content_types="
                                              (pr-str (mapv :type (:content response)))))
                                ;; Commit all changes
                                (-> (git-commit-all "Lab: completed task from program.md")
                                    (.then (fn [_]
                                             (let [text (->> (:content response)
                                                             (filter #(= "text" (:type %)))
                                                             (map :text)
                                                             (apply str))]
                                               (swap! state assoc
                                                      :status "done"
                                                      :progress (or text "Task completed"))
                                               (println "Lab worker: task completed")))))))
                       (.catch (fn [err]
                                 (let [msg (str "Task failed: " (.-message err))]
                                   (println msg)
                                   (swap! state assoc :status "failed" :error msg)))))))))))
