(ns loom.supervisor.lab
  "Lab container orchestration: clone repo, set up branch, write program.md,
   launch container with volume mount, poll Lab status."
  (:require [loom.supervisor.git :as git]
            [loom.supervisor.container :as container]
            [loom.shared.http-client :as client]
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
                     ;; Write .gitignore to prevent runtime artifacts from being committed
                     (let [gitignore-path (.join path-mod lab-dir ".gitignore")
                           existing (if (.existsSync fs gitignore-path)
                                      (str (.readFileSync fs gitignore-path "utf8") "\n")
                                      "")
                           entries "# Loom Lab runtime artifacts\nlab-worker.js\nprogram.md\n"]
                       (.writeFileSync fs gitignore-path (str existing entries) "utf8"))
                     ;; Write program.md into the lab repo
                     (.writeFileSync fs
                                     (.join path-mod lab-dir "program.md")
                                     program-md "utf8")
                     ;; Commit so the lab starts from a clean state
                     (git/commit lab-dir (str "Add .gitignore and program.md for " branch-name))))))
        (.then (fn [result]
                 (if (:error result)
                   result
                   {:ok true :lab-dir lab-dir :branch branch-name})))
        (.catch (fn [err]
                  {:error true :message (str "Lab setup failed: " (.-message err))})))))

(defn cleanup-lab-workspace
  "Delete old Lab workspace directories. Best-effort, never throws.
   Keeps the last `keep-count` workspaces (by modification time) for debugging."
  [lab-base-dir & {:keys [keep-count] :or {keep-count 3}}]
  (try
    (when (and lab-base-dir (.existsSync fs lab-base-dir))
      (let [entries (->> (.readdirSync fs lab-base-dir)
                         (js->clj)
                         (filter #(.startsWith % "lab-"))
                         (sort-by (fn [name]
                                    (try
                                      (.-mtimeMs (.statSync fs (.join path-mod lab-base-dir name)))
                                      (catch :default _ 0))))
                         (reverse))
            to-delete (drop keep-count entries)]
        (doseq [dir-name to-delete]
          (let [full-path (.join path-mod lab-base-dir dir-name)]
            (.rmSync fs full-path #js {:recursive true :force true})))))
    (catch :default e
      (js/console.warn "Lab workspace cleanup failed:" (.-message e)))))

(defn save-program-md
  "Save program.md to programs-dir/gen-N.md for reference."
  [programs-dir gen-num program-md]
  (when-not (.existsSync fs programs-dir)
    (.mkdirSync fs programs-dir #js {:recursive true}))
  (.writeFileSync fs
                  (.join path-mod programs-dir (str "gen-" gen-num ".md"))
                  program-md "utf8"))

(defn save-generation-report
  "Write gen-N-report.json with lifecycle metadata.
   Best-effort — never throws."
  [programs-dir gen-num report-data]
  (try
    (when-not (.existsSync fs programs-dir)
      (.mkdirSync fs programs-dir #js {:recursive true}))
    (.writeFileSync fs
                    (.join path-mod programs-dir (str "gen-" gen-num "-report.json"))
                    (js/JSON.stringify (clj->js report-data) nil 2)
                    "utf8")
    (catch :default e
      (js/console.warn "Failed to write generation report:" (.-message e)))))

;; ---------------------------------------------------------------------------
;; Lab status polling
;; ---------------------------------------------------------------------------

(def ^:private poll-interval-ms
  "How often the Supervisor polls a Lab container's /status endpoint."
  5000)

(defn poll-lab-status
  "Poll a Lab container's /status endpoint every poll-interval-ms.
   Calls on-status with the parsed status map on every successful poll.
   Calls on-done with the final status when the Lab reports 'done' or 'failed'.
   Stops polling on done/failed or when the returned cancel-fn is called.
   Returns a cancel function."
  [host-port on-status on-done]
  (let [cancelled  (atom false)
        status-url (str "http://localhost:" host-port "/status")
        poll-fn    (atom nil)
        do-poll    (fn []
                     (when-not @cancelled
                       (-> (client/get-json status-url :timeout 3000)
                           (.catch (fn [_] {:error true :message "connection refused"}))
                           (.then (fn [result]
                                    (when-not @cancelled
                                      (let [http-err? (true? (:error result))
                                            status (when-not http-err? (:status result))]
                                        (when-not http-err?
                                          (on-status result))
                                        (cond
                                          (= status "done")   (on-done result)
                                          (= status "failed") (on-done result)
                                          ;; Still running or not ready — poll again
                                          :else (js/setTimeout @poll-fn poll-interval-ms)))))))))]
    (reset! poll-fn do-poll)
    ;; Start first poll after a short delay for container boot
    (js/setTimeout do-poll 2000)
    ;; Return cancel function
    (fn [] (reset! cancelled true))))

;; ---------------------------------------------------------------------------
;; Container lifecycle
;; ---------------------------------------------------------------------------

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
   Starts polling Lab /status immediately after container launch.
   Returns a promise resolving to:
     {:ok true :container-name <string> :lab-dir <path> :branch <string>
      :host-port <int> :timeout-id <int> :cancel-poll <fn>}
   or {:error true :message <string>}.

   Options:
     :worker-path   — path to compiled lab-worker.js (default: out/lab-worker.js)
     :on-timeout    — (fn [gen-num container-name]) called when hard timeout fires
     :on-lab-status — (fn [status-map]) called on each successful poll
     :on-lab-done   — (fn [final-status]) called when Lab reports done/failed
     :timeout-ms    — hard timeout in ms (default: 600000 = 10 min)"
  [source-repo-path gen-num program-md
   & {:keys [image network env-vars container-port on-timeout on-lab-status on-lab-done
             worker-path lab-base-dir timeout-ms]
      :or {image "loom-lab:latest"
           container-port 8402
           on-timeout (fn [_gen-num _container-name])
           on-lab-status (fn [_status])
           on-lab-done (fn [_status])
           worker-path "out/lab-worker.js"
           timeout-ms 300000}}]
  ;; Pre-flight checks
  (cond
    (not (.existsSync fs worker-path))
    (js/Promise.resolve
     {:error true
      :message (str "Build artifact missing: " worker-path
                    ". Run 'npx shadow-cljs release lab-worker' first.")})

    (and (nil? (.-LOOM_LAB_API_KEY (.-env js/process)))
         (nil? (.-ANTHROPIC_API_KEY (.-env js/process))))
    (js/Promise.resolve
     {:error true
      :message "No API key for Lab: set LOOM_LAB_API_KEY or ANTHROPIC_API_KEY. Source .env before starting."})

    :else
    (let [branch         (str "lab/gen-" gen-num)
          container-name (str "lab-gen-" gen-num)
          ;; Inject API credentials into Lab container.
          ;; LOOM_LAB_API_KEY / LOOM_LAB_API_BASE allow a separate provider for Labs
          ;; (e.g. Minimax Anthropic-compatible endpoint). Falls back to Anthropic.
          api-key        (or (.-LOOM_LAB_API_KEY (.-env js/process))
                             (.-ANTHROPIC_API_KEY (.-env js/process)))
          api-base       (or (.-LOOM_LAB_API_BASE (.-env js/process))
                             (.-ANTHROPIC_API_BASE (.-env js/process)))
          ;; Forward Lab model: LOOM_LAB_MODEL takes precedence, falls back to LOOM_MODEL
          lab-model      (or (.-LOOM_LAB_MODEL (.-env js/process))
                             (.-LOOM_MODEL (.-env js/process)))
          lab-env        (cond-> {:PORT (str container-port)}
                           api-key   (assoc :ANTHROPIC_API_KEY api-key)
                           api-base  (assoc :ANTHROPIC_API_BASE api-base)
                           lab-model (assoc :LOOM_MODEL lab-model))
          ;; Host port: offset from a base to avoid collisions across concurrent Labs
          host-port      (+ 18400 gen-num)]
      (-> (setup-lab-repo source-repo-path branch program-md :base-dir lab-base-dir)
          (.then (fn [result]
                   (if (:error result)
                     result
                     (let [lab-dir (:lab-dir result)
                           copy-result (copy-worker-js worker-path lab-dir)]
                       (if (:error copy-result)
                         copy-result
                         (let [publish-spec (str host-port ":" container-port)]
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
                                          ;; Start polling + hard timeout
                                          (let [cancel-poll (poll-lab-status
                                                             host-port
                                                             on-lab-status
                                                             on-lab-done)
                                                timeout-id (js/setTimeout
                                                            (fn []
                                                              (cancel-poll)
                                                              (on-timeout gen-num container-name)
                                                              (cleanup-lab container-name))
                                                            timeout-ms)]
                                            {:ok true
                                             :container-name container-name
                                             :container-id (:container-id run-result)
                                             :lab-dir lab-dir
                                             :branch branch
                                             :host-port host-port
                                             :timeout-id timeout-id
                                             :cancel-poll cancel-poll})))))))))))
          (.catch (fn [err]
                    {:error true :message (str "Spawn failed: " (.-message err))}))))))
