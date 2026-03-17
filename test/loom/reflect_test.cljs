(ns loom.reflect-test
  "Tests for the reflect step: prompt building and error handling."
  (:require [cljs.test :refer [deftest async is testing]]
            [loom.agent.reflect :as reflect]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))

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

(deftest build-reflect-prompt-with-lessons-test
  (testing "lessons in context appear in the prompt as a Recent Lessons section"
    (let [context {:priorities  "## 1. Fix bug"
                   :generations []
                   :latest-gen  nil
                   :lessons     [{:generation        1
                                  :outcome           "promoted"
                                  :task-summary      "Add caching layer"
                                  :cycle-duration-ms 5000
                                  :what-worked       "Task completed successfully, all tests passed, LLM review approved"
                                  :what-didnt        nil}
                                 {:generation        2
                                  :outcome           "rolled-back"
                                  :task-summary      "Refactor auth module"
                                  :cycle-duration-ms 3000
                                  :what-worked       "Lab completed work but tests regressed"
                                  :what-didnt        "Test failures: 3 failures, 1 errors"}]}
          prompt (reflect/build-reflect-prompt context)
          user-msg (:content (first (:messages prompt)))]
      (is (re-find #"Recent Lessons" user-msg))
      (is (re-find #"Add caching layer" user-msg))
      (is (re-find #"promoted" user-msg))
      (is (re-find #"Test failures: 3 failures, 1 errors" user-msg))
      (is (re-find #"Task completed successfully" user-msg)))))

(deftest build-reflect-prompt-no-lessons-test
  (testing "nil or absent lessons key does not add Recent Lessons section"
    (let [context {:priorities  "## 1. Fix bug"
                   :generations []
                   :latest-gen  nil}
          prompt (reflect/build-reflect-prompt context)
          user-msg (:content (first (:messages prompt)))]
      (is (not (re-find #"Recent Lessons" user-msg))))))

;; ---------------------------------------------------------------------------
;; read-lessons tests
;; ---------------------------------------------------------------------------

(deftest read-lessons-roundtrip-test
  (testing "read-lessons reads from lessons.jsonl and returns last N entries"
    (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "loom-reflect-test-"))
          tmp (.join path dir "tmp")]
      (.mkdirSync fs tmp #js {:recursive true})
      (let [filepath (.join path tmp "lessons.jsonl")]
        ;; Write 3 lessons
        (.writeFileSync fs filepath
                        (str (js/JSON.stringify #js {:generation 1 :outcome "promoted"}) "\n"
                             (js/JSON.stringify #js {:generation 2 :outcome "rolled-back"}) "\n"
                             (js/JSON.stringify #js {:generation 3 :outcome "promoted"}) "\n")
                        "utf8"))
      (let [lessons (reflect/read-lessons dir 2)]
        (is (= 2 (count lessons)))
        ;; Should be last 2: gen 2 and gen 3
        (is (= 2 (:generation (first lessons))))
        (is (= 3 (:generation (second lessons)))))
      (.rmSync fs dir #js {:recursive true :force true}))))

(deftest read-lessons-missing-file-test
  (testing "read-lessons returns empty seq when file doesn't exist"
    (let [lessons (reflect/read-lessons "/nonexistent/path" 5)]
      (is (empty? lessons)))))

;; ---------------------------------------------------------------------------
;; gather-codebase-summary tests
;; ---------------------------------------------------------------------------

(deftest gather-codebase-summary-returns-expected-keys-test
  (testing "gather-codebase-summary returns a map with all expected keys when pointed at the real repo root"
    (let [repo-root (-> js/process .-env .-PWD)
          summary   (reflect/gather-codebase-summary repo-root)]
      ;; Must have all four keys
      (is (map? summary))
      (is (contains? summary :fitness-fn))
      (is (contains? summary :tool-definitions))
      (is (contains? summary :reflect-prompt))
      (is (contains? summary :loop-system-prompt))
      ;; Each value should be a non-blank string (files exist in the real repo)
      (is (string? (:fitness-fn summary)))
      (is (seq (:fitness-fn summary)))
      (is (string? (:tool-definitions summary)))
      (is (seq (:tool-definitions summary)))
      (is (string? (:reflect-prompt summary)))
      (is (seq (:reflect-prompt summary)))
      (is (string? (:loop-system-prompt summary)))
      (is (seq (:loop-system-prompt summary)))
      ;; Spot-check content: fitness.cljs should mention fitness-score
      (is (re-find #"fitness-score" (:fitness-fn summary)))
      ;; tools.cljs should mention tool-definitions
      (is (re-find #"tool-definitions" (:tool-definitions summary)))
      ;; reflect.cljs should mention build-reflect-prompt
      (is (re-find #"build-reflect-prompt" (:reflect-prompt summary)))
      ;; loop.cljs should mention system-prompt
      (is (re-find #"system-prompt" (:loop-system-prompt summary))))))

(deftest gather-codebase-summary-returns-nil-on-missing-files-test
  (testing "gather-codebase-summary returns nil values for files that don't exist"
    (let [summary (reflect/gather-codebase-summary "/nonexistent/path/that/does/not/exist")]
      (is (map? summary))
      (is (nil? (:fitness-fn summary)))
      (is (nil? (:tool-definitions summary)))
      (is (nil? (:reflect-prompt summary)))
      (is (nil? (:loop-system-prompt summary))))))

;; ---------------------------------------------------------------------------
;; load-reflect-system-prompt tests
;; ---------------------------------------------------------------------------

(deftest load-reflect-system-prompt-from-file-test
  (testing "loads from templates/reflect-system.md when present (real repo)"
    ;; The real repo has the template file; loading from PWD should succeed.
    (let [repo-root (.. js/process -env -PWD)
          loaded    (reflect/load-reflect-system-prompt repo-root)]
      (is (string? loaded))
      (is (seq loaded))
      ;; Should contain key phrases from the template
      (is (re-find #"program\.md" loaded))
      (is (re-find #"small and focused" loaded)))))

(deftest load-reflect-system-prompt-fallback-test
  (testing "falls back to embedded default when template file is missing"
    (let [loaded (reflect/load-reflect-system-prompt "/nonexistent/path/that/does/not/exist")]
      (is (string? loaded))
      (is (seq loaded))
      ;; Fallback default must still contain key phrases
      (is (re-find #"program\.md" loaded))
      (is (re-find #"Do NOT repeat" loaded)))))

(deftest build-reflect-prompt-uses-template-base-dir-test
  (testing "build-reflect-prompt loads system prompt from :base-dir when provided"
    (let [repo-root (.. js/process -env -PWD)
          context   {:priorities  "## 1. Test"
                     :generations []
                     :latest-gen  nil
                     :base-dir    repo-root}
          prompt    (reflect/build-reflect-prompt context)]
      (is (string? (:system prompt)))
      ;; System prompt from template should include expected content
      (is (re-find #"program\.md" (:system prompt))))))

(deftest build-reflect-prompt-uses-fallback-when-no-base-dir-test
  (testing "build-reflect-prompt uses module-level system-prompt when no :base-dir"
    (let [context {:priorities  nil
                   :generations []
                   :latest-gen  nil}
          prompt  (reflect/build-reflect-prompt context)]
      (is (string? (:system prompt)))
      (is (re-find #"program\.md" (:system prompt))))))

;; ---------------------------------------------------------------------------
;; extract-user-friction tests
;; ---------------------------------------------------------------------------

(deftest extract-user-friction-filters-by-source-test
  (testing "only generations with :source :user are returned"
    (let [gens [{:generation 1 :source :user    :outcome :failed   :program-md "# Task\nAdd caching\n## Req" :report nil}
                {:generation 2 :source :reflect :outcome :failed   :program-md "# Task\nFix bug\n## Req"    :report nil}
                {:generation 3 :source :cli     :outcome :failed   :program-md "# Task\nDeploy\n## Req"     :report nil}
                {:generation 4 :source nil      :outcome :failed   :program-md "# Task\nOld gen\n## Req"    :report nil}
                {:generation 5 :source :user    :outcome :promoted :program-md "# Task\nSucceed\n## Req"    :report nil}]
          friction (reflect/extract-user-friction gens)]
      (is (= 1 (count friction)))
      (is (= 1 (:generation (first friction)))))))

(deftest extract-user-friction-extracts-task-summary-test
  (testing "task-summary is taken from the first non-blank line of program-md"
    (let [gens [{:generation 7 :source :user :outcome :failed
                 :program-md "# Task\nRewrite the parser\n## Requirements\n- Do stuff"
                 :report nil}]
          friction (reflect/extract-user-friction gens)]
      (is (= "# Task" (:task-summary (first friction)))))))

(deftest extract-user-friction-failure-reason-test-failures-test
  (testing "failure-reason reflects test failures when test-results present"
    (let [gens [{:generation 8 :source :user :outcome :failed
                 :program-md "# Task\nFix parser"
                 :report {:test-results {:tests-run 5 :failures 3 :errors 1 :passed? false}}}]
          friction (reflect/extract-user-friction gens)]
      (is (= 1 (count friction)))
      (is (re-find #"3 failures" (:failure-reason (first friction))))
      (is (re-find #"1 errors" (:failure-reason (first friction)))))))

(deftest extract-user-friction-failure-reason-timeout-test
  (testing "failure-reason is 'timed out' for :timeout outcome"
    (let [gens [{:generation 9 :source :user :outcome :timeout
                 :program-md "# Task\nSlow operation"
                 :report nil}]
          friction (reflect/extract-user-friction gens)]
      (is (= 1 (count friction)))
      (is (= "timed out" (:failure-reason (first friction)))))))

(deftest extract-user-friction-handles-nil-program-md-test
  (testing "nil program-md uses 'unknown task' as summary"
    (let [gens [{:generation 10 :source :user :outcome :failed :program-md nil :report nil}]
          friction (reflect/extract-user-friction gens)]
      (is (= 1 (count friction)))
      (is (= "unknown task" (:task-summary (first friction)))))))

(deftest extract-user-friction-mixed-sources-test
  (testing "only :user source failures appear in result; reflect/cli/nil are excluded"
    (let [gens [{:generation 1 :source :user    :outcome :failed   :program-md "# Task\nA" :report nil}
                {:generation 2 :source :reflect :outcome :failed   :program-md "# Task\nB" :report nil}
                {:generation 3 :source :user    :outcome :failed   :program-md "# Task\nC" :report nil}
                {:generation 4 :source :cli     :outcome :failed   :program-md "# Task\nD" :report nil}
                {:generation 5 :source :user    :outcome :promoted :program-md "# Task\nE" :report nil}]
          friction (reflect/extract-user-friction gens)]
      (is (= 2 (count friction)))
      (is (= #{1 3} (set (map :generation friction)))))))

;; ---------------------------------------------------------------------------
;; build-reflect-prompt user-friction section tests
;; ---------------------------------------------------------------------------

(deftest build-reflect-prompt-includes-user-friction-section-test
  (testing "User Friction section appears when there are failed user tasks"
    (let [context {:priorities  "## 1. Fix bug"
                   :generations []
                   :latest-gen  nil
                   :user-friction [{:generation 3 :task-summary "# Task\nAdd caching" :failure-reason "test failures: 2 failures, 0 errors"}]}
          prompt (reflect/build-reflect-prompt context)
          user-msg (:content (first (:messages prompt)))]
      (is (re-find #"User Friction" user-msg))
      (is (re-find #"Gen 3" user-msg))
      (is (re-find #"Add caching" user-msg))
      (is (re-find #"test failures: 2 failures" user-msg))
      (is (re-find #"Consider whether improving the system" user-msg)))))

(deftest build-reflect-prompt-omits-user-friction-section-when-empty-test
  (testing "User Friction section is absent when user-friction is empty"
    (let [context {:priorities  "## 1. Fix bug"
                   :generations []
                   :latest-gen  nil
                   :user-friction []}
          prompt (reflect/build-reflect-prompt context)
          user-msg (:content (first (:messages prompt)))]
      (is (not (re-find #"User Friction" user-msg))))))

(deftest build-reflect-prompt-omits-user-friction-section-when-nil-test
  (testing "User Friction section is absent when user-friction key is missing/nil"
    (let [context {:priorities  "## 1. Fix bug"
                   :generations []
                   :latest-gen  nil}
          prompt (reflect/build-reflect-prompt context)
          user-msg (:content (first (:messages prompt)))]
      (is (not (re-find #"User Friction" user-msg))))))

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
