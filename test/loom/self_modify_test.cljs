(ns loom.self-modify-test
  "Tests for self-modify tools: spawn_lab (with built-in polling), promote, rollback.
   Uses a real HTTP server to mock Supervisor/Lab responses."
  (:require [cljs.test :refer [deftest async is testing]]
            [loom.shared.http :as http]
            [loom.agent.self-modify :as sm]))

;; ---------------------------------------------------------------------------
;; Helper: mock server with dynamic route handlers
;; ---------------------------------------------------------------------------

(defn- mock-server
  "Start a server with route handlers. Each handler is (fn [req] response-map).
   Returns promise of [server port]."
  [routes]
  (-> (http/create-server
       (into {}
             (map (fn [[route-key handler]]
                    [route-key
                     (fn [req]
                       (let [resp (handler req)]
                         {:status  (:status resp 200)
                          :headers {"content-type" "application/json"}
                          :body    (js/JSON.stringify (clj->js (:body resp)))}))])
                  routes))
       :port 0)
      (.then (fn [server]
               [server (http/server-port server)]))))

(defn- with-mock
  "Start mock, bind supervisor-url + fast polling, run body-fn, cleanup.
   body-fn receives [server port] and must return a promise."
  [routes body-fn]
  (let [original-url sm/supervisor-url
        original-poll sm/poll-interval-ms
        original-timeout sm/poll-timeout-ms]
    (-> (mock-server routes)
        (.then (fn [[server port]]
                 (set! sm/supervisor-url (str "http://localhost:" port))
                 (set! sm/poll-interval-ms 100)
                 (set! sm/poll-timeout-ms 2000)
                 (-> (body-fn server port)
                     (.then (fn [result]
                              (set! sm/supervisor-url original-url)
                              (set! sm/poll-interval-ms original-poll)
                              (set! sm/poll-timeout-ms original-timeout)
                              (http/stop-server server)
                              result))
                     (.catch (fn [err]
                               (set! sm/supervisor-url original-url)
                               (set! sm/poll-interval-ms original-poll)
                               (set! sm/poll-timeout-ms original-timeout)
                               (-> (http/stop-server server)
                                   (.then (fn [_] (throw err))))))))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest spawn-lab-polls-until-done-test
  (async done
         (let [poll-count (atom 0)]
           (-> (with-mock
                 {[:post "/spawn"]
                  (fn [_req]
                    ;; Return the mock's own port so polling stays on the same server.
                    ;; We don't know the port yet, but supervisor-url is already set
                    ;; by with-mock, so we extract it.
                    (let [port (js/parseInt (last (re-find #":(\d+)$" sm/supervisor-url)))]
                      {:body {:generation 1
                              :branch "lab/gen-1"
                              :container_name "lab-gen-1"
                              :host_port port
                              :status "spawned"}}))
                  [:get "/status"]
                  (fn [_req]
                    (let [n (swap! poll-count inc)]
                      (if (>= n 2)
                        {:body {:status "done" :progress "Task completed" :error nil}}
                        {:body {:status "running" :progress "Working..." :error nil}})))}
                 (fn [_server _port]
                   (-> (sm/spawn-lab {:program_md "# Test\nDo something"})
                       (.then (fn [result]
                                (testing "spawn_lab blocks until Lab done"
                                  (is (re-find #"Generation: 1" result))
                                  (is (re-find #"Branch: lab/gen-1" result))
                                  (is (re-find #"Lab completed successfully" result))
                                  (is (>= @poll-count 2))))))))
               (.then (fn [_] (done)))
               (.catch (fn [err]
                         (is false (str "Unexpected error: " err))
                         (done)))))))

(deftest spawn-lab-reports-failure-test
  (async done
         (-> (with-mock
               {[:post "/spawn"]
                (fn [_req]
                  (let [port (js/parseInt (last (re-find #":(\d+)$" sm/supervisor-url)))]
                    {:body {:generation 2
                            :branch "lab/gen-2"
                            :container_name "lab-gen-2"
                            :host_port port
                            :status "spawned"}}))
                [:get "/status"]
                (fn [_req]
                  {:body {:status "failed" :progress "" :error "Task crashed"}})}
               (fn [_server _port]
                 (-> (sm/spawn-lab {:program_md "# Fail test"})
                     (.then (fn [result]
                              (testing "spawn_lab reports failure"
                                (is (re-find #"Lab failed" result))
                                (is (re-find #"Task crashed" result))))))))
             (.then (fn [_] (done)))
             (.catch (fn [err]
                       (is false (str "Unexpected error: " err))
                       (done))))))

(deftest spawn-lab-timeout-test
  (async done
         (-> (with-mock
               {[:post "/spawn"]
                (fn [_req]
                  (let [port (js/parseInt (last (re-find #":(\d+)$" sm/supervisor-url)))]
                    {:body {:generation 3
                            :branch "lab/gen-3"
                            :container_name "lab-gen-3"
                            :host_port port
                            :status "spawned"}}))
                [:get "/status"]
                (fn [_req]
                  ;; Always return running — should trigger poll timeout
                  {:body {:status "running" :progress "Still going..." :error nil}})}
               (fn [_server _port]
                 (-> (sm/spawn-lab {:program_md "# Timeout test"})
                     (.then (fn [result]
                              (testing "spawn_lab reports timeout"
                                (is (re-find #"polling timed out" result))))))))
             (.then (fn [_] (done)))
             (.catch (fn [err]
                       (is false (str "Unexpected error: " err))
                       (done))))))

(deftest promote-generation-test
  (async done
         (-> (with-mock
               {[:post "/promote"]
                (fn [_req]
                  {:body {:generation 1 :status "promoted"}})}
               (fn [_server _port]
                 (-> (sm/promote-generation {:generation 1})
                     (.then (fn [result]
                              (testing "promote returns success message"
                                (is (re-find #"promoted successfully" result))
                                (is (re-find #"Generation 1" result))))))))
             (.then (fn [_] (done)))
             (.catch (fn [err]
                       (is false (str "Unexpected error: " err))
                       (done))))))

(deftest rollback-generation-test
  (async done
         (-> (with-mock
               {[:post "/rollback"]
                (fn [_req]
                  {:body {:generation 1 :status "rolled-back"}})}
               (fn [_server _port]
                 (-> (sm/rollback-generation {:generation 1})
                     (.then (fn [result]
                              (testing "rollback returns success message"
                                (is (re-find #"rolled back" result))
                                (is (re-find #"Generation 1" result))))))))
             (.then (fn [_] (done)))
             (.catch (fn [err]
                       (is false (str "Unexpected error: " err))
                       (done))))))
