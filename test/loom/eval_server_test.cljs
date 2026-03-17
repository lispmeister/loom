(ns loom.eval-server-test
  (:require [cljs.test :refer [deftest is async testing]]
            [loom.lab.eval-server :as server]
            [loom.shared.eval-client :as client]))

;; -- Unit tests for truncate-value guards (no network needed) --

(deftest truncate-value-small-passes-through
  (testing "small values are returned unchanged"
    (is (= 42 (server/truncate-value 42)))
    (is (= "hello" (server/truncate-value "hello")))
    (is (= {:a 1 :b 2} (server/truncate-value {:a 1 :b 2})))
    (is (= [1 2 3] (server/truncate-value [1 2 3])))))

(deftest truncate-value-oversized-replaced
  (testing "a value whose serialization exceeds max-size is replaced with a truncation message"
    (let [big-str (apply str (repeat 100 "x"))
          result (server/truncate-value big-str :max-size 10)]
      (is (string? result))
      (is (.startsWith result "<value truncated:")))))

(deftest truncate-value-deep-structure-truncated
  (testing "a deeply nested value is truncated at max-depth"
    ;; Build a 5-level deep map: {:a {:a {:a {:a {:a 1}}}}}
    (let [deep {:a {:a {:a {:a {:a 1}}}}}
          result (server/truncate-value deep :max-depth 3)]
      ;; The result should be a map (not truncated at top), but
      ;; something inside should be a truncation sentinel string.
      (is (map? result))
      (let [serialized (pr-str result)]
        (is (.includes serialized "<truncated at depth"))))))

(deftest measure-depth-primitives
  (testing "primitives have depth 0"
    (is (= 0 (server/measure-depth 42)))
    (is (= 0 (server/measure-depth "hello")))
    (is (= 0 (server/measure-depth nil)))))

(deftest measure-depth-collections
  (testing "flat collections have depth 1"
    (is (= 1 (server/measure-depth [1 2 3])))
    (is (= 1 (server/measure-depth {:a 1}))))
  (testing "nested collections accumulate depth"
    (is (= 2 (server/measure-depth {:a [1 2 3]})))
    (is (= 3 (server/measure-depth {:a {:b [1]}})))))

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
