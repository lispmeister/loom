(ns loom.self-modify-test
  "Tests for self-modify tools: spawn_lab, check_lab_status, promote, rollback.
   Uses a real HTTP server to mock Supervisor/Lab responses."
  (:require [cljs.test :refer [deftest async is testing]]
            [loom.shared.http :as http]
            [loom.agent.self-modify :as sm]))

;; ---------------------------------------------------------------------------
;; Helper: mock server that returns canned JSON responses
;; ---------------------------------------------------------------------------

(defn- mock-server
  "Start a server that responds with canned data based on path.
   routes: {\"POST /spawn\" {:status 200 :body {...}}, ...}
   Returns promise of [server port]."
  [routes]
  (let [route-map (atom routes)]
    (-> (http/create-server
         (into {}
               (map (fn [[[method path] response]]
                      [[method path]
                       (fn [_req]
                         {:status  (:status response 200)
                          :headers {"content-type" "application/json"}
                          :body    (js/JSON.stringify (clj->js (:body response)))})])
                    @route-map))
         :port 0)
        (.then (fn [server]
                 [server (http/server-port server)])))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest spawn-lab-test
  (async done
         (-> (mock-server
              {[:post "/spawn"] {:body {:generation 1
                                        :branch "lab/gen-1"
                                        :container-name "lab-gen-1"
                                        :host-port 8402
                                        :status "spawned"}}})
             (.then (fn [[server port]]
                 ;; Temporarily override the supervisor URL
                      (let [original-url sm/supervisor-url]
                        (set! sm/supervisor-url (str "http://localhost:" port))
                        (-> (sm/spawn-lab {:program_md "# Test task\nDo something"})
                            (.then (fn [result]
                                     (testing "spawn returns formatted string"
                                       (is (re-find #"Lab spawned successfully" result))
                                       (is (re-find #"Generation: 1" result))
                                       (is (re-find #"Branch: lab/gen-1" result)))))
                            (.then (fn [_]
                                     (set! sm/supervisor-url original-url)
                                     (http/stop-server server)))
                            (.then (fn [_] (done)))
                            (.catch (fn [err]
                                      (set! sm/supervisor-url original-url)
                                      (-> (http/stop-server server)
                                          (.then (fn [_]
                                                   (is false (str "Unexpected error: " err))
                                                   (done))))))))))
             (.catch (fn [err]
                       (is false (str "Server start error: " err))
                       (done))))))

(deftest check-lab-status-test
  (async done
         (-> (mock-server
              {[:get "/status"] {:body {:status "done"
                                        :tests {:passed 5 :failed 0}
                                        :progress 100}}})
             (.then (fn [[server port]]
                      (-> (sm/check-lab-status {:url (str "http://localhost:" port "/status")})
                          (.then (fn [result]
                                   (testing "status returns formatted output"
                                     (is (re-find #"Lab status:" result))
                                     (is (re-find #"done" result)))))
                          (.then (fn [_] (http/stop-server server)))
                          (.then (fn [_] (done)))
                          (.catch (fn [err]
                                    (-> (http/stop-server server)
                                        (.then (fn [_]
                                                 (is false (str "Unexpected error: " err))
                                                 (done)))))))))
             (.catch (fn [err]
                       (is false (str "Server start error: " err))
                       (done))))))

(deftest check-lab-status-retry-test
  (async done
    ;; Test that check_lab_status retries on connection refused
    ;; Use a port that nothing is listening on
         (-> (sm/check-lab-status {:url "http://localhost:19999/status"})
             (.then (fn [result]
                      (testing "returns error after retries exhausted"
                        (is (re-find #"Error checking Lab status:" result)))
                      (done)))
             (.catch (fn [err]
                       (is false (str "Unexpected error: " err))
                       (done))))))

(deftest promote-generation-test
  (async done
         (-> (mock-server
              {[:post "/promote"] {:body {:generation 1 :status "promoted"}}})
             (.then (fn [[server port]]
                      (let [original-url sm/supervisor-url]
                        (set! sm/supervisor-url (str "http://localhost:" port))
                        (-> (sm/promote-generation {:generation 1})
                            (.then (fn [result]
                                     (testing "promote returns success message"
                                       (is (re-find #"promoted successfully" result))
                                       (is (re-find #"Generation 1" result)))))
                            (.then (fn [_]
                                     (set! sm/supervisor-url original-url)
                                     (http/stop-server server)))
                            (.then (fn [_] (done)))
                            (.catch (fn [err]
                                      (set! sm/supervisor-url original-url)
                                      (-> (http/stop-server server)
                                          (.then (fn [_]
                                                   (is false (str "Unexpected error: " err))
                                                   (done))))))))))
             (.catch (fn [err]
                       (is false (str "Server start error: " err))
                       (done))))))

(deftest rollback-generation-test
  (async done
         (-> (mock-server
              {[:post "/rollback"] {:body {:generation 1 :status "rolled-back"}}})
             (.then (fn [[server port]]
                      (let [original-url sm/supervisor-url]
                        (set! sm/supervisor-url (str "http://localhost:" port))
                        (-> (sm/rollback-generation {:generation 1})
                            (.then (fn [result]
                                     (testing "rollback returns success message"
                                       (is (re-find #"rolled back" result))
                                       (is (re-find #"Generation 1" result)))))
                            (.then (fn [_]
                                     (set! sm/supervisor-url original-url)
                                     (http/stop-server server)))
                            (.then (fn [_] (done)))
                            (.catch (fn [err]
                                      (set! sm/supervisor-url original-url)
                                      (-> (http/stop-server server)
                                          (.then (fn [_]
                                                   (is false (str "Unexpected error: " err))
                                                   (done))))))))))
             (.catch (fn [err]
                       (is false (str "Server start error: " err))
                       (done))))))
