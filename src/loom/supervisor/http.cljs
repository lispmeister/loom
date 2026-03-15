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

;; -- Lab timeout tracking --

;; Map of generation-number to timeout-id for active Lab containers.
(defonce lab-timeouts (atom {}))

(defn- cancel-lab-timeout
  "Cancel the timeout for a generation, if one exists. Removes it from the atom."
  [gen-num]
  (when-let [timeout-id (get @lab-timeouts gen-num)]
    (lab/cancel-timeout timeout-id)
    (swap! lab-timeouts dissoc gen-num)))

;; -- Dashboard HTML --

(defn- dashboard-html [config]
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

(defn- handle-dashboard [config _req]
  {:status  200
   :headers {"content-type" "text/html"}
   :body    (dashboard-html config)})

(defn- handle-stats [state-atom config _req]
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

(defn- handle-versions [config _req]
  (let [gens (gen/read-generations (:generations-path config))]
    (http/json-response 200 gens)))

(defn- handle-logs [_req]
  (http/sse-handler
   _req
   (fn [send-fn _close-fn]
     (swap! sse-clients conj send-fn)
     (http/send-sse-event send-fn "connected" {:msg "SSE stream connected"}))))

(def ^:private fs-mod (js/require "node:fs"))
(def ^:private path-mod (js/require "node:path"))

(defn- save-program-md
  "Save program.md to tmp/programs/gen-N.md for reference."
  [config gen-num program-md]
  (let [programs-dir (.join path-mod (.dirname path-mod (:generations-path config)) "programs")]
    (when-not (.existsSync fs-mod programs-dir)
      (.mkdirSync fs-mod programs-dir #js {:recursive true}))
    (.writeFileSync fs-mod
                    (.join path-mod programs-dir (str "gen-" gen-num ".md"))
                    program-md "utf8")))

(defn- handle-spawn [_state-atom config req]
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
        on-timeout (fn [timed-out-gen _container-name]
                     (let [completed (.toISOString (js/Date.))]
                       (gen/update-generation gens-path timed-out-gen
                                              {:outcome :timeout :completed completed})
                       (swap! lab-timeouts dissoc timed-out-gen)
                       (emit-log "timeout" {:generation timed-out-gen
                                            :status "timeout"
                                            :message "Lab killed after 5 minute timeout"})))]
    (save-program-md config gen-num program-md)
    (emit-log "spawn" {:generation gen-num :branch branch :status "starting"})
    (-> (lab/spawn-lab repo-path gen-num program-md
                       :network network
                       :image (or (:lab-image config) "loom-lab:latest")
                       :on-timeout on-timeout
                       :lab-base-dir (:lab-base-dir config))
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
                     ;; Track the timeout so promote/rollback can cancel it
                     (swap! lab-timeouts assoc gen-num (:timeout-id result))
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

(defn- handle-promote [_state-atom config req]
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
                         (emit-log "promote" {:generation gen-num})
                         (http/json-response 200 {:generation gen-num
                                                  :status     "promoted"}))))))))))

(defn- handle-rollback [_state-atom config req]
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
