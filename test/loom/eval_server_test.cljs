(ns loom.eval-server-test
  (:require [cljs.test :refer [deftest is async testing]]
            [loom.lab.eval-server :as server]
            [loom.shared.eval-client :as client]))

(def cache-path ".shadow-cljs/builds/test/dev/ana/cljs/core.cljs.cache.transit.json")

(deftest eval-server-simple-arithmetic
  (testing "eval server returns correct result for (+ 1 2)"
    (async done
           (-> (server/start-server :port 0 :cache-path cache-path)
               (.then (fn [{:keys [server port]}]
                        (-> (client/eval-form "127.0.0.1" port "(+ 1 2)")
                            (.then (fn [response]
                                     (is (= :ok (:status response)))
                                     (is (= 3 (:value response)))
                                     (-> (server/stop-server server)
                                         (.then (fn [_] (done)))))))))))))

(deftest eval-server-invalid-form
  (testing "eval server returns error for invalid form"
    (async done
           (-> (server/start-server :port 0 :cache-path cache-path)
               (.then (fn [{:keys [server port]}]
                        (-> (client/eval-form "127.0.0.1" port "(def)")
                            (.then (fn [response]
                                     (is (= :error (:status response)))
                                     (is (string? (:message response)))
                                     (-> (server/stop-server server)
                                         (.then (fn [_] (done)))))))))))))

(deftest eval-server-malformed-edn
  (testing "eval server handles malformed EDN input gracefully"
    (async done
           (let [net (js/require "node:net")]
             (-> (server/start-server :port 0 :cache-path cache-path)
                 (.then (fn [{:keys [server port]}]
                          (let [socket (.createConnection net #js {:host "127.0.0.1" :port port})
                                buffer (atom "")]
                            (.on socket "data"
                                 (fn [data]
                                   (swap! buffer str (.toString data))
                                   (when (>= (.indexOf @buffer "\n") 0)
                                     (let [response (cljs.reader/read-string
                                                     (.substring @buffer 0 (.indexOf @buffer "\n")))]
                                       (.destroy socket)
                                       (is (= :error (:status response)))
                                       (is (string? (:message response)))
                                       (-> (server/stop-server server)
                                           (.then (fn [_] (done))))))))
                            (.on socket "connect"
                                 (fn []
                                   (.write socket "this is not valid edn {{{\n")))))))))))

(deftest eval-server-data-structures
  (testing "eval server handles data structure results"
    (async done
           (-> (server/start-server :port 0 :cache-path cache-path)
               (.then (fn [{:keys [server port]}]
                        (-> (client/eval-form "127.0.0.1" port "(into {} [[:a 1] [:b 2]])")
                            (.then (fn [response]
                                     (is (= :ok (:status response)))
                                     (is (= {:a 1 :b 2} (:value response)))
                                     (-> (server/stop-server server)
                                         (.then (fn [_] (done)))))))))))))
