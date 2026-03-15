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
  "Create a temp directory for the Lab's repo clone.
   If base-dir is provided, create under that directory instead of system temp."
  [& {:keys [base-dir]}]
  (let [parent (or base-dir (.tmpdir os))]
    (when-not (.existsSync fs parent)
      (.mkdirSync fs parent #js {:recursive true}))
    (.mkdtempSync fs (.join path-mod parent "lab-"))))

(defn setup-lab-repo
  "Clone the source repo, create the lab branch, and write program.md.
   Returns a promise resolving to {:ok true :lab-dir <path> :branch <name>}
   or {:error true :message <string>}."
  [source-repo-path branch-name program-md & {:keys [base-dir]}]
  (let [lab-dir (make-lab-dir :base-dir base-dir)
        child-process (js/require "node:child_process")]
    (-> (git/clone-repo source-repo-path lab-dir)
        (.then (fn [result]
                 (if (:error result)
                   result
                   (do
                     ;; Set git user for lab commits, disable GPG signing
                     (.execFileSync child-process "git"
                                    #js ["-C" lab-dir "config" "user.email" "lab@loom.local"])
                     (.execFileSync child-process "git"
                                    #js ["-C" lab-dir "config" "user.name" "Loom Lab"])
                     (.execFileSync child-process "git"
                                    #js ["-C" lab-dir "config" "commit.gpgsign" "false"])
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

(defn cleanup-lab
  "Stop and remove a Lab container. Best-effort, never rejects."
  [container-name]
  (-> (container/stop container-name)
      (.catch (fn [_] nil))
      (.then (fn [_] (container/destroy container-name)))
      (.catch (fn [_] nil))))

(defn cancel-timeout
  "Cancel a scheduled Lab timeout by ID. Returns nil."
  [timeout-id]
  (js/clearTimeout timeout-id)
  nil)

(defn- copy-worker-js
  "Copy the compiled lab-worker.js into the lab workspace.
   worker-path: path to compiled out/lab-worker.js on the host."
  [worker-path lab-dir]
  (try
    (.copyFileSync fs worker-path (.join path-mod lab-dir "lab-worker.js"))
    {:ok true}
    (catch :default e
      {:error true :message (str "Failed to copy lab-worker.js: " (.-message e))})))

(defn spawn-lab
  "Full Lab spawn: clone repo, set up branch, write program.md, run container.
   Returns a promise resolving to:
     {:ok true :container-name <string> :lab-dir <path> :branch <string>
      :host-port <int> :timeout-id <int>}
   or {:error true :message <string>}.

   Options:
     :worker-path — path to compiled lab-worker.js (default: out/lab-worker.js)
     :on-timeout  — callback (fn [gen-num container-name]) called on 5-min timeout"
  [source-repo-path gen-num program-md
   & {:keys [image network env-vars container-port on-timeout worker-path lab-base-dir]
      :or {image "loom-lab:latest"
           container-port 8402
           on-timeout (fn [_gen-num _container-name])
           worker-path "out/lab-worker.js"}}]
  (let [branch         (str "lab/gen-" gen-num)
        container-name (str "lab-gen-" gen-num)
        ;; Inject ANTHROPIC_API_KEY from Supervisor's env into Lab
        api-key        (.-ANTHROPIC_API_KEY (.-env js/process))
        lab-env        (cond-> {:PORT (str container-port)}
                         api-key (assoc :ANTHROPIC_API_KEY api-key))]
    (-> (setup-lab-repo source-repo-path branch program-md :base-dir lab-base-dir)
        (.then (fn [result]
                 (if (:error result)
                   result
                   (let [lab-dir (:lab-dir result)
                         copy-result (copy-worker-js worker-path lab-dir)]
                     (if (:error copy-result)
                       copy-result
                       (let [publish-spec (str container-port ":" container-port)]
                         (-> (container/run
                              container-name image
                              ["node" "/workspace/lab-worker.js"]
                              ;; Dual-attach: default for internet, custom for container DNS
                              :networks (if network
                                          ["default" network]
                                          ["default"])
                              :env-vars (merge lab-env env-vars)
                              :volumes [[lab-dir "/workspace"]]
                              :publish publish-spec
                              :detach true)
                             (.then (fn [run-result]
                                      (if (:error run-result)
                                        run-result
                                        ;; Get the actual host port
                                        (-> (container/published-port container-name container-port)
                                            (.then (fn [host-port]
                                                     ;; Start 5-minute timeout
                                                     (let [timeout-id (js/setTimeout
                                                                       (fn []
                                                                         (on-timeout gen-num container-name)
                                                                         (cleanup-lab container-name))
                                                                       300000)]
                                                       {:ok true
                                                        :container-name container-name
                                                        :container-id (:container-id run-result)
                                                        :lab-dir lab-dir
                                                        :branch branch
                                                        :host-port host-port
                                                        :timeout-id timeout-id}))))))))))))))
        (.catch (fn [err]
                  {:error true :message (str "Spawn failed: " (.-message err))})))))
