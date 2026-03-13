(ns loom.container-test
  (:require [cljs.test :refer [deftest is async testing]]
            [loom.supervisor.container :as c]
            [loom.shared.eval-client :as eval-client]))

(def ^:private test-network "loom-test-net")

(defn- skip-unless-container-available
  "Run test-fn only if container CLI is available, otherwise skip.
   test-fn receives `done` callback."
  [test-fn done]
  (-> (c/cli-available?)
      (.then (fn [available?]
               (if available?
                 (test-fn done)
                 (do
                   (js/console.log "SKIP: container CLI not available")
                   (done)))))
      (.catch (fn [_]
                (js/console.log "SKIP: container CLI not available (error)")
                (done)))))

;; -- Tests --

(deftest cli-available-returns-boolean
  (testing "cli-available? returns a boolean"
    (async done
           (-> (c/cli-available?)
               (.then (fn [result]
                        (is (boolean? result))
                        (done)))
               (.catch (fn [_]
                         (is false "cli-available? should never reject")
                         (done)))))))

(deftest network-create-test
  (testing "network-create creates a network (skips if CLI unavailable)"
    (async done
           (skip-unless-container-available
            (fn [done]
              ;; Delete first to make test idempotent
              (-> (c/network-delete test-network)
                  (.then (fn [_] (c/network-create test-network)))
                  (.then (fn [result]
                           (is (:ok result) (str "network-create failed: " (:message result)))
                           (done)))
                  (.catch (fn [e]
                            (is false (str "unexpected rejection: " e))
                            (done)))))
            done))))

(deftest network-exists-test
  (testing "network-exists? finds the test network (skips if CLI unavailable)"
    (async done
           (skip-unless-container-available
            (fn [done]
              ;; Ensure network exists first, then check
              (-> (c/network-ensure test-network)
                  (.then (fn [_] (c/network-exists? test-network)))
                  (.then (fn [exists?]
                           (is (true? exists?) "test network should exist")
                           (done)))
                  (.catch (fn [e]
                            (is false (str "unexpected rejection: " e))
                            (done)))))
            done))))

(deftest network-ensure-idempotent-test
  (testing "network-ensure is idempotent (skips if CLI unavailable)"
    (async done
           (skip-unless-container-available
            (fn [done]
              (-> (c/network-ensure test-network)
                  (.then (fn [result]
                           (is (:ok result))
                           ;; Call again — should still succeed
                           (c/network-ensure test-network)))
                  (.then (fn [result]
                           (is (:ok result) "second ensure should also succeed")
                           (done)))
                  (.catch (fn [e]
                            (is false (str "unexpected rejection: " e))
                            (done)))))
            done))))

;; -- Lab image tests --

(def ^:private lab-container-name "loom-lab-test-eval")

(defn- delay-ms
  "Return a promise that resolves after ms milliseconds."
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) ms))))

(deftest lab-image-boot-and-eval
  (testing "loom-lab container boots eval server and evaluates (+ 1 2) (skips if CLI unavailable)"
    (async done
           (skip-unless-container-available
            (fn [done]
              (let [cname (str "loom-lab-test-" (rand-int 100000))
                    host-port (+ 19000 (rand-int 1000))
                    publish-spec (str host-port ":8402")]
                (-> (c/run cname "loom-lab:latest" nil
                           :publish publish-spec :detach true)
                    (.then
                     (fn [result]
                       (if (:error result)
                         (do (is false (str "container run failed: " (:message result)))
                             (js/Promise.resolve nil))
                         (-> (delay-ms 3000)
                             (.then (fn [_]
                                      (eval-client/eval-form "127.0.0.1" host-port "(+ 1 2)")))
                             (.then (fn [response]
                                      (is (= :ok (:status response)))
                                      (is (= 3 (:value response)))))))))
                    (.then (fn [_] (c/stop cname)))
                    (.then (fn [_] (c/destroy cname)))
                    (.then (fn [_] (done)))
                    (.catch (fn [err]
                              (-> (c/stop cname)
                                  (.then (fn [_] (c/destroy cname)))
                                  (.catch (fn [_] nil))
                                  (.then (fn [_]
                                           (is false (str "Unexpected: " err))
                                           (done)))))))))
            done))))

;; Cleanup: remove test network after tests
(deftest network-cleanup
  (testing "cleanup: remove test network (skips if CLI unavailable)"
    (async done
           (skip-unless-container-available
            (fn [done]
              (-> (c/network-delete test-network)
                  (.then (fn [_result]
                           ;; Don't assert — cleanup is best-effort
                           (done)))
                  (.catch (fn [_]
                            (done)))))
            done))))
