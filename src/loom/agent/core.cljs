(ns loom.agent.core
  "Prime container entry point. Starts the HTTP server and runs the agentic loop."
  (:require [loom.agent.loop :as loop]
            [loom.agent.http :as agent-http]
            [loom.agent.cli :as cli]
            [loom.shared.http :as http]))

(defonce state
  (atom {:status           :idle
         :messages-count   0
         :tool-calls-count 0
         :start-time       (.now js/Date)
         :version          "0.1.0"}))

(defonce agent-state (atom nil))

(defn- on-event
  "Handle events from the agentic loop — emit to SSE and update stats."
  [event]
  (case (:type event)
    :text       (agent-http/emit-log "text" {:text (:text event)})
    :tool-calls (do (swap! state update :tool-calls-count + (count (:calls event)))
                    (agent-http/emit-log "tool-calls" {:calls (:calls event)}))
    :tool-result (agent-http/emit-log "tool-result" (select-keys event [:tool-id :error?]))
    :error      (agent-http/emit-log "error" (:error event))
    :warning    (agent-http/emit-log "warning" {:message (:message event)})
    nil))

(defn handle-chat-message
  "Process a user chat message through the agentic loop."
  [message]
  (swap! state assoc :status :working)
  (swap! state update :messages-count inc)
  (agent-http/emit-log "chat" {:message message})
  (-> (loop/run-turn @agent-state message :on-event on-event)
      (.then (fn [{:keys [agent response]}]
               (reset! agent-state agent)
               (swap! state assoc :status :idle)
               (let [text (or (some-> response :error :message)
                              (some->> (:content response)
                                       (filter #(= "text" (:type %)))
                                       (map :text)
                                       (apply str))
                              "")]
                 (agent-http/emit-log "response" {:text text})
                 text)))
      (.catch (fn [err]
                (swap! state assoc :status :idle)
                (let [msg (str "Agent error: " (.-message err))]
                  (agent-http/emit-log "error" {:message msg})
                  msg)))))

(defn- start-server
  "Start the HTTP server and agentic loop."
  []
  (let [api-key (.-ANTHROPIC_API_KEY (.-env js/process))
        model   (or (.-LOOM_MODEL (.-env js/process)) "claude-sonnet-4-20250514")
        port    (let [p (.-PORT (.-env js/process))]
                  (if p (js/parseInt p 10) 8401))]
    (if (nil? api-key)
      (do (println "Error: ANTHROPIC_API_KEY environment variable is required")
          (.exit js/process 1))
      (do
        (reset! agent-state
                (loop/create-agent {:api-key api-key :model model}))
        (-> (agent-http/start-prime-server state
                                           :port port
                                           :on-chat-fn handle-chat-message)
            (.then (fn [server]
                     (let [shutdown (fn []
                                      (println "\nPrime shutting down...")
                                      (-> (.close server (fn [_] nil))
                                          (.finally (fn [] (.exit js/process 0)))))]
                       (.on js/process "SIGINT" shutdown)
                       (.on js/process "SIGTERM" shutdown))
                     (println (str "Loom Prime listening on port "
                                   (http/server-port server)))
                     (println (str "Model: " model)))))))))

(defn main
  "Prime agent entry point. Dispatches CLI commands or starts HTTP server."
  []
  (when (= :serve (cli/dispatch))
    (start-server)))
