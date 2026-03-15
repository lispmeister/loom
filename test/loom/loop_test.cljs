(ns loom.loop-test
  (:require [cljs.test :refer [deftest is async testing]]
            [loom.agent.loop :as loop]
            [loom.agent.claude :as claude]
            [loom.agent.tools :as tools]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:os" :as os]))

;; ---------------------------------------------------------------------------
;; Mock infrastructure
;; ---------------------------------------------------------------------------

(defn- make-tmpdir []
  (.mkdtempSync fs (.join node-path (.tmpdir os) "loom-loop-test-")))

(defn- cleanup [dir]
  (.rmSync fs dir #js {:recursive true :force true}))

;; Disable loop pacing for tests
(set! loop/loop-delay-ms 0)
;; Disable message trimming for existing tests (they check exact message counts)
(set! loop/max-context-messages 0)

(defn- mock-agent
  "Create an agent with a fake API key (tests override send-message)."
  []
  (loop/create-agent {:api-key "test-key"}))

(defn- text-response
  "Construct a mock Claude API text-only response."
  [text]
  {:id "msg_test"
   :type "message"
   :role "assistant"
   :content [{:type "text" :text text}]
   :model "claude-sonnet-4-20250514"
   :stop_reason "end_turn"
   :usage {:input_tokens 10 :output_tokens 5}})

(defn- tool-response
  "Construct a mock Claude API response with tool calls."
  [text tool-calls]
  {:id "msg_test"
   :type "message"
   :role "assistant"
   :content (into (if (seq text) [{:type "text" :text text}] [])
                  (map (fn [{:keys [id name input]}]
                         {:type "tool_use" :id id :name name :input input})
                       tool-calls))
   :model "claude-sonnet-4-20250514"
   :stop_reason "tool_use"
   :usage {:input_tokens 10 :output_tokens 15}})

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest test-text-only-turn
  (testing "A turn with no tool calls returns text immediately"
    (async done
           (let [agent (mock-agent)
                 original-send claude/send-message]
             ;; Monkey-patch send-message
             (set! claude/send-message
                   (fn [_opts]
                     (js/Promise.resolve (text-response "Hello from Claude!"))))
             (-> (loop/run-turn agent "Hi there")
                 (.then (fn [{:keys [agent response]}]
                          (is (= "Hello from Claude!"
                                 (claude/extract-text response)))
                          ;; Agent should have 2 messages: user + assistant
                          (is (= 2 (count (:messages agent))))
                          (is (= "user" (:role (first (:messages agent)))))
                          (is (= "assistant" (:role (second (:messages agent)))))))
                 (.then (fn [_]
                          (set! claude/send-message original-send)
                          (done)))
                 (.catch (fn [err]
                           (set! claude/send-message original-send)
                           (is false (str "Unexpected: " err))
                           (done))))))))

(deftest test-tool-use-then-text
  (testing "A turn with one tool call round then text response"
    (async done
           (let [agent (mock-agent)
                 dir (make-tmpdir)
                 fp (.join node-path dir "test.txt")
                 call-count (atom 0)
                 original-send claude/send-message]
             (.writeFileSync fs fp "file contents here")
             (set! claude/send-message
                   (fn [_opts]
                     (let [n (swap! call-count inc)]
                       (js/Promise.resolve
                        (if (= 1 n)
                          ;; First call: request to read a file
                          (tool-response "Let me read that."
                                         [{:id "toolu_01" :name "read_file"
                                           :input {:path fp}}])
                          ;; Second call: return text
                          (text-response "The file contains: file contents here"))))))
             (-> (loop/run-turn agent "What's in test.txt?")
                 (.then (fn [{:keys [agent response]}]
                          (is (= "The file contains: file contents here"
                                 (claude/extract-text response)))
                          ;; Messages: user, assistant(tool), tool_result, assistant(text)
                          (is (= 4 (count (:messages agent))))
                          (is (= 2 @call-count))))
                 (.then (fn [_]
                          (set! claude/send-message original-send)
                          (cleanup dir)
                          (done)))
                 (.catch (fn [err]
                           (set! claude/send-message original-send)
                           (cleanup dir)
                           (is false (str "Unexpected: " err))
                           (done))))))))

(deftest test-multi-tool-round
  (testing "Multiple tool calls in a single response are dispatched"
    (async done
           (let [agent (mock-agent)
                 dir (make-tmpdir)
                 call-count (atom 0)
                 original-send claude/send-message]
             (set! claude/send-message
                   (fn [_opts]
                     (let [n (swap! call-count inc)]
                       (js/Promise.resolve
                        (if (= 1 n)
                          ;; First call: write two files at once
                          (tool-response ""
                                         [{:id "toolu_w1" :name "write_file"
                                           :input {:path (.join node-path dir "a.txt")
                                                   :content "aaa"}}
                                          {:id "toolu_w2" :name "write_file"
                                           :input {:path (.join node-path dir "b.txt")
                                                   :content "bbb"}}])
                          ;; Second call: done
                          (text-response "Wrote both files."))))))
             (-> (loop/run-turn agent "Create two files")
                 (.then (fn [{:keys [agent response]}]
                          (is (= "Wrote both files." (claude/extract-text response)))
                          ;; user, assistant(2 tools), 2x tool_result, assistant(text)
                          (is (= 5 (count (:messages agent))))
                          ;; Verify files exist
                          (is (= "aaa" (.readFileSync fs (.join node-path dir "a.txt") "utf8")))
                          (is (= "bbb" (.readFileSync fs (.join node-path dir "b.txt") "utf8")))))
                 (.then (fn [_]
                          (set! claude/send-message original-send)
                          (cleanup dir)
                          (done)))
                 (.catch (fn [err]
                           (set! claude/send-message original-send)
                           (cleanup dir)
                           (is false (str "Unexpected: " err))
                           (done))))))))

(deftest test-on-event-callbacks
  (testing "on-event receives expected event types during a tool-use turn"
    (async done
           (let [agent (mock-agent)
                 events (atom [])
                 call-count (atom 0)
                 original-send claude/send-message]
             (set! claude/send-message
                   (fn [_opts]
                     (let [n (swap! call-count inc)]
                       (js/Promise.resolve
                        (if (= 1 n)
                          (tool-response "Thinking..."
                                         [{:id "toolu_e1" :name "bash"
                                           :input {:command "echo hi"}}])
                          (text-response "Done."))))))
             (-> (loop/run-turn agent "Run echo"
                                :on-event (fn [e] (swap! events conj e)))
                 (.then (fn [_]
                          ;; Should have: text("Thinking..."), tool-calls, tool-result, text("Done.")
                          (let [types (mapv :type @events)]
                            (is (some #{:text} types))
                            (is (some #{:tool-calls} types))
                            (is (some #{:tool-result} types)))))
                 (.then (fn [_]
                          (set! claude/send-message original-send)
                          (done)))
                 (.catch (fn [err]
                           (set! claude/send-message original-send)
                           (is false (str "Unexpected: " err))
                           (done))))))))

(deftest test-api-error-stops-loop
  (testing "API error stops the loop and returns the error"
    (async done
           (let [agent (mock-agent)
                 original-send claude/send-message]
             (set! claude/send-message
                   (fn [_opts]
                     (js/Promise.resolve
                      {:error true :status 401 :body "unauthorized"})))
             (-> (loop/run-turn agent "Hello")
                 (.then (fn [{:keys [response]}]
                          (is (true? (:error response)))
                          (is (= 401 (:status response)))))
                 (.then (fn [_]
                          (set! claude/send-message original-send)
                          (done)))
                 (.catch (fn [err]
                           (set! claude/send-message original-send)
                           (is false (str "Unexpected: " err))
                           (done))))))))

(deftest test-conversation-continuity
  (testing "Multiple turns maintain conversation history"
    (async done
           (let [agent (mock-agent)
                 call-count (atom 0)
                 original-send claude/send-message]
             (set! claude/send-message
                   (fn [opts]
                     (swap! call-count inc)
                     (js/Promise.resolve
                      (text-response (str "Response " @call-count)))))
             (-> (loop/run-turn agent "First message")
                 (.then (fn [{:keys [agent]}]
                          (is (= 2 (count (:messages agent))))
                          (loop/run-turn agent "Second message")))
                 (.then (fn [{:keys [agent]}]
                          ;; Should have: user1, assistant1, user2, assistant2
                          (is (= 4 (count (:messages agent))))
                          (is (= "First message"
                                 (:content (nth (:messages agent) 0))))
                          (is (= "Second message"
                                 (:content (nth (:messages agent) 2))))))
                 (.then (fn [_]
                          (set! claude/send-message original-send)
                          (done)))
                 (.catch (fn [err]
                           (set! claude/send-message original-send)
                           (is false (str "Unexpected: " err))
                           (done))))))))

;; ---------------------------------------------------------------------------
;; Message trimming tests (synchronous — no API calls needed)
;; ---------------------------------------------------------------------------

(deftest test-trim-messages-no-op-when-disabled
  (testing "Trimming is a no-op when max-context-messages is 0"
    (binding [loop/max-context-messages 0]
      (let [msgs (vec (for [i (range 30)]
                        {:role (if (even? i) "user" "assistant")
                         :content (str "msg-" i)}))]
        (is (= 30 (count (#'loop/trim-messages msgs))))))))

(deftest test-trim-messages-keeps-within-limit
  (testing "Trimming keeps first message + last N when over limit"
    (binding [loop/max-context-messages 4]
      (let [msgs [{:role "user" :content "task"}
                  {:role "assistant" :content "resp-1"}
                  {:role "user" :content "msg-2"}
                  {:role "assistant" :content "resp-2"}
                  {:role "user" :content "msg-3"}
                  {:role "assistant" :content "resp-3"}
                  {:role "user" :content "msg-4"}
                  {:role "assistant" :content "resp-4"}]
            trimmed (#'loop/trim-messages msgs)]
        ;; First msg (task) + summary + last 4 = 6
        (is (= 6 (count trimmed)))
        ;; First is the original task
        (is (= "task" (:content (first trimmed))))
        ;; Second is the summary marker
        (is (string? (:content (second trimmed))))
        (is (re-find #"trimmed" (:content (second trimmed))))
        ;; Last message is preserved
        (is (= "resp-4" (:content (last trimmed))))))))

(deftest test-trim-messages-no-op-when-under-limit
  (testing "Trimming is a no-op when messages are under the limit"
    (binding [loop/max-context-messages 10]
      (let [msgs [{:role "user" :content "task"}
                  {:role "assistant" :content "resp"}]]
        (is (= 2 (count (#'loop/trim-messages msgs))))))))

(deftest test-trim-truncates-tool-results
  (testing "Tool result content is truncated to 2000 chars"
    (binding [loop/max-context-messages 0]
      (let [big-content (apply str (repeat 3000 "x"))
            msgs [{:role "user"
                   :content [{:type "tool_result"
                              :tool_use_id "t1"
                              :content big-content}]}]
            trimmed (#'loop/trim-messages msgs)
            block (first (:content (first trimmed)))]
        (is (< (count (:content block)) 2100))
        (is (re-find #"truncated" (:content block)))))))
