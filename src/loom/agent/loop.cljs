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
2. Use spawn_lab with the program.md — it blocks until the Lab finishes (done/failed/timeout)
3. If done, use verify_generation to independently run tests on the Lab's branch
4. If tests pass, use promote_generation to merge. If tests fail, use rollback_generation to discard.
5. On failure or timeout, retry: call spawn_lab again with the same program.md (up to 2 retries)

Work step by step. Read files before editing them. Run tests to verify your changes.")

(def ^:private max-iterations
  "Maximum tool-use loop iterations per turn to prevent runaway."
  25)

(def ^:dynamic max-context-messages
  "Maximum number of messages to keep in conversation history.
   Older messages are replaced with a summary to reduce token usage.
   Set to 0 to disable trimming. Override via LOOM_MAX_CONTEXT env var."
  (let [env-val (some-> js/process .-env .-LOOM_MAX_CONTEXT)]
    (if env-val (js/parseInt env-val 10) 20)))

(def ^:dynamic loop-delay-ms
  "Delay in ms between agent loop iterations to avoid hitting API rate limits.
   Set to 0 to disable. Override via LOOM_LOOP_DELAY_MS env var."
  (let [env-val (some-> js/process .-env .-LOOM_LOOP_DELAY_MS)]
    (if env-val (js/parseInt env-val 10) 2000)))

(defn- delay-ms
  "Return a promise that resolves after ms milliseconds. Resolves immediately if ms <= 0."
  [ms]
  (if (pos? ms)
    (js/Promise. (fn [resolve _] (js/setTimeout resolve ms)))
    (js/Promise.resolve nil)))

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

(defn- truncate-tool-content
  "Truncate a tool_result content string if it exceeds max-chars."
  [content max-chars]
  (if (and (string? content) (> (count content) max-chars))
    (str (subs content 0 max-chars) "\n... (truncated, " (count content) " chars total)")
    content))

(defn- trim-messages
  "Trim conversation history to keep token usage bounded.
   Strategy: keep the first user message (the task), then keep the last
   max-context-messages messages. Older messages in between are dropped
   and replaced with a summary marker. Tool result content is truncated
   to 2000 chars max."
  [messages]
  (let [max-content 2000
        ;; Truncate large tool results in all messages
        truncated (mapv (fn [msg]
                          (if (and (= "user" (:role msg)) (vector? (:content msg)))
                            (update msg :content
                                    (fn [blocks]
                                      (mapv (fn [block]
                                              (if (= "tool_result" (:type block))
                                                (update block :content truncate-tool-content max-content)
                                                block))
                                            blocks)))
                            msg))
                        messages)]
    (if (or (<= max-context-messages 0)
            (<= (count truncated) (inc max-context-messages))) ;; +1 for first message
      truncated
      ;; Keep first message + last N messages, drop middle
      (let [first-msg  (first truncated)
            kept       (take-last max-context-messages (rest truncated))
            n-dropped  (- (count truncated) 1 (count kept))
            summary    {:role "user"
                        :content (str "[" n-dropped " earlier messages trimmed to save context]")}]
        (into [first-msg summary] kept)))))

(defn- call-claude
  "Send current conversation to Claude. Returns a promise of the response.
   Trims conversation history to max-context-messages before sending."
  [agent on-event]
  (claude/send-message
   {:api-key    (:api-key agent)
    :model      (:model agent)
    :messages   (trim-messages (:messages agent))
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
                            ;; Pace API calls to stay under rate limits
                            (-> (delay-ms loop-delay-ms)
                                (.then (fn [_] (tool-use-loop agent'' on-event (inc iteration)))))))))))))))))

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
