(ns loom.lab-setup-test
  (:require [cljs.test :refer [deftest is async testing]]
            [loom.supervisor.lab :as lab]
            [loom.supervisor.git :as git]))

(def ^:private fs (js/require "node:fs"))
(def ^:private path-mod (js/require "node:path"))
(def ^:private os (js/require "node:os"))
(def ^:private child-process (js/require "node:child_process"))

;; -- Helpers --

(defn- init-source-repo
  "Create a minimal git repo to serve as the source for cloning."
  []
  (let [dir (.mkdtempSync fs (.join path-mod (.tmpdir os) "loom-src-"))]
    (.execFileSync child-process "git" #js ["init" dir])
    (.execFileSync child-process "git" #js ["-C" dir "config" "user.email" "test@test.com"])
    (.execFileSync child-process "git" #js ["-C" dir "config" "user.name" "Test"])
    (.writeFileSync fs (.join path-mod dir "README.md") "# Test Repo" "utf8")
    (.writeFileSync fs (.join path-mod dir "src.cljs") "(ns core)" "utf8")
    (.execFileSync child-process "git" #js ["-C" dir "add" "."])
    (.execFileSync child-process "git" #js ["-C" dir "commit" "-m" "initial"])
    dir))

(defn- cleanup [dir]
  (.rmSync fs dir #js {:recursive true :force true}))

;; -- git/clone-repo tests --

(deftest test-git-clone
  (testing "clone copies a repo to a new directory"
    (async done
           (let [src (init-source-repo)
                 dest (.join path-mod (.tmpdir os) (str "loom-clone-" (rand-int 100000)))]
             (-> (git/clone-repo src dest)
                 (.then (fn [result]
                          (is (:ok result))
                          ;; Verify files exist in clone
                          (is (.existsSync fs (.join path-mod dest "README.md")))
                          (is (.existsSync fs (.join path-mod dest "src.cljs")))
                          (cleanup src)
                          (cleanup dest)
                          (done)))
                 (.catch (fn [err]
                           (cleanup src)
                           (is false (str "Unexpected: " err))
                           (done))))))))

(deftest test-git-commit
  (testing "commit stages and commits changes"
    (async done
           (let [src (init-source-repo)]
             ;; Add a new file and commit
             (.writeFileSync fs (.join path-mod src "new.txt") "new content" "utf8")
             (-> (git/commit src "add new file")
                 (.then (fn [result]
                          (is (:ok result))
                          ;; Verify the commit exists in log
                          (let [log (.toString
                                     (.execFileSync child-process
                                                    "git" #js ["-C" src "log" "--oneline"]))]
                            (is (re-find #"add new file" log)))
                          (cleanup src)
                          (done)))
                 (.catch (fn [err]
                           (cleanup src)
                           (is false (str "Unexpected: " err))
                           (done))))))))

;; -- setup-lab-repo tests --

(deftest test-setup-lab-repo
  (testing "setup-lab-repo clones, branches, and writes program.md"
    (async done
           (let [src (init-source-repo)
                 program "# Task\nImplement feature X"]
             (-> (lab/setup-lab-repo src "lab/gen-1" program)
                 (.then (fn [result]
                          (is (:ok result) (str "setup failed: " (:message result)))
                          (let [lab-dir (:lab-dir result)]
                            ;; Verify branch
                            (is (= "lab/gen-1" (:branch result)))
                            ;; Verify program.md exists with correct content
                            (let [content (.readFileSync fs
                                                         (.join path-mod lab-dir "program.md")
                                                         "utf8")]
                              (is (= program content)))
                            ;; Verify we're on the right branch
                            (let [branch (.toString
                                          (.execFileSync child-process
                                                         "git" #js ["-C" lab-dir "rev-parse"
                                                                    "--abbrev-ref" "HEAD"]))]
                              (is (= "lab/gen-1" (.trim branch))))
                            ;; Verify source files were cloned
                            (is (.existsSync fs (.join path-mod lab-dir "README.md")))
                            (is (.existsSync fs (.join path-mod lab-dir "src.cljs")))
                            ;; Verify program.md was committed (not just written)
                            (let [status (.toString
                                          (.execFileSync child-process
                                                         "git" #js ["-C" lab-dir "status" "--porcelain"]))]
                              (is (= "" (.trim status)) "working tree should be clean"))
                            (cleanup lab-dir))
                          (cleanup src)
                          (done)))
                 (.catch (fn [err]
                           (cleanup src)
                           (is false (str "Unexpected: " err))
                           (done))))))))

(deftest test-setup-lab-repo-preserves-source
  (testing "setup-lab-repo does not modify the source repo"
    (async done
           (let [src (init-source-repo)]
             ;; Record the original branch
             (let [original-branch (.trim (.toString
                                           (.execFileSync child-process
                                                          "git" #js ["-C" src "rev-parse"
                                                                     "--abbrev-ref" "HEAD"])))]
               (-> (lab/setup-lab-repo src "lab/gen-2" "# Task 2")
                   (.then (fn [result]
                            (is (:ok result))
                            ;; Source repo should still be on original branch
                            (let [current (.trim (.toString
                                                  (.execFileSync child-process
                                                                 "git" #js ["-C" src "rev-parse"
                                                                            "--abbrev-ref" "HEAD"])))]
                              (is (= original-branch current)
                                  "source repo branch should not change"))
                            ;; Source repo should not have program.md
                            (is (not (.existsSync fs (.join path-mod src "program.md")))
                                "source repo should not have program.md")
                            (cleanup (:lab-dir result))
                            (cleanup src)
                            (done)))
                   (.catch (fn [err]
                             (cleanup src)
                             (is false (str "Unexpected: " err))
                             (done)))))))))
