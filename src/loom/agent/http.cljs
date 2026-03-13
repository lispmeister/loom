(ns loom.agent.http
  "Prime agent HTTP server — dashboard, stats, logs, and chat input."
  (:require [loom.shared.http :as http]))

;; -- SSE client registry --

(defonce ^:private sse-clients (atom #{}))

(defn emit-log
  "Emit a log event to all connected SSE clients."
  [event-name data]
  (doseq [send-fn @sse-clients]
    (http/send-sse-event send-fn event-name data)))

;; -- Handlers --

(defn- dashboard-html [state]
  (let [{:keys [status version]} state]
    (str "<!DOCTYPE html>
<html lang=\"en\">
<head>
  <meta charset=\"utf-8\">
  <title>Loom Prime</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 640px; margin: 2rem auto; padding: 0 1rem; color: #222; }
    h1 { margin-bottom: 0.25rem; }
    .status { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 0.85rem; }
    .status-idle { background: #e0f0e0; color: #2a6e2a; }
    .status-working { background: #fff3cd; color: #856404; }
    nav { margin: 1rem 0; }
    nav a { margin-right: 1rem; }
    form { margin-top: 1.5rem; }
    input[type=text] { width: 70%; padding: 0.4rem; }
    button { padding: 0.4rem 1rem; }
  </style>
</head>
<body>
  <h1>Loom Prime</h1>
  <p>Version: " version
         " &mdash; <span class=\"status status-" (name status) "\">" (name status) "</span></p>
  <nav><a href=\"/logs\">Live Logs (SSE)</a> <a href=\"/stats\">Stats (JSON)</a></nav>
  <form method=\"POST\" action=\"/chat\">
    <input type=\"text\" name=\"message\" placeholder=\"Send a message...\" required>
    <button type=\"submit\">Send</button>
  </form>
  <script>
    document.querySelector('form').addEventListener('submit', function(e) {
      e.preventDefault();
      var msg = e.target.message.value;
      fetch('/chat', {method:'POST', headers:{'content-type':'application/json'}, body:JSON.stringify({message:msg})})
        .then(function(r){return r.json()})
        .then(function(d){e.target.message.value=''; console.log('sent',d)});
    });
  </script>
</body>
</html>")))

(defn- handle-dashboard [state-atom _req]
  {:status  200
   :headers {"content-type" "text/html"}
   :body    (dashboard-html @state-atom)})

(defn- handle-stats [state-atom _req]
  (let [{:keys [status messages-count tool-calls-count start-time version]} @state-atom
        uptime-ms (- (.now js/Date) start-time)]
    (http/json-response 200 {:status          (name status)
                             :uptime-ms       uptime-ms
                             :messages-count  messages-count
                             :tool-calls-count tool-calls-count
                             :version         version})))

(defn- handle-logs [_req]
  (http/sse-handler
   _req
   (fn [send-fn close-fn]
     (swap! sse-clients conj send-fn)
     (http/send-sse-event send-fn "connected" {:msg "SSE stream open"})
     ;; Remove client on disconnect — the underlying socket emits "close"
     ;; We don't have direct access to the raw socket here, so we wrap close-fn
     ;; to also deregister. The HTTP layer will call close when the client disconnects.
     ;; For now, clients accumulate until server restart. A production version
     ;; would hook into the response "close" event.
     )))

(defn- handle-chat [state-atom req]
  (let [body    (http/read-json-body req)
        message (.-message ^js body)]
    (emit-log "chat" {:message message})
    (http/json-response 200 {:status "received" :message message})))

;; -- Public API --

(defn create-prime-routes
  "Create the route map for the Prime HTTP server.
   state-atom holds {:status :messages-count :tool-calls-count :start-time :version ...}"
  [state-atom]
  {[:get "/"]      (partial handle-dashboard state-atom)
   [:get "/stats"] (partial handle-stats state-atom)
   [:get "/logs"]  handle-logs
   [:post "/chat"] (partial handle-chat state-atom)})

(defn start-prime-server
  "Start the Prime HTTP server. Returns promise of server."
  [state-atom & {:keys [port] :or {port 8401}}]
  (http/create-server (create-prime-routes state-atom) :port port))
