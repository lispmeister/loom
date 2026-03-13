(ns loom.claude-test
  (:require [cljs.test :refer [deftest is testing]]
            [loom.agent.claude :as claude]))

;; ---------------------------------------------------------------------------
;; Mock response data
;; ---------------------------------------------------------------------------

(def mock-text-response
  {:id "msg_01XYZ"
   :type "message"
   :role "assistant"
   :content [{:type "text" :text "Hello, "}
             {:type "text" :text "world!"}]
   :model "claude-sonnet-4-20250514"
   :stop_reason "end_turn"
   :usage {:input_tokens 10 :output_tokens 5}})

(def mock-tool-response
  {:id "msg_02ABC"
   :type "message"
   :role "assistant"
   :content [{:type "text" :text "Let me read that file."}
             {:type "tool_use"
              :id "toolu_01AAA"
              :name "read-file"
              :input {:path "src/core.cljs"}}
             {:type "tool_use"
              :id "toolu_02BBB"
              :name "list-dir"
              :input {:path "src/"}}]
   :model "claude-sonnet-4-20250514"
   :stop_reason "tool_use"
   :usage {:input_tokens 20 :output_tokens 15}})

(def mock-empty-response
  {:id "msg_03DEF"
   :type "message"
   :role "assistant"
   :content []
   :model "claude-sonnet-4-20250514"
   :stop_reason "end_turn"
   :usage {:input_tokens 5 :output_tokens 0}})

(def mock-error-response
  {:error true
   :status 401
   :body "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"})

;; ---------------------------------------------------------------------------
;; extract-tool-calls tests
;; ---------------------------------------------------------------------------

(deftest extract-tool-calls-with-tools
  (testing "extracts tool_use blocks from response with multiple tool calls"
    (let [tools (claude/extract-tool-calls mock-tool-response)]
      (is (= 2 (count tools)))
      (is (= {:id "toolu_01AAA"
              :name "read-file"
              :input {:path "src/core.cljs"}}
             (first tools)))
      (is (= {:id "toolu_02BBB"
              :name "list-dir"
              :input {:path "src/"}}
             (second tools))))))

(deftest extract-tool-calls-text-only
  (testing "returns [] for text-only response"
    (is (= [] (claude/extract-tool-calls mock-text-response)))))

(deftest extract-tool-calls-empty-content
  (testing "returns [] for empty content"
    (is (= [] (claude/extract-tool-calls mock-empty-response)))))

;; ---------------------------------------------------------------------------
;; extract-text tests
;; ---------------------------------------------------------------------------

(deftest extract-text-concatenates
  (testing "concatenates text from multiple text blocks"
    (is (= "Hello, world!" (claude/extract-text mock-text-response)))))

(deftest extract-text-with-tool-response
  (testing "extracts only text blocks, ignoring tool_use blocks"
    (is (= "Let me read that file." (claude/extract-text mock-tool-response)))))

(deftest extract-text-empty
  (testing "returns empty string for empty content"
    (is (= "" (claude/extract-text mock-empty-response)))))

;; ---------------------------------------------------------------------------
;; tool-result-message tests
;; ---------------------------------------------------------------------------

(deftest tool-result-message-success
  (testing "constructs correct tool_result message for success"
    (let [msg (claude/tool-result-message "toolu_01AAA" "file contents here")]
      (is (= "user" (:role msg)))
      (is (= 1 (count (:content msg))))
      (let [block (first (:content msg))]
        (is (= "tool_result" (:type block)))
        (is (= "toolu_01AAA" (:tool_use_id block)))
        (is (= "file contents here" (:content block)))
        (is (= false (:is_error block)))))))

(deftest tool-result-message-error
  (testing "constructs correct tool_result message for error"
    (let [msg (claude/tool-result-message "toolu_02BBB" "File not found" :is-error true)]
      (is (= "user" (:role msg)))
      (let [block (first (:content msg))]
        (is (= "tool_result" (:type block)))
        (is (= "toolu_02BBB" (:tool_use_id block)))
        (is (= "File not found" (:content block)))
        (is (= true (:is_error block)))))))

;; ---------------------------------------------------------------------------
;; Error response handling
;; ---------------------------------------------------------------------------

(deftest error-response-shape
  (testing "error responses have :error true and :status"
    (is (true? (:error mock-error-response)))
    (is (= 401 (:status mock-error-response)))
    (is (string? (:body mock-error-response)))))
