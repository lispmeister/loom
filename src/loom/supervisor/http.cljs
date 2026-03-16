(ns loom.supervisor.http
  "Supervisor HTTP server — dashboard, stats, SSE logs, and Lab orchestration endpoints."
  (:require [clojure.string :as str]
            [loom.shared.http :as http]
            [loom.supervisor.git :as git]
            [loom.supervisor.generations :as gen]
            [loom.supervisor.lab :as lab]
            [loom.supervisor.container :as container]))

(def ^:private crypto (js/require "node:crypto"))

;; -- SSE log infrastructure --

(defonce ^:private sse-clients (atom #{}))

(defn emit-log
  "Emit a log event to all connected SSE clients."
  [event-name data]
  (doseq [send-fn @sse-clients]
    (http/send-sse-event send-fn event-name data)))

;; -- Lab lifecycle tracking --

;; Map of generation-number to {:timeout-id N :cancel-poll fn :lab-dir path} for active Labs.
(defonce lab-timeouts (atom {}))

(defn- cancel-lab-timeout
  "Cancel the timeout and polling for a generation. Removes it from the atom."
  [gen-num]
  (when-let [entry (get @lab-timeouts gen-num)]
    (when (:timeout-id entry) (lab/cancel-timeout (:timeout-id entry)))
    (when (:cancel-poll entry) ((:cancel-poll entry)))
    (swap! lab-timeouts dissoc gen-num)))

;; -- Dashboard HTML --

(defn- dashboard-html
  "Render the Supervisor HTML dashboard. Shows current generation count,
   links to JSON/SSE endpoints, and a table of all generations with outcome status."
  [config]
  (let [gens-path (:generations-path config)
        gens      (gen/read-generations gens-path)
        current   (if (empty? gens) 0 (apply max (map :generation gens)))]
    (str "<!DOCTYPE html>
<html>
<head><title>Loom Supervisor</title>
<style>
  body { font-family: monospace; background: #1a1a2e; color: #e0e0e0; max-width: 800px; margin: 40px auto; padding: 0 20px; }
  h1 { color: #00d4ff; }
  h2 { color: #7b68ee; margin-top: 2em; }
  a { color: #00d4ff; }
  table { border-collapse: collapse; width: 100%; margin-top: 1em; }
  th, td { border: 1px solid #333; padding: 6px 12px; text-align: left; }
  th { background: #16213e; }
  .in-progress { color: #ffd700; }
  .promoted { color: #00ff88; }
  .failed { color: #ff4444; }
  .timeout { color: #ff8800; }
</style>
</head>
<body>
<h1>Loom Supervisor</h1>
<p>Status: <strong>running</strong> | Current generation: <strong>" current "</strong> | Total: <strong>" (count gens) "</strong></p>
<p><a href=\"/stats\">Stats (JSON)</a> | <a href=\"/versions\">Versions (JSON)</a> | <a href=\"/logs\">Logs (SSE)</a></p>

<h2>Generations</h2>"
         (if (empty? gens)
           "<p>No generations yet.</p>"
           (str "<table><tr><th>Gen</th><th>Parent</th><th>Branch</th><th>Outcome</th><th>Created</th></tr>"
                (str/join
                 (map (fn [g]
                        (str "<tr><td>" (:generation g) "</td>"
                             "<td>" (:parent g) "</td>"
                             "<td>" (:branch g) "</td>"
                             "<td class=\"" (name (:outcome g)) "\">" (name (:outcome g)) "</td>"
                             "<td>" (:created g) "</td></tr>"))
                      gens))
                "</table>"))
         "
</body>
</html>")))

;; -- Helpers --

(defn- sha256-short
  "Return the first 12 hex chars of the SHA-256 hash of s."
  [s]
  (-> (.createHash crypto "sha256")
      (.update (or s ""))
      (.digest "hex")
      (.substring 0 12)))

(defn- find-generation
  "Find a generation record by number. Returns nil if not found."
  [config gen-num]
  (let [gens (gen/read-generations (:generations-path config))]
    (first (filter #(= gen-num (:generation %)) gens))))

;; -- Route handlers --

(defn- handle-dashboard
  "Serve the Supervisor HTML dashboard."
  [config _req]
  {:status  200
   :headers {"content-type" "text/html"}
   :body    (dashboard-html config)})

(defn- handle-stats
  "Return Supervisor stats as JSON: status, current generation, total count,
   uptime, and version."
  [state-atom config _req]
  (let [gens-path (:generations-path config)
        gens      (gen/read-generations gens-path)
        current   (if (empty? gens) 0 (apply max (map :generation gens)))
        uptime    (- (.now js/Date) (:started-at @state-atom))]
    (http/json-response 200
                        {:status             "running"
                         :current-generation  current
                         :total-generations   (count gens)
                         :uptime-ms           uptime
                         :version             "0.1.0"})))

(defn- handle-versions
  "Return the full generation history as a JSON array."
  [config _req]
  (let [gens (gen/read-generations (:generations-path config))]
    (http/json-response 200 gens)))

(defn- handle-logs
  "Open an SSE stream for real-time Supervisor events (spawn, promote, rollback,
   timeout). Registers the client; deregisters on disconnect."
  [_req]
  (http/sse-handler
   _req
   (fn [send-fn _close-fn on-close-fn]
     (swap! sse-clients conj send-fn)
     (http/send-sse-event send-fn "connected" {:msg "SSE stream connected"})
     (on-close-fn (fn [] (swap! sse-clients disj send-fn))))))

(def ^:private path-mod (js/require "node:path"))

;; -- Delegated helpers (implementations in supervisor/lab.cljs) --

(defn- cleanup-lab-workspace
  "Delegate workspace cleanup to lab.cljs, keeping the last 3 workspaces."
  [config _gen-num]
  (lab/cleanup-lab-workspace (:lab-base-dir config)))

(defn- programs-dir
  "Derive the programs/ directory path from the generations-path config."
  [config]
  (.join path-mod (.dirname path-mod (:generations-path config)) "programs"))

(defn- save-program-md
  "Save a copy of program.md as programs/gen-N.md for post-mortem reference."
  [config gen-num program-md]
  (lab/save-program-md (programs-dir config) gen-num program-md))

(defn- save-report
  "Write a gen-N-report.json with lifecycle metadata (timing, outcome, branch, etc.)."
  [config gen-num record outcome]
  (let [created   (:created record)
        completed (.toISOString (js/Date.))
        duration  (when created
                    (- (.getTime (js/Date. completed)) (.getTime (js/Date. created))))]
    (lab/save-generation-report
     (programs-dir config) gen-num
     {:generation      gen-num
      :outcome         (name outcome)
      :branch          (:branch record)
      :program-md-hash (:program-md-hash record)
      :created         created
      :completed       completed
      :duration-ms     duration
      :container-id    (:container-id record)})))

(defn- handle-spawn
  "Handle POST /spawn. Clones the repo, creates a lab branch, writes program.md,
   launches a Lab container, starts polling Lab /status, and sets a hard timeout.
   On success, records the generation as :in-progress. When the Supervisor's poll
   detects done/failed, it updates the generation, cancels the timeout, emits SSE,
   and stops the container."
  [_state-atom config req]
  (let [body       (js->clj (http/read-json-body req) :keywordize-keys true)
        program-md (:program_md body)
        parent-gen (or (:parent_generation body) 0)
        gens-path  (:generations-path config)
        repo-path  (:repo-path config)
        gen-num    (gen/next-generation-number gens-path)
        branch     (str "lab/gen-" gen-num)
        now        (.toISOString (js/Date.))
        hash       (sha256-short program-md)
        network    (:network config)
        on-timeout (fn [timed-out-gen container-name]
                     (let [completed (.toISOString (js/Date.))
                           record    (find-generation config timed-out-gen)]
                       (gen/update-generation gens-path timed-out-gen
                                              {:outcome :timeout :completed completed})
                       (when record
                         (save-report config timed-out-gen record :timeout))
                       (swap! lab-timeouts dissoc timed-out-gen)
                       (emit-log "timeout" {:generation timed-out-gen
                                            :status "timeout"
                                            :message (str "Lab " container-name " killed after timeout ("
                                                          (:lab-timeout-ms config) "ms)")})))
        on-lab-status (fn [status-map]
                        (emit-log "lab-status" {:generation gen-num
                                                :status (:status status-map)
                                                :progress (:progress status-map)}))
        on-lab-done (fn [final-status]
                      ;; Lab reported done or failed — cancel timeout, update gen,
                      ;; fetch branch into main repo, stop container
                      (let [entry (get @lab-timeouts gen-num)]
                        (cancel-lab-timeout gen-num)
                        (let [lab-status (:status final-status)
                              outcome    (if (= lab-status "done") :done :failed)
                              completed  (.toISOString (js/Date.))
                              record     (find-generation config gen-num)
                              container-name (str "lab-gen-" gen-num)
                              lab-dir    (:lab-dir entry)]
                          (gen/update-generation gens-path gen-num
                                                 {:outcome outcome :completed completed})
                          (when record
                            (save-report config gen-num record outcome))
                          ;; Fetch Lab branch into main repo so verify/promote can find it
                          (when (and lab-dir (= outcome :done))
                            (-> (git/fetch-branch repo-path lab-dir branch)
                                (.then (fn [result]
                                         (if (:ok result)
                                           (emit-log "fetch" {:generation gen-num :branch branch
                                                              :status "ok"})
                                           (emit-log "fetch" {:generation gen-num :branch branch
                                                              :status "error"
                                                              :error (:message result)}))))
                                (.catch (fn [err]
                                          (emit-log "fetch" {:generation gen-num :branch branch
                                                             :status "error"
                                                             :error (.-message err)})))))
                          (emit-log "lab-done" {:generation gen-num
                                                :status (name outcome)
                                                :progress (:progress final-status)
                                                :error (:error final-status)})
                          ;; Stop container (best-effort) but keep workspace for verify
                          (lab/cleanup-lab container-name))))]
    (save-program-md config gen-num program-md)
    (emit-log "spawn" {:generation gen-num :branch branch :status "starting"})
    (-> (lab/spawn-lab repo-path gen-num program-md
                       :network network
                       :image (or (:lab-image config) "loom-lab:latest")
                       :on-timeout on-timeout
                       :on-lab-status on-lab-status
                       :on-lab-done on-lab-done
                       :lab-base-dir (:lab-base-dir config)
                       :timeout-ms (:lab-timeout-ms config))
        (.then (fn [result]
                 (if (:error result)
                   (do
                     (gen/append-generation gens-path
                                            {:generation      gen-num
                                             :parent          parent-gen
                                             :branch          branch
                                             :program-md-hash hash
                                             :outcome         :failed
                                             :created         now
                                             :completed       (.toISOString (js/Date.))
                                             :container-id    ""})
                     (emit-log "spawn" {:generation gen-num :status "failed"
                                        :error (:message result)})
                     (http/json-response 500 {:error (:message result)}))
                   (do
                     ;; Track timeout + poll + lab-dir so callbacks can use them
                     (swap! lab-timeouts assoc gen-num
                            {:timeout-id (:timeout-id result)
                             :cancel-poll (:cancel-poll result)
                             :lab-dir (:lab-dir result)})
                     (gen/append-generation gens-path
                                            {:generation      gen-num
                                             :parent          parent-gen
                                             :branch          branch
                                             :program-md-hash hash
                                             :outcome         :in-progress
                                             :created         now
                                             :container-id    (or (:container-id result) "")})
                     (emit-log "spawn" {:generation gen-num :status "spawned"
                                        :container (:container-name result)
                                        :port (:host-port result)})
                     (http/json-response 200 {:generation     gen-num
                                              :branch         branch
                                              :container_name (:container-name result)
                                              :host_port      (:host-port result)
                                              :status         "spawned"}))))))))

(defn- handle-promote
  "Handle POST /promote. Cancels the lab timeout, merges the lab branch into
   master, tags it gen-N, deletes the branch, saves a report, and cleans up
   the workspace."
  [_state-atom config req]
  (let [body      (js->clj (http/read-json-body req) :keywordize-keys true)
        gen-num   (:generation body)
        gens-path (:generations-path config)
        repo-path (:repo-path config)
        record    (find-generation config gen-num)]
    ;; Cancel timeout before processing promotion
    (cancel-lab-timeout gen-num)
    (if (nil? record)
      (js/Promise.resolve
       (http/json-response 404 {:error (str "Generation " gen-num " not found")}))
      (let [branch (:branch record)]
        (-> (git/checkout repo-path "master")
            (.then (fn [result]
                     (if (:error result)
                       (http/json-response 500 {:error (:message result)})
                       (git/merge-branch repo-path branch))))
            (.then (fn [result]
                     (if (and (map? result) (:error result))
                       (http/json-response 500 {:error (:message result)})
                       (git/tag repo-path (str "gen-" gen-num)
                                :message (str "Promote generation " gen-num)))))
            (.then (fn [result]
                     (if (and (map? result) (:error result))
                       (http/json-response 500 {:error (:message result)})
                       (git/delete-branch repo-path branch))))
            (.then (fn [result]
                     (if (and (map? result) (:error result))
                       (http/json-response 500 {:error (:message result)})
                       (let [now (.toISOString (js/Date.))]
                         (gen/update-generation gens-path gen-num
                                                {:outcome :promoted :completed now})
                         (save-report config gen-num record :promoted)
                         (cleanup-lab-workspace config gen-num)
                         (emit-log "promote" {:generation gen-num})
                         (http/json-response 200 {:generation gen-num
                                                  :status     "promoted"}))))))))))

(defn- handle-rollback
  "Handle POST /rollback. Cancels the lab timeout, checks out master, deletes
   the lab branch, marks the generation as :failed, saves a report, and cleans
   up the workspace."
  [_state-atom config req]
  (let [body      (js->clj (http/read-json-body req) :keywordize-keys true)
        gen-num   (:generation body)
        gens-path (:generations-path config)
        repo-path (:repo-path config)
        record    (find-generation config gen-num)]
    ;; Cancel timeout before processing rollback
    (cancel-lab-timeout gen-num)
    (if (nil? record)
      (js/Promise.resolve
       (http/json-response 404 {:error (str "Generation " gen-num " not found")}))
      (let [branch (:branch record)]
        ;; Need to be on a different branch before deleting
        (-> (git/checkout repo-path "master")
            (.then (fn [_]
                     (git/delete-branch repo-path branch)))
            (.then (fn [result]
                     (if (:error result)
                       (http/json-response 500 {:error (:message result)})
                       (let [now (.toISOString (js/Date.))]
                         (gen/update-generation gens-path gen-num
                                                {:outcome :failed :completed now})
                         (save-report config gen-num record :failed)
                         (cleanup-lab-workspace config gen-num)
                         (emit-log "rollback" {:generation gen-num})
                         (http/json-response 200 {:generation gen-num
                                                  :status     "rolled-back"}))))))))))

;; -- Public API --

(defn create-supervisor-routes
  "Create the route map. config has :repo-path :generations-path etc."
  [state-atom config]
  {[:get "/"]         (partial handle-dashboard config)
   [:get "/stats"]    (partial handle-stats state-atom config)
   [:get "/versions"] (partial handle-versions config)
   [:get "/logs"]     (fn [req] (handle-logs req))
   [:post "/spawn"]   (partial handle-spawn state-atom config)
   [:post "/promote"] (partial handle-promote state-atom config)
   [:post "/rollback"] (partial handle-rollback state-atom config)})

(defn start-supervisor-server
  "Start the Supervisor HTTP server. Returns promise of server."
  [state-atom config & {:keys [port] :or {port 8400}}]
  (let [routes (create-supervisor-routes state-atom config)]
    (http/create-server routes :port port :host "0.0.0.0")))
