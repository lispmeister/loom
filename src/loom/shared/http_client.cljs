(ns loom.shared.http-client
  "Simple HTTP client for agent-to-service calls (Supervisor, Lab).
   Uses node:http since all calls are local/container-to-container."
  (:require ["node:http" :as http]))

(defn request
  "Make an HTTP request. Returns promise of {:status N :body <parsed-json-or-string>}
   or {:error true :message \"...\"}. Timeout in ms (default 10000)."
  [{:keys [method url body headers timeout]
    :or {method "GET" headers {} timeout 10000}}]
  (js/Promise.
   (fn [resolve _reject]
     (let [parsed   (js/URL. url)
           options  #js {:hostname (.-hostname parsed)
                         :port     (.-port parsed)
                         :path     (str (.-pathname parsed) (.-search parsed))
                         :method   method
                         :headers  (clj->js (merge {"content-type" "application/json"} headers))
                         :timeout  timeout}
           req (.request http options
                         (fn [^js res]
                           (let [chunks #js []]
                             (.on res "data" (fn [chunk] (.push chunks chunk)))
                             (.on res "end"
                                  (fn []
                                    (let [raw (.join chunks "")
                                          parsed-body (try (js->clj (js/JSON.parse raw)
                                                                    :keywordize-keys true)
                                                           (catch :default _ raw))]
                                      (resolve {:status (.-statusCode res)
                                                :body   parsed-body})))))))]
       (.on req "timeout" (fn [] (.destroy req)))
       (.on req "error"
            (fn [err]
              (resolve {:error true :message (.-message err)})))
       (when body
         (.write req (if (string? body) body (js/JSON.stringify (clj->js body)))))
       (.end req)))))

(defn get-json
  "GET a URL, parse JSON response. Returns promise of parsed body or {:error ...}."
  [url & {:keys [timeout] :or {timeout 5000}}]
  (-> (request {:method "GET" :url url :timeout timeout})
      (.then (fn [result]
               (if (:error result)
                 result
                 (:body result))))))

(defn post-json
  "POST JSON to a URL. Returns promise of parsed response body or {:error ...}."
  [url data & {:keys [timeout] :or {timeout 10000}}]
  (-> (request {:method "POST" :url url :body data :timeout timeout})
      (.then (fn [result]
               (if (:error result)
                 result
                 (:body result))))))
