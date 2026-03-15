(ns loom.shared.http
  "Lightweight HTTP server for Loom components.
   Routes are [method path] -> handler-fn maps.
   Handlers receive request maps and return response maps (or promises of them)."
  (:require [clojure.string :as str]))

(def ^:private node-http (js/require "node:http"))

;; -- JSON helpers --

(defn json-response
  "Create a response map with JSON content-type and stringified body."
  [status data]
  {:status  status
   :headers {"content-type" "application/json"}
   :body    (js/JSON.stringify (clj->js data))})

(defn read-json-body
  "Parse the request body as JSON. Returns a JS object."
  [req]
  (js/JSON.parse (:body req)))

;; -- SSE support --

(defn send-sse-event
  "Format and send an SSE event via send-fn.
   Data will be pr-str'd if not a string."
  [send-fn event-name data]
  (let [data-str (if (string? data) data (pr-str data))]
    (send-fn (str "event: " event-name "\ndata: " data-str "\n\n"))))

(defn sse-handler
  "Create an SSE response. Calls (on-connect send-fn close-fn) where
   send-fn pushes a raw string to the client and close-fn ends the stream.
   Returns :sse to signal the router to skip normal response handling."
  [_req on-connect]
  {:status  :sse
   :setup-fn on-connect})

;; -- Internal helpers --

(defn- parse-query-params
  "Parse a query string into a map. Returns {} for nil/empty input."
  [search-str]
  (if (or (nil? search-str) (str/blank? search-str))
    {}
    (let [params (js/URLSearchParams. search-str)]
      (persistent!
       (reduce (fn [acc key]
                 (assoc! acc key (.get params key)))
               (transient {})
               (es6-iterator-seq (.keys params)))))))

(defn- read-body
  "Read the full request body as a string. Returns a promise."
  [^js req]
  (js/Promise.
   (fn [resolve _reject]
     (let [chunks (atom [])]
       (.on req "data" (fn [^js chunk] (swap! chunks conj (.toString chunk "utf8"))))
       (.on req "end" (fn [] (resolve (str/join @chunks))))))))

(defn- build-request-map
  "Build a request map from a Node.js IncomingMessage and parsed body."
  [req body]
  (let [url-obj (js/URL. (.-url req) (str "http://" (or (.-host (.-headers req))
                                                        "localhost")))]
    {:method       (keyword (str/lower-case (.-method req)))
     :path         (.-pathname url-obj)
     :headers      (js->clj (.-headers req))
     :query-params (parse-query-params (.-search url-obj))
     :body         body}))

(defn- send-response
  "Write a response map to a Node.js ServerResponse."
  [^js res response-map]
  (let [{:keys [status headers body]} response-map
        status (or status 200)]
    (doseq [[k v] headers]
      (.setHeader res k v))
    (.writeHead res status)
    (.end res (or body ""))))

(defn- setup-sse
  "Set up an SSE connection on the raw Node.js response object.
   setup-fn receives (send-fn close-fn on-close-fn) where on-close-fn
   registers a callback for when the client disconnects."
  [^js res setup-fn]
  (.writeHead res 200
              #js {"content-type"  "text/event-stream"
                   "cache-control" "no-cache"
                   "connection"    "keep-alive"})
  (let [send-fn    (fn [s] (.write res s))
        close-fn   (fn [] (.end res))
        on-close-fn (fn [cb] (.on res "close" cb))]
    (setup-fn send-fn close-fn on-close-fn)))

(defn- handle-request
  "Route and handle a single HTTP request."
  [routes req res]
  (-> (let [needs-body? (= (str/lower-case (.-method req)) "post")
            body-p      (if needs-body? (read-body req) (js/Promise.resolve nil))]
        (-> body-p
            (.then (fn [body]
                     (let [req-map   (build-request-map req body)
                           route-key [(:method req-map) (:path req-map)]
                           handler   (get routes route-key)]
                       (if (nil? handler)
                         {:status 404
                          :headers {"content-type" "text/plain"}
                          :body "Not Found"}
                         (handler req-map)))))
            (.then (fn [response]
                     (cond
                       ;; SSE: handler returned our sse-handler marker
                       (and (map? response) (= :sse (:status response)))
                       (setup-sse res (:setup-fn response))

                       ;; Normal response map
                       (map? response)
                       (send-response res response)

                       :else
                       (send-response res {:status 500
                                           :headers {"content-type" "text/plain"}
                                           :body "Invalid handler response"}))))))
      (.catch (fn [err]
                (send-response res {:status  500
                                    :headers {"content-type" "text/plain"}
                                    :body    (str "Internal Server Error: "
                                                  (or (.-message err) (str err)))})))))

;; -- Public API --

(defn create-server
  "Create and start an HTTP server with the given route map.
   Routes is a map of [method path] -> handler-fn.
   Returns a promise that resolves to the server instance."
  [routes & {:keys [port host] :or {port 8400 host "0.0.0.0"}}]
  (js/Promise.
   (fn [resolve reject]
     (let [server (.createServer node-http
                                 (fn [req res]
                                   (handle-request routes req res)))]
       (.on server "error" reject)
       (.listen server port host
                (fn []
                  (.removeListener server "error" reject)
                  (resolve server)))))))

(defn server-port
  "Return the port a server is actually listening on.
   Useful when started with port 0 (OS-assigned)."
  [server]
  (.-port (.address server)))

(defn stop-server
  "Stop the HTTP server. Returns a promise."
  [server]
  (js/Promise.
   (fn [resolve reject]
     (.close server (fn [err]
                      (if err (reject err) (resolve nil)))))))
