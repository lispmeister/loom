(ns loom.reflect-test
  "Tests for the reflect step: prompt building and error handling."
  (:require [cljs.test :refer [deftest async is testing]]
            [loom.agent.reflect :as reflect]))

;; ---------------------------------------------------------------------------
;; build-reflect-prompt tests
;; ---------------------------------------------------------------------------

(deftest build-reflect-prompt-with-context-test
  (testing "full context produces prompt with priorities and generation entries"
    (let [context {:priorities  "## 1. Add tests\nWrite unit tests for core module."
                   :generations [{:generation    5
                                  :outcome       :done
                                  :program-md    "# Task\nAdd unit tests\n## Requirements\n- Test core\n## Acceptance Criteria\n- Tests pass"
                                  :report        {:token-usage  {:input 5000 :output 1000}
                                                  :test-results {:tests-run 10 :assertions 20 :failures 0 :errors 0 :passed? true}
                                                  :llm-verdict  {:approved true :confidence :high :summary "All good"}}
                                  :fitness-score 114.0}
                                 {:generation    6
                                  :outcome       :failed
                                  :program-md    "# Task\nRefactor module\n## Requirements\n- Simplify"
                                  :report        {:token-usage  {:input 3000 :output 500}
                                                  :test-results {:tests-run 8 :assertions 15 :failures 2 :errors 0 :passed? false}}
                                  :fitness-score 91.5}]
                   :latest-gen  6}
          prompt (reflect/build-reflect-prompt context)
          system (:system prompt)
          user-msg (:content (first (:messages prompt)))]
      ;; System prompt has key instructions
      (is (string? system))
      (is (re-find #"program\.md" system))
      (is (re-find #"Do NOT repeat" system))
      (is (re-find #"small and focused" system))
      ;; User message has priorities
      (is (re-find #"Add tests" user-msg))
      ;; User message has generation entries
      (is (re-find #"Generation 5" user-msg))
      (is (re-find #"Generation 6" user-msg))
      (is (re-find #"done" user-msg))
      (is (re-find #"failed" user-msg))
      ;; User message has fitness score
      (is (re-find #"114" user-msg))
      ;; User message has test results
      (is (re-find #"10 run" user-msg))
      ;; User message has LLM verdict
      (is (re-find #"APPROVED" user-msg))
      ;; User message has current state
      (is (re-find #"Latest generation: 6" user-msg))
      ;; Ends with instruction
      (is (re-find #"Respond with ONLY" user-msg)))))

(deftest build-reflect-prompt-no-generations-test
  (testing "empty history triggers bootstrap case"
    (let [context {:priorities  "## 1. Something"
                   :generations []
                   :latest-gen  nil}
          prompt (reflect/build-reflect-prompt context)
          user-msg (:content (first (:messages prompt)))]
      (is (re-find #"No previous generations" user-msg))
      (is (re-find #"first run" user-msg))
      (is (re-find #"No generations have run yet" user-msg)))))

(deftest build-reflect-prompt-no-priorities-test
  (testing "nil priorities shows fallback text"
    (let [context {:priorities  nil
                   :generations [{:generation 1 :outcome :done
                                  :program-md "# Task\nDo stuff" :report nil :fitness-score nil}]
                   :latest-gen  1}
          prompt (reflect/build-reflect-prompt context)
          user-msg (:content (first (:messages prompt)))]
      (is (re-find #"No priorities file found" user-msg))
      (is (re-find #"stability improvements" user-msg)))))

(deftest build-reflect-prompt-failed-generation-test
  (testing "failed generation info appears in prompt for avoidance"
    (let [context {:priorities  "## 1. Fix bug"
                   :generations [{:generation    3
                                  :outcome       :failed
                                  :program-md    "# Task\nFix the parser bug\n## Requirements\n- Patch parse.cljs"
                                  :report        {:token-usage {:input 2000 :output 400}
                                                  :test-results {:tests-run 5 :assertions 10 :failures 3 :errors 0 :passed? false}}
                                  :fitness-score 55.6}]
                   :latest-gen  3}
          prompt (reflect/build-reflect-prompt context)
          user-msg (:content (first (:messages prompt)))]
      ;; Failed gen info present so LLM can avoid repeating
      (is (re-find #"Generation 3" user-msg))
      (is (re-find #"failed" user-msg))
      (is (re-find #"FAILED" user-msg))
      (is (re-find #"3 failures" user-msg))
      (is (re-find #"Fix the parser bug" user-msg)))))

(deftest build-reflect-prompt-missing-program-md-test
  (testing "missing program.md for a generation shows placeholder"
    (let [context {:priorities  nil
                   :generations [{:generation 2 :outcome :done
                                  :program-md nil :report nil :fitness-score nil}]
                   :latest-gen  2}
          prompt (reflect/build-reflect-prompt context)
          user-msg (:content (first (:messages prompt)))]
      (is (re-find #"program\.md.*not found" user-msg)))))

;; ---------------------------------------------------------------------------
;; reflect-and-propose error handling
;; ---------------------------------------------------------------------------

(deftest reflect-and-propose-no-api-key-test
  (testing "returns error string when no API key is set"
    (async done
           (let [original-key (.. js/process -env -ANTHROPIC_API_KEY)]
             (js-delete (.-env js/process) "ANTHROPIC_API_KEY")
             (-> (reflect/reflect-and-propose {:repo_path "."})
                 (.then (fn [result]
                          (is (string? result))
                          (is (re-find #"No ANTHROPIC_API_KEY" result))
                          (when original-key
                            (aset (.-env js/process) "ANTHROPIC_API_KEY" original-key))
                          (done)))
                 (.catch (fn [err]
                           (when original-key
                             (aset (.-env js/process) "ANTHROPIC_API_KEY" original-key))
                           (is false (str "Should not reject: " err))
                           (done))))))))
