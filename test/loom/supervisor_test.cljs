(ns loom.supervisor-test
  (:require [cljs.test :refer [deftest is async testing]]
            [loom.supervisor.git :as git]
            [loom.supervisor.generations :as gen]
            [loom.supervisor.core :as core]))

(def ^:private fs (js/require "node:fs"))
(def ^:private path-mod (js/require "node:path"))
(def ^:private os (js/require "node:os"))
(def ^:private child-process (js/require "node:child_process"))

;; -- Helpers --

(defn- make-temp-dir []
  (.mkdtempSync fs (.join path-mod (.tmpdir os) "loom-test-")))

(defn- init-git-repo
  "Create a temp dir, git init, make an initial commit. Returns the path."
  []
  (let [dir (make-temp-dir)]
    (.execFileSync child-process "git" #js ["init" dir])
    (.execFileSync child-process "git" #js ["-C" dir "config" "user.email" "test@test.com"])
    (.execFileSync child-process "git" #js ["-C" dir "config" "user.name" "Test"])
    (.writeFileSync fs (.join path-mod dir "README") "init" "utf8")
    (.execFileSync child-process "git" #js ["-C" dir "add" "."])
    (.execFileSync child-process "git" #js ["-C" dir "commit" "-m" "initial"])
    dir))

(defn- cleanup [dir]
  (.rmSync fs dir #js {:recursive true :force true}))

;; -- Git tests --

(deftest git-current-branch
  (testing "current-branch returns the branch name"
    (async done
           (let [dir (init-git-repo)]
             (-> (git/current-branch dir)
                 (.then (fn [result]
                          (is (:ok result))
                     ;; git init defaults to master or main
                          (is (string? (:output result)))
                          (is (pos? (count (:output result))))
                          (cleanup dir)
                          (done))))))))

(deftest git-create-branch
  (testing "create-branch creates and checks out a new branch"
    (async done
           (let [dir (init-git-repo)]
             (-> (git/create-branch dir "feature-x")
                 (.then (fn [result]
                          (is (:ok result))
                          (git/current-branch dir)))
                 (.then (fn [result]
                          (is (= "feature-x" (:output result)))
                          (cleanup dir)
                          (done))))))))

(deftest git-checkout
  (testing "checkout switches to an existing branch"
    (async done
           (let [dir (init-git-repo)]
             (-> (git/create-branch dir "branch-a")
                 (.then (fn [_] (git/current-branch dir)))
                 (.then (fn [result]
                          (is (= "branch-a" (:output result)))
                          (git/checkout dir "master")))
                 (.then (fn [result]
                          (is (:ok result))
                          (git/current-branch dir)))
                 (.then (fn [result]
                     ;; Could be "master" or "main" depending on git config
                          (is (string? (:output result)))
                          (cleanup dir)
                          (done))))))))

(deftest git-merge-branch
  (testing "merge-branch merges a branch into current"
    (async done
           (let [dir (init-git-repo)]
        ;; Create a feature branch with a commit
             (-> (git/create-branch dir "feat")
                 (.then (fn [_]
                          (.writeFileSync fs (.join path-mod dir "new.txt") "hello" "utf8")
                          (.execFileSync child-process "git" #js ["-C" dir "add" "."])
                          (.execFileSync child-process "git" #js ["-C" dir "commit" "-m" "feat commit"])
                     ;; Go back to original branch and merge
                          (git/checkout dir "master")))
                 (.then (fn [_] (git/merge-branch dir "feat")))
                 (.then (fn [result]
                          (is (:ok result))
                     ;; Verify file exists after merge
                          (is (.existsSync fs (.join path-mod dir "new.txt")))
                          (cleanup dir)
                          (done))))))))

(deftest git-merge-nonexistent-branch
  (testing "merge-branch returns error for nonexistent branch"
    (async done
           (let [dir (init-git-repo)]
             (-> (git/merge-branch dir "does-not-exist")
                 (.then (fn [result]
                          (is (:error result))
                          (is (some? (:message result)))
                          (is (some? (:exit-code result)))
                          (cleanup dir)
                          (done))))))))

(deftest git-tag
  (testing "tag creates an annotated tag"
    (async done
           (let [dir (init-git-repo)]
             (-> (git/tag dir "v1.0" :message "Release 1.0")
                 (.then (fn [result]
                          (is (:ok result))
                          (cleanup dir)
                          (done))))))))

(deftest git-delete-branch
  (testing "delete-branch removes a merged branch"
    (async done
           (let [dir (init-git-repo)]
             (-> (git/create-branch dir "to-delete")
                 (.then (fn [_] (git/checkout dir "master")))
                 (.then (fn [_] (git/delete-branch dir "to-delete")))
                 (.then (fn [result]
                          (is (:ok result))
                          (cleanup dir)
                          (done))))))))

;; -- Generations tests --

(defn- make-temp-edn-path []
  (.join path-mod (make-temp-dir) "generations.edn"))

(def ^:private sample-gen
  {:generation 1
   :parent 0
   :branch "gen-1"
   :program-md-hash "abc123"
   :outcome :in-progress
   :created "2026-03-13T00:00:00Z"
   :container-id "ctr-001"})

(deftest gen-read-missing-file
  (testing "read-generations returns [] for missing file"
    (let [path (make-temp-edn-path)]
      (is (= [] (gen/read-generations path))))))

(deftest gen-append-and-read
  (testing "append-generation adds a record that can be read back"
    (let [path (make-temp-edn-path)]
      (gen/append-generation path sample-gen)
      (let [gens (gen/read-generations path)]
        (is (= 1 (count gens)))
        (is (= sample-gen (first gens)))))))

(deftest gen-update-generation
  (testing "update-generation modifies the right record"
    (let [path (make-temp-edn-path)]
      (gen/append-generation path sample-gen)
      (gen/append-generation path (assoc sample-gen :generation 2 :branch "gen-2"))
      (gen/update-generation path 1 {:outcome :promoted :completed "2026-03-13T01:00:00Z"})
      (let [gens (gen/read-generations path)
            g1 (first gens)
            g2 (second gens)]
        (is (= :promoted (:outcome g1)))
        (is (= "2026-03-13T01:00:00Z" (:completed g1)))
        ;; gen 2 should be unchanged
        (is (= :in-progress (:outcome g2)))))))

(deftest gen-next-generation-number
  (testing "next-generation-number returns correct values"
    (let [path (make-temp-edn-path)]
      (is (= 1 (gen/next-generation-number path)))
      (gen/append-generation path sample-gen)
      (is (= 2 (gen/next-generation-number path)))
      (gen/append-generation path (assoc sample-gen :generation 5))
      (is (= 6 (gen/next-generation-number path))))))

(deftest gen-validation-rejects-bad-records
  (testing "Malli validation rejects invalid records"
    (is (false? (gen/valid? {})))
    (is (false? (gen/valid? {:generation "not-int"})))
    (is (false? (gen/valid? (dissoc sample-gen :container-id))))
    (is (false? (gen/valid? (assoc sample-gen :outcome :invalid-outcome))))
    (is (true? (gen/valid? sample-gen)))))

(deftest gen-validation-done-outcome
  (testing ":done is a valid outcome"
    (is (true? (gen/valid? (assoc sample-gen :outcome :done))))))

(deftest gen-validation-bogus-outcome
  (testing ":bogus is not a valid outcome"
    (is (false? (gen/valid? (assoc sample-gen :outcome :bogus))))))

(deftest gen-source-field
  (testing "Generation schema accepts optional :source field"
    (is (true? (gen/valid? (assoc sample-gen :source :reflect))))
    (is (true? (gen/valid? (assoc sample-gen :source :user))))
    (is (true? (gen/valid? (assoc sample-gen :source :cli))))
    (is (false? (gen/valid? (assoc sample-gen :source :invalid))))
    (is (true? (gen/valid? sample-gen)) "source is optional — omitting it still passes")))

;; -- Reconcile stale generations tests --

(deftest reconcile-stale-generations-marks-old-in-progress-as-timeout
  (testing "reconcile-stale-generations updates old :in-progress records to :timeout"
    (let [path       (make-temp-edn-path)
          ;; A generation created 10 minutes ago (older than 5-min timeout)
          old-ts     (.toISOString (js/Date. (- (.now js/Date) 600000)))
          ;; A generation created 1 minute ago (within timeout)
          recent-ts  (.toISOString (js/Date. (- (.now js/Date) 60000)))
          old-gen    {:generation 1 :parent 0 :branch "gen-1"
                      :program-md-hash "abc" :outcome :in-progress
                      :created old-ts :container-id "ctr-1"}
          recent-gen {:generation 2 :parent 0 :branch "gen-2"
                      :program-md-hash "def" :outcome :in-progress
                      :created recent-ts :container-id "ctr-2"}
          done-gen   {:generation 3 :parent 0 :branch "gen-3"
                      :program-md-hash "ghi" :outcome :done
                      :created old-ts :container-id "ctr-3"
                      :completed old-ts}]
      (gen/append-generation path old-gen)
      (gen/append-generation path recent-gen)
      (gen/append-generation path done-gen)
      ;; 5-minute timeout in ms
      (let [reconciled (core/reconcile-stale-generations path 300000)
            gens       (gen/read-generations path)
            g1         (first gens)
            g2         (second gens)
            g3         (nth gens 2)]
        ;; Only the old :in-progress should be reconciled
        (is (= 1 reconciled))
        (is (= :timeout (:outcome g1)))
        (is (string? (:completed g1)))
        ;; Recent :in-progress should be untouched
        (is (= :in-progress (:outcome g2)))
        (is (nil? (:completed g2)))
        ;; :done record should be untouched
        (is (= :done (:outcome g3)))))))

(deftest reconcile-stale-generations-no-stale
  (testing "reconcile-stale-generations returns 0 when no stale records exist"
    (let [path      (make-temp-edn-path)
          recent-ts (.toISOString (js/Date. (- (.now js/Date) 60000)))
          gen1      {:generation 1 :parent 0 :branch "gen-1"
                     :program-md-hash "abc" :outcome :in-progress
                     :created recent-ts :container-id "ctr-1"}]
      (gen/append-generation path gen1)
      (is (= 0 (core/reconcile-stale-generations path 300000))))))
