(ns loom.state-test
  "Tests for Prime state serialization and loading."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [loom.agent.state :as state]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))

;; ---------------------------------------------------------------------------
;; Test fixture: redirect state file to a temp directory
;; ---------------------------------------------------------------------------

(defonce ^:private original-state-file-path state/state-file-path)

(defn- make-tmpdir []
  (.mkdtempSync fs (.join path (.tmpdir os) "loom-state-test-")))

(defn- cleanup [dir]
  (.rmSync fs dir #js {:recursive true :force true}))

;; We override the state file path by monkey-patching the Var for each test.
;; Tests create their own tmp dirs and set the path directly via the fs helpers.

;; ---------------------------------------------------------------------------
;; Helper: write a raw string to a file path (bypasses serialize-state)
;; ---------------------------------------------------------------------------

(defn- write-raw [fpath content]
  (.mkdirSync fs (.dirname path fpath) #js {:recursive true})
  (.writeFileSync fs fpath content "utf8"))

;; ---------------------------------------------------------------------------
;; Roundtrip test
;; ---------------------------------------------------------------------------

(deftest serialize-load-roundtrip-test
  (testing "serialize and load returns the original state map"
    (let [tmpdir (make-tmpdir)
          fpath  (.join path tmpdir "prime-state.edn")]
      (try
        ;; Override the state file path
        (with-redefs [state/state-file-path (constantly fpath)]
          (let [input {:conversation-history [{:role "user" :content "hello"}]
                       :generation-count     7
                       :promoted-count       3}]
            (state/serialize-state input)
            (let [loaded (state/load-state)]
              (is (map? loaded) "load-state returns a map")
              (is (= 7 (:generation-count loaded)))
              (is (= 3 (:promoted-count loaded)))
              (is (= [{:role "user" :content "hello"}]
                     (:conversation-history loaded)))
              (is (nil? (:version loaded)) "version stripped from loaded state")
              (is (nil? (:timestamp loaded)) "timestamp stripped from loaded state"))))
        (finally
          (cleanup tmpdir))))))

;; ---------------------------------------------------------------------------
;; Version mismatch returns nil
;; ---------------------------------------------------------------------------

(deftest load-state-version-mismatch-test
  (testing "returns nil when version does not match current version"
    (let [tmpdir (make-tmpdir)
          fpath  (.join path tmpdir "prime-state.edn")]
      (try
        (with-redefs [state/state-file-path (constantly fpath)]
          ;; Write a state with version 999 (future version)
          (write-raw fpath "{:version 999 :generation-count 1}")
          (is (nil? (state/load-state)) "version mismatch returns nil"))
        (finally
          (cleanup tmpdir))))))

;; ---------------------------------------------------------------------------
;; Missing file returns nil
;; ---------------------------------------------------------------------------

(deftest load-state-missing-file-test
  (testing "returns nil when state file does not exist"
    (let [tmpdir (make-tmpdir)
          fpath  (.join path tmpdir "nonexistent" "prime-state.edn")]
      (try
        (with-redefs [state/state-file-path (constantly fpath)]
          (is (nil? (state/load-state)) "missing file returns nil"))
        (finally
          (cleanup tmpdir))))))

;; ---------------------------------------------------------------------------
;; Corrupt file returns nil
;; ---------------------------------------------------------------------------

(deftest load-state-corrupt-file-test
  (testing "returns nil on EDN parse error"
    (let [tmpdir (make-tmpdir)
          fpath  (.join path tmpdir "prime-state.edn")]
      (try
        (with-redefs [state/state-file-path (constantly fpath)]
          (write-raw fpath "this is not valid { edn [}")
          (is (nil? (state/load-state)) "corrupt EDN returns nil"))
        (finally
          (cleanup tmpdir))))))

;; ---------------------------------------------------------------------------
;; clear-state removes the file
;; ---------------------------------------------------------------------------

(deftest clear-state-test
  (testing "clear-state deletes the state file"
    (let [tmpdir (make-tmpdir)
          fpath  (.join path tmpdir "prime-state.edn")]
      (try
        (with-redefs [state/state-file-path (constantly fpath)]
          (state/serialize-state {:generation-count 1})
          (is (.existsSync fs fpath) "file exists after serialize")
          (state/clear-state)
          (is (not (.existsSync fs fpath)) "file removed after clear-state"))
        (finally
          (cleanup tmpdir))))))

(deftest clear-state-noop-when-missing-test
  (testing "clear-state is a no-op when file does not exist"
    (let [tmpdir (make-tmpdir)
          fpath  (.join path tmpdir "prime-state.edn")]
      (try
        (with-redefs [state/state-file-path (constantly fpath)]
          ;; Should not throw
          (is (nil? (state/clear-state)) "returns nil without error"))
        (finally
          (cleanup tmpdir))))))

;; ---------------------------------------------------------------------------
;; serialize-state creates tmp dir if missing
;; ---------------------------------------------------------------------------

(deftest serialize-creates-dir-test
  (testing "serialize-state creates the parent directory if it does not exist"
    (let [tmpdir (make-tmpdir)
          fpath  (.join path tmpdir "nested" "dir" "prime-state.edn")]
      (try
        (with-redefs [state/state-file-path (constantly fpath)]
          (state/serialize-state {:generation-count 5})
          (is (.existsSync fs fpath) "file created even when directory was missing"))
        (finally
          (cleanup tmpdir))))))
