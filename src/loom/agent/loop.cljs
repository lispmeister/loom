(ns loom.agent.loop
  "Agentic loop: user message → Claude → tool dispatch → repeat.
   Manages conversation history and drives the tool-use cycle."
  (:require [loom.agent.claude :as claude]
            [loom.agent.dispatch :as dispatch]
            [loom.agent.tools :as tools]))

(def ^:private default-system-prompt
  "You are Loom, a coding agent built in ClojureScript.
You have access to tools for reading, writing, and editing files, and running shell commands.
You can also spawn Lab containers to test code modifications in isolation.

Workflow for self-modification:
1. Draft a program.md with the task spec and acceptance criteria
2. Use spawn_lab with the program.md to create a Lab container
3. Use check_lab_status to poll the Lab until it reports done or fails
4. If done, review the Lab's work (read files on the lab branch, run tests via bash)
5. Use promote_generation to merge successful changes, or rollback_generation to discard

Work step by step. Read files before editing them. Run tests to verify your changes.")

(def ^:private max-iterations
  "Maximum tool-use loop iterations per turn to prevent runaway."
  25)

(defn create-agent
  "Create an agent state map.
   Options:
     :api-key    — Anthropic API key (required)
     :model      — model ID (default: claude-sonnet-4-20250514)
     :system     — system prompt (default: built-in)
     :max-tokens — max tokens per response (default: 4096)"
  [{:keys [api-key model system max-tokens]
    :or {model "claude-sonnet-4-20250514"
         system default-system-prompt
         max-tokens 4096}}]
  {:api-key    api-key
   :model      model
   :system     system
   :max-tokens max-tokens
   :messages   []})

(defn- append-assistant-message
  "Append Claude's response as an assistant message."
  [agent response]
  (update agent :messages conj
          {:role "assistant" :content (:content response)}))

(defn- append-tool-results
  "Append tool result messages to conversation."
  [agent tool-result-messages]
  (update agent :messages into tool-result-messages))

(defn- call-claude
  "Send current conversation to Claude. Returns a promise of the response."
  [agent on-event]
  (claude/send-message
   {:api-key    (:api-key agent)
    :model      (:model agent)
    :messages   (:messages agent)
    :system     (:system agent)
    :tools      tools/tool-definitions
    :max-tokens (:max-tokens agent)
    :on-event   on-event}))

(defn- tool-use-loop
  "Inner loop: send to Claude, dispatch tools, repeat until end_turn or max iterations.
   Returns a promise resolving to {:agent <updated> :response <final-response>}."
  [agent on-event iteration]
  (-> (call-claude agent on-event)
      (.then
       (fn [response]
         (if (:error response)
           ;; API error — stop the loop
           (do (when on-event (on-event {:type :error :error response}))
               {:agent agent :response response})

           (let [agent' (append-assistant-message agent response)
                 tool-calls (claude/extract-tool-calls response)
                 text (claude/extract-text response)]

             ;; Emit text if present
             (when (and on-event (seq text))
               (on-event {:type :text :text text}))

             (if (or (empty? tool-calls)
                     (>= iteration max-iterations))
               ;; No tool calls or max iterations — done
               (do (when (and on-event (>= iteration max-iterations) (seq tool-calls))
                     (on-event {:type :warning :message "Max iterations reached, stopping"}))
                   {:agent agent' :response response})

               ;; Dispatch tools and loop
               (do (when on-event
                     (on-event {:type :tool-calls
                                :calls (mapv #(select-keys % [:name :id]) tool-calls)}))
                   (-> (dispatch/dispatch-all tool-calls)
                       (.then
                        (fn [results]
                          (when on-event
                            (doseq [r results]
                              (let [block (first (:content r))]
                                (on-event {:type    :tool-result
                                           :tool-id (:tool_use_id block)
                                           :error?  (:is_error block)}))))
                          (let [agent'' (append-tool-results agent' results)]
                            (tool-use-loop agent'' on-event (inc iteration))))))))))))))

(defn run-turn
  "Run one conversational turn: append user message, enter the tool-use loop,
   return updated agent + final response.

   on-event is an optional callback (fn [event-map]) for streaming progress:
     {:type :text :text \"...\"}
     {:type :tool-calls :calls [{:name \"...\" :id \"...\"}]}
     {:type :tool-result :tool-id \"...\" :error? bool}
     {:type :error :error {...}}
     {:type :warning :message \"...\"}

   Returns a promise resolving to {:agent <updated> :response <final-response>}."
  [agent user-message & {:keys [on-event]}]
  (let [agent' (update agent :messages conj
                       {:role "user" :content user-message})]
    (tool-use-loop agent' on-event 0)))
