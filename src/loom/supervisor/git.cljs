(ns loom.supervisor.git
  "Pure functions wrapping the git CLI via child_process.
   Each function shells out to git and returns a promise resolving
   to structured data — never throws."
  (:require [clojure.string :as str]))

(def ^:private child-process (js/require "node:child_process"))

(defn- git
  "Run a git command in `cwd`. Returns a promise that always resolves:
   {:ok true :output \"...\"} on success,
   {:error true :message \"...\" :exit-code N} on failure."
  [cwd args]
  (js/Promise.
   (fn [resolve _reject]
     (let [cb (fn [err stdout stderr]
                (if err
                  (resolve {:error true
                            :message (str (str/trim (str stderr)) " " (str/trim (str stdout)))
                            :exit-code (or (.-code err) 1)})
                  (resolve {:ok true
                            :output (str/trim (str stdout))})))]
       (.execFile child-process
                  "git"
                  (clj->js args)
                  #js {:cwd cwd}
                  cb)))))

(defn create-branch
  "Create and checkout a new branch. Returns promise."
  [cwd branch-name]
  (git cwd ["checkout" "-b" branch-name]))

(defn merge-branch
  "Merge branch into current branch. Returns promise."
  [cwd branch-name]
  (git cwd ["merge" branch-name]))

(defn tag
  "Create an annotated tag. Returns promise.
   Options: :message — annotation message (defaults to tag name)."
  [cwd tag-name & {:keys [message]}]
  (let [msg (or message tag-name)]
    (git cwd ["tag" "-a" tag-name "-m" msg])))

(defn delete-branch
  "Delete a branch. Returns promise."
  [cwd branch-name]
  (git cwd ["branch" "-d" branch-name]))

(defn current-branch
  "Return the name of the current branch. Returns promise."
  [cwd]
  (git cwd ["rev-parse" "--abbrev-ref" "HEAD"]))

(defn checkout
  "Checkout an existing branch. Returns promise."
  [cwd branch-name]
  (git cwd ["checkout" branch-name]))
