(ns loom.http-client-test
  "Tests for the shared HTTP client."
  (:require [cljs.test :refer [deftest async is testing]]
            [loom.shared.http :as http]
            [loom.shared.http-client :as client]))

(deftest get-json-test
  (async done
         (let [routes {[:get "/data"] (fn [_req]
                                        (http/json-response 200 {:answer 42}))}]
           (-> (http/create-server routes :port 0)
               (.then (fn [server]
                        (let [port (http/server-port server)]
                          (-> (client/get-json (str "http://localhost:" port "/data"))
                              (.then (fn [result]
                                       (testing "parses JSON response"
                                         (is (= 42 (:answer result))))))
                              (.then (fn [_] (http/stop-server server)))
                              (.then (fn [_] (done)))
                              (.catch (fn [err]
                                        (-> (http/stop-server server)
                                            (.then (fn [_]
                                                     (is false (str "Error: " err))
                                                     (done))))))))))
               (.catch (fn [err]
                         (is false (str "Server error: " err))
                         (done)))))))

(deftest post-json-test
  (async done
         (let [received (atom nil)
               routes {[:post "/submit"] (fn [req]
                                           (let [body (js->clj (http/read-json-body req)
                                                               :keywordize-keys true)]
                                             (reset! received body)
                                             (http/json-response 200 {:ok true})))}]
           (-> (http/create-server routes :port 0)
               (.then (fn [server]
                        (let [port (http/server-port server)]
                          (-> (client/post-json (str "http://localhost:" port "/submit")
                                                {:name "test" :value 123})
                              (.then (fn [result]
                                       (testing "sends and receives JSON"
                                         (is (= true (:ok result))))))
                              (.then (fn [_] (http/stop-server server)))
                              (.then (fn [_] (done)))
                              (.catch (fn [err]
                                        (-> (http/stop-server server)
                                            (.then (fn [_]
                                                     (is false (str "Error: " err))
                                                     (done))))))))))
               (.catch (fn [err]
                         (is false (str "Server error: " err))
                         (done)))))))

(deftest connection-refused-test
  (async done
         (-> (client/get-json "http://localhost:19998/nope" :timeout 1000)
             (.then (fn [result]
                      (testing "returns error on connection refused"
                        (is (:error result))
                        (is (string? (:message result))))
                      (done)))
             (.catch (fn [err]
                       (is false (str "Unexpected: " err))
                       (done))))))
