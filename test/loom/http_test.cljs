(ns loom.http-test
  (:require [cljs.test :refer [deftest is async testing]]
            [clojure.string :as str]
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

(defn- post-request [port path body]
  (http-request {:hostname "127.0.0.1"
                 :port     port
                 :path     path
                 :method   "POST"
                 :headers  {"content-type" "application/json"}
                 :body     body}))

(defn- sse-request
  "Make an HTTP request that collects data until the connection closes.
   Returns a promise of {:status :headers :body}."
  [port path]
  (js/Promise.
   (fn [resolve reject]
     (let [req (.request node-http
                         #js {:hostname "127.0.0.1" :port port :path path :method "GET"}
                         (fn [^js res]
                           (let [chunks (atom [])]
                             (.on res "data" (fn [^js chunk]
                                               (swap! chunks conj (.toString chunk "utf8"))))
                             (.on res "end"
                                  (fn []
                                    (resolve {:status  (.-statusCode res)
                                              :headers (js->clj (.-headers res))
                                              :body    (apply str @chunks)}))))))]
       (.on req "error" reject)
       (.end req)))))

;; All tests use port 0 (OS-assigned) to avoid conflicts.

(deftest test-basic-route
  (testing "GET route returns expected response"
    (async done
           (let [routes {[:get "/hello"] (fn [_req]
                                           {:status  200
                                            :headers {"content-type" "text/plain"}
                                            :body    "world"})}]
             (-> (http/create-server routes :port 0 :host "127.0.0.1")
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (get-request port "/hello")
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (is (= "world" (:body resp)))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (is false (str "Unexpected error: " err))
                                          (done))))))))))))

(deftest test-json-response
  (testing "json-response helper produces correct response map"
    (let [resp (http/json-response 201 {:id 1 :name "test"})]
      (is (= 201 (:status resp)))
      (is (= "application/json" (get-in resp [:headers "content-type"])))
      (let [parsed (js->clj (js/JSON.parse (:body resp)) :keywordize-keys true)]
        (is (= 1 (:id parsed)))
        (is (= "test" (:name parsed)))))))

(deftest test-404-unknown-route
  (testing "Unknown route returns 404"
    (async done
           (let [routes {[:get "/exists"] (fn [_req]
                                            {:status 200
                                             :headers {}
                                             :body "ok"})}]
             (-> (http/create-server routes :port 0 :host "127.0.0.1")
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (get-request port "/nope")
                                (.then (fn [resp]
                                         (is (= 404 (:status resp)))
                                         (is (= "Not Found" (:body resp)))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (is false (str "Unexpected error: " err))
                                          (done))))))))))))

(deftest test-post-body-parsing
  (testing "POST handler receives parsed body"
    (async done
           (let [received (atom nil)
                 routes   {[:post "/data"] (fn [req]
                                             (let [parsed (http/read-json-body req)]
                                               (reset! received parsed)
                                               (http/json-response 200 {:received true})))}]
             (-> (http/create-server routes :port 0 :host "127.0.0.1")
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (post-request port "/data" (js/JSON.stringify #js {:foo "bar"}))
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (is (some? @received))
                                         (is (= "bar" (.-foo ^js @received)))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (is false (str "Unexpected error: " err))
                                          (done))))))))))))

(deftest test-sse-connection
  (testing "SSE connection sends events to client"
    (async done
           (let [routes {[:get "/events"]
                         (fn [req]
                           (http/sse-handler
                            req
                            (fn [send-fn close-fn]
                              ;; Send one event then close
                              (http/send-sse-event send-fn "greeting" {:msg "hello"})
                              (close-fn))))}]
             (-> (http/create-server routes :port 0 :host "127.0.0.1")
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (sse-request port "/events")
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (is (str/includes?
                                              (get (:headers resp) "content-type")
                                              "text/event-stream"))
                                         (is (str/includes? (:body resp) "event: greeting"))
                                         (is (str/includes? (:body resp) "data: {:msg \"hello\"}"))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (is false (str "Unexpected error: " err))
                                          (done))))))))))))
