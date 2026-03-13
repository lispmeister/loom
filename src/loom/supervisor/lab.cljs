(ns loom.supervisor.lab
  "Lab container orchestration: clone repo, set up branch, write program.md,
   launch container with volume mount."
  (:require [loom.supervisor.git :as git]
            [loom.supervisor.container :as container]
            [clojure.string :as str]))

(def ^:private fs (js/require "node:fs"))
(def ^:private path-mod (js/require "node:path"))
(def ^:private os (js/require "node:os"))

(defn- make-lab-dir
  "Create a temp directory for the Lab's repo clone."
  []
  (.mkdtempSync fs (.join path-mod (.tmpdir os) "loom-lab-")))

(defn setup-lab-repo
  "Clone the source repo, create the lab branch, and write program.md.
   Returns a promise resolving to {:ok true :lab-dir <path> :branch <name>}
   or {:error true :message <string>}."
  [source-repo-path branch-name program-md]
  (let [lab-dir (make-lab-dir)
        child-process (js/require "node:child_process")]
    (-> (git/clone-repo source-repo-path lab-dir)
        (.then (fn [result]
                 (if (:error result)
                   result
                   (do
                     ;; Set git user for lab commits
                     (.execFileSync child-process "git"
                                    #js ["-C" lab-dir "config" "user.email" "lab@loom.local"])
                     (.execFileSync child-process "git"
                                    #js ["-C" lab-dir "config" "user.name" "Loom Lab"])
                     (git/create-branch lab-dir branch-name)))))
        (.then (fn [result]
                 (if (:error result)
                   result
                   (do
                     ;; Write program.md into the lab repo
                     (.writeFileSync fs
                                     (.join path-mod lab-dir "program.md")
                                     program-md "utf8")
                     ;; Commit so the lab starts from a clean state
                     (git/commit lab-dir (str "Add program.md for " branch-name))))))
        (.then (fn [result]
                 (if (:error result)
                   result
                   {:ok true :lab-dir lab-dir :branch branch-name})))
        (.catch (fn [err]
                  {:error true :message (str "Lab setup failed: " (.-message err))})))))

(defn spawn-lab
  "Full Lab spawn: clone repo, set up branch, write program.md, run container.
   Returns a promise resolving to:
     {:ok true :container-name <string> :lab-dir <path> :branch <string> :host-port <int>}
   or {:error true :message <string>}."
  [source-repo-path gen-num program-md
   & {:keys [image network env-vars container-port]
      :or {image "loom-lab:latest"
           container-port 8402}}]
  (let [branch         (str "lab/gen-" gen-num)
        container-name (str "lab-gen-" gen-num)]
    (-> (setup-lab-repo source-repo-path branch program-md)
        (.then (fn [result]
                 (if (:error result)
                   result
                   (let [lab-dir (:lab-dir result)
                         publish-spec (str container-port ":" container-port)]
                     (-> (container/run
                          container-name image nil
                          :network network
                          :env-vars (merge {:CACHE_PATH "/app/cljs-core-cache.transit.json"}
                                           env-vars)
                          :volumes [[lab-dir "/workspace"]]
                          :publish publish-spec
                          :detach true)
                         (.then (fn [run-result]
                                  (if (:error run-result)
                                    run-result
                                    ;; Get the actual host port
                                    (-> (container/published-port container-name container-port)
                                        (.then (fn [host-port]
                                                 {:ok true
                                                  :container-name container-name
                                                  :container-id (:container-id run-result)
                                                  :lab-dir lab-dir
                                                  :branch branch
                                                  :host-port host-port})))))))))))
        (.catch (fn [err]
                  {:error true :message (str "Spawn failed: " (.-message err))})))))

(defn cleanup-lab
  "Stop and remove a Lab container. Best-effort, never rejects."
  [container-name]
  (-> (container/stop container-name)
      (.catch (fn [_] nil))
      (.then (fn [_] (container/destroy container-name)))
      (.catch (fn [_] nil))))
