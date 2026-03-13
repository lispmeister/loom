(ns loom.agent-http-test
  (:require [cljs.test :refer [deftest is async testing]]
            [clojure.string :as str]
            [loom.agent.http :as agent-http]
            [loom.shared.http :as http]))

(def node-http (js/require "node:http"))

;; -- Test helpers --

(defn- http-request
  "Make an HTTP request. Returns a promise of {:status :headers :body}."
  [opts]
  (js/Promise.
   (fn [resolve reject]
     (let [req (.request node-http
                         (clj->js opts)
                         (fn [^js res]
                           (let [chunks (atom [])]
                             (.on res "data" (fn [^js chunk] (swap! chunks conj (.toString chunk "utf8"))))
                             (.on res "end"
                                  (fn []
                                    (resolve {:status  (.-statusCode res)
                                              :headers (js->clj (.-headers res))
                                              :body    (apply str @chunks)}))))))]
       (.on req "error" reject)
       (when-let [body (:body opts)]
         (.write req body))
       (.end req)))))

(defn- get-request [port path]
  (http-request {:hostname "127.0.0.1" :port port :path path :method "GET"}))

(defn- post-json [port path data]
  (http-request {:hostname "127.0.0.1"
                 :port     port
                 :path     path
                 :method   "POST"
                 :headers  {"content-type" "application/json"}
                 :body     (js/JSON.stringify (clj->js data))}))

(defn- sse-read-first-event
  "Connect to an SSE endpoint, read until we get the first complete event,
   then abort the connection. Returns a promise of {:status :headers :body}."
  [port path]
  (js/Promise.
   (fn [resolve reject]
     (let [req (.request node-http
                         #js {:hostname "127.0.0.1" :port port :path path :method "GET"}
                         (fn [^js res]
                           (let [chunks (atom [])]
                             (.on res "data"
                                  (fn [^js chunk]
                                    (let [text (.toString chunk "utf8")]
                                      (swap! chunks conj text)
                                      ;; Once we have a double newline, we got a complete event
                                      (let [body (apply str @chunks)]
                                        (when (str/includes? body "\n\n")
                                          ;; Got at least one complete event, abort
                                          (.destroy res)
                                          (resolve {:status  (.-statusCode res)
                                                    :headers (js->clj (.-headers res))
                                                    :body    body})))))))))]
       (.on req "error" reject)
       (.end req)))))

(defn- make-state-atom []
  (atom {:status           :idle
         :messages-count   5
         :tool-calls-count 3
         :start-time       (.now js/Date)
         :version          "0.1.0"}))

;; -- Tests (all use port 0 to avoid conflicts) --

(deftest test-stats-endpoint
  (testing "GET /stats returns valid JSON with expected fields"
    (async done
           (let [state (make-state-atom)]
             (-> (agent-http/start-prime-server state :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (get-request port "/stats")
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (let [body (js->clj (js/JSON.parse (:body resp)) :keywordize-keys true)]
                                           (is (= "idle" (:status body)))
                                           (is (number? (:uptime-ms body)))
                                           (is (= 5 (:messages-count body)))
                                           (is (= 3 (:tool-calls-count body)))
                                           (is (= "0.1.0" (:version body))))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (is false (str "Unexpected error: " err))
                                          (done))))))))))))

(deftest test-chat-endpoint
  (testing "POST /chat accepts message and returns acknowledgment"
    (async done
           (let [state (make-state-atom)]
             (-> (agent-http/start-prime-server state :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (post-json port "/chat" {:message "hello agent"})
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (let [body (js->clj (js/JSON.parse (:body resp)) :keywordize-keys true)]
                                           (is (= "received" (:status body)))
                                           (is (= "hello agent" (:message body))))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (is false (str "Unexpected error: " err))
                                          (done))))))))))))

(deftest test-dashboard-endpoint
  (testing "GET / returns HTML"
    (async done
           (let [state (make-state-atom)]
             (-> (agent-http/start-prime-server state :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (get-request port "/")
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (is (str/includes? (get (:headers resp) "content-type") "text/html"))
                                         (is (str/includes? (:body resp) "Loom Prime"))
                                         (is (str/includes? (:body resp) "idle"))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (is false (str "Unexpected error: " err))
                                          (done))))))))))))

(deftest test-logs-sse-endpoint
  (testing "GET /logs opens SSE connection and receives connected event"
    (async done
           (let [state (make-state-atom)]
             (-> (agent-http/start-prime-server state :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (sse-read-first-event port "/logs")
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (is (str/includes?
                                              (get (:headers resp) "content-type")
                                              "text/event-stream"))
                                         (is (str/includes? (:body resp) "event: connected"))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (is false (str "Unexpected error: " err))
                                          (done))))))))))))
