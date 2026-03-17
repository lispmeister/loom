(ns loom.tools-test
  (:require [cljs.test :refer [deftest is async testing]]
            [loom.agent.tools :as tools]
            [loom.agent.dispatch :as dispatch]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:os" :as os]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn make-tmpdir
  "Create a temporary directory, return its path."
  []
  (.mkdtempSync fs (.join node-path (.tmpdir os) "loom-test-")))

(defn cleanup-tmpdir
  "Recursively remove a temporary directory."
  [dir]
  (.rmSync fs dir #js {:recursive true :force true}))

;; ---------------------------------------------------------------------------
;; read-file tests
;; ---------------------------------------------------------------------------

(deftest read-file-existing
  (testing "reads an existing file and returns its contents"
    (async done
           (let [dir (make-tmpdir)
                 fp  (.join node-path dir "hello.txt")]
             (.writeFileSync fs fp "hello world")
             (-> (tools/read-file {:path fp})
                 (.then (fn [result]
                          (is (= "1\thello world" result))
                          (cleanup-tmpdir dir)
                          (done))))))))

(deftest read-file-nonexistent
  (testing "returns error string for nonexistent file"
    (async done
           (-> (tools/read-file {:path "/tmp/loom-does-not-exist-xyz.txt"})
               (.then (fn [result]
                        (is (string? result))
                        (is (re-find #"Error reading" result))
                        (done)))))))

;; ---------------------------------------------------------------------------
;; write-file tests
;; ---------------------------------------------------------------------------

(deftest write-file-new
  (testing "writes a new file and can read it back"
    (async done
           (let [dir (make-tmpdir)
                 fp  (.join node-path dir "out.txt")]
             (-> (tools/write-file {:path fp :content "test content"})
                 (.then (fn [result]
                          (is (re-find #"Wrote" result))
                          (let [contents (.readFileSync fs fp "utf8")]
                            (is (= "test content" contents))
                            (cleanup-tmpdir dir)
                            (done)))))))))

(deftest write-file-nested
  (testing "creates parent directories as needed"
    (async done
           (let [dir (make-tmpdir)
                 fp  (.join node-path dir "a" "b" "c" "deep.txt")]
             (-> (tools/write-file {:path fp :content "deep"})
                 (.then (fn [result]
                          (is (re-find #"Wrote" result))
                          (let [contents (.readFileSync fs fp "utf8")]
                            (is (= "deep" contents))
                            (cleanup-tmpdir dir)
                            (done)))))))))

;; ---------------------------------------------------------------------------
;; edit-file tests
;; ---------------------------------------------------------------------------

(deftest edit-file-replace
  (testing "replaces a string in a file"
    (async done
           (let [dir (make-tmpdir)
                 fp  (.join node-path dir "edit.txt")]
             (.writeFileSync fs fp "foo bar baz")
             (-> (tools/edit-file {:path fp :old_string "bar" :new_string "qux"})
                 (.then (fn [result]
                          (is (re-find #"replaced" result))
                          (let [contents (.readFileSync fs fp "utf8")]
                            (is (= "foo qux baz" contents))
                            (cleanup-tmpdir dir)
                            (done)))))))))

(deftest edit-file-not-found
  (testing "returns error when old-string not found"
    (async done
           (let [dir (make-tmpdir)
                 fp  (.join node-path dir "edit2.txt")]
             (.writeFileSync fs fp "hello world")
             (-> (tools/edit-file {:path fp :old_string "xyz" :new_string "abc"})
                 (.then (fn [result]
                          (is (re-find #"not found" result))
                          (cleanup-tmpdir dir)
                          (done))))))))

;; ---------------------------------------------------------------------------
;; bash tests
;; ---------------------------------------------------------------------------

(deftest bash-echo
  (testing "runs echo command and captures stdout"
    (async done
           (-> (tools/bash {:command "echo hello"})
               (.then (fn [result]
                        (is (re-find #"hello" result))
                        (is (re-find #"exit-code: 0" result))
                        (done)))))))

(deftest bash-failing-command
  (testing "captures non-zero exit code"
    (async done
           (-> (tools/bash {:command "exit 42"})
               (.then (fn [result]
                        (is (re-find #"exit-code: 42" result))
                        (done)))))))

;; ---------------------------------------------------------------------------
;; dispatch tests
;; ---------------------------------------------------------------------------

(deftest dispatch-single-tool-call
  (testing "dispatches a read_file call through the registry"
    (async done
           (let [dir (make-tmpdir)
                 fp  (.join node-path dir "dispatch.txt")]
             (.writeFileSync fs fp "dispatch test")
             (-> (dispatch/dispatch-tool-call
                  {:id "toolu_test1" :name "read_file" :input {:path fp}})
                 (.then (fn [msg]
                          (is (= "user" (:role msg)))
                          (let [block (first (:content msg))]
                            (is (= "tool_result" (:type block)))
                            (is (= "toolu_test1" (:tool_use_id block)))
                            (is (= "1\tdispatch test" (:content block)))
                            (is (= false (:is_error block))))
                          (cleanup-tmpdir dir)
                          (done))))))))

(deftest dispatch-all-multiple
  (testing "dispatches multiple tool calls sequentially"
    (async done
           (let [dir (make-tmpdir)
                 fp  (.join node-path dir "multi.txt")]
             (-> (dispatch/dispatch-all
                  [{:id "toolu_w1" :name "write_file"
                    :input {:path fp :content "hello from dispatch"}}
                   {:id "toolu_r1" :name "read_file"
                    :input {:path fp}}])
                 (.then (fn [results]
                          (is (= 2 (count results)))
                     ;; First result: write
                          (let [b1 (first (:content (first results)))]
                            (is (= "toolu_w1" (:tool_use_id b1)))
                            (is (re-find #"Wrote" (:content b1))))
                     ;; Second result: read back what was written
                          (let [b2 (first (:content (second results)))]
                            (is (= "toolu_r1" (:tool_use_id b2)))
                            (is (= "1\thello from dispatch" (:content b2))))
                          (cleanup-tmpdir dir)
                          (done))))))))

(deftest dispatch-unknown-tool
  (testing "returns error for unknown tool name"
    (async done
           (-> (dispatch/dispatch-tool-call
                {:id "toolu_bad" :name "nonexistent_tool" :input {}})
               (.then (fn [msg]
                        (let [block (first (:content msg))]
                          (is (= true (:is_error block)))
                          (is (re-find #"unknown tool" (:content block))))
                        (done)))))))

;; ---------------------------------------------------------------------------
;; build-program-md tests
;; ---------------------------------------------------------------------------

(deftest build-program-md-contains-task-description
  (testing "includes task description in output"
    (let [result (tools/build-program-md "add a REST endpoint for health checks")]
      (is (string? result))
      (is (re-find #"add a REST endpoint for health checks" result)))))

(deftest build-program-md-has-required-sections
  (testing "output contains # Task heading and ## Acceptance Criteria"
    (let [result (tools/build-program-md "some task")]
      (is (re-find #"# Task" result))
      (is (re-find #"## Acceptance Criteria" result)))))

(deftest build-program-md-has-standard-criteria
  (testing "acceptance criteria include tests pass and functionality works"
    (let [result (tools/build-program-md "some task")]
      (is (re-find #"All existing tests pass" result))
      (is (re-find #"New functionality works as described" result)))))

(deftest build-task-registered
  (testing "build_task is in the tool definitions"
    (let [names (map :name tools/tool-definitions)]
      (is (some #{"build_task"} names)))))
