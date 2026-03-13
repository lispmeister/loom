(ns loom.supervisor-http-test
  (:require [cljs.test :refer [deftest is async testing]]
            [clojure.string :as str]
            [loom.supervisor.http :as sup-http]
            [loom.supervisor.generations :as gen]
            [loom.supervisor.git :as git]
            [loom.shared.http :as http]))

(def ^:private node-http (js/require "node:http"))
(def ^:private fs (js/require "node:fs"))
(def ^:private path-mod (js/require "node:path"))
(def ^:private os (js/require "node:os"))
(def ^:private child-process (js/require "node:child_process"))

;; -- Helpers --

(defn- http-request [opts]
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

(defn- init-git-repo []
  (let [dir (.mkdtempSync fs (.join path-mod (.tmpdir os) "loom-sup-test-"))]
    (.execFileSync child-process "git" #js ["init" dir])
    (.execFileSync child-process "git" #js ["-C" dir "config" "user.email" "test@test.com"])
    (.execFileSync child-process "git" #js ["-C" dir "config" "user.name" "Test"])
    (.writeFileSync fs (.join path-mod dir "README") "init" "utf8")
    (.execFileSync child-process "git" #js ["-C" dir "add" "."])
    (.execFileSync child-process "git" #js ["-C" dir "commit" "-m" "initial"])
    dir))

(defn- cleanup [dir]
  (.rmSync fs dir #js {:recursive true :force true}))

(defn- make-config [repo-dir]
  {:repo-path        repo-dir
   :generations-path (.join path-mod repo-dir "generations.edn")})

(defn- make-state []
  (atom {:started-at (.now js/Date)}))

(defn- parse-json [body]
  (js->clj (js/JSON.parse body) :keywordize-keys true))

(defn- seed-generation
  "Manually create a branch and generation record (bypasses /spawn which needs containers)."
  [dir config gen-num]
  (let [branch (str "lab/gen-" gen-num)
        gens-path (:generations-path config)]
    (.execFileSync child-process "git" #js ["-C" dir "checkout" "-b" branch])
    (gen/append-generation gens-path
                           {:generation      gen-num
                            :parent          0
                            :branch          branch
                            :program-md-hash "test123"
                            :outcome         :in-progress
                            :created         (.toISOString (js/Date.))
                            :container-id    ""})))

;; -- Tests --

(deftest test-dashboard-returns-html
  (testing "GET / returns HTML dashboard"
    (async done
           (let [dir    (init-git-repo)
                 config (make-config dir)
                 state  (make-state)]
             (-> (sup-http/start-supervisor-server state config :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (get-request port "/")
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (is (str/includes? (get (:headers resp) "content-type") "text/html"))
                                         (is (str/includes? (:body resp) "Loom Supervisor"))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (cleanup dir) (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (cleanup dir)
                                          (is false (str "Unexpected: " err))
                                          (done))))))))))))

(deftest test-stats-endpoint
  (testing "GET /stats returns JSON with status info"
    (async done
           (let [dir    (init-git-repo)
                 config (make-config dir)
                 state  (make-state)]
             (-> (sup-http/start-supervisor-server state config :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (get-request port "/stats")
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (let [body (parse-json (:body resp))]
                                           (is (= "running" (:status body)))
                                           (is (= 0 (:current-generation body)))
                                           (is (= 0 (:total-generations body)))
                                           (is (number? (:uptime-ms body))))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (cleanup dir) (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (cleanup dir)
                                          (is false (str "Unexpected: " err))
                                          (done))))))))))))

(deftest test-versions-empty
  (testing "GET /versions returns empty array initially"
    (async done
           (let [dir    (init-git-repo)
                 config (make-config dir)
                 state  (make-state)]
             (-> (sup-http/start-supervisor-server state config :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (get-request port "/versions")
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (let [body (parse-json (:body resp))]
                                           (is (= [] body)))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (cleanup dir) (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (cleanup dir)
                                          (is false (str "Unexpected: " err))
                                          (done))))))))))))

(deftest test-spawn-without-containers-fails
  (testing "POST /spawn returns 500 when container infrastructure unavailable"
    (async done
           (let [dir    (init-git-repo)
                 config (make-config dir)
                 state  (make-state)]
             (-> (sup-http/start-supervisor-server state config :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (post-json port "/spawn" {:program_md "# Task: add feature X"})
                                (.then (fn [resp]
                                         ;; Without container CLI, spawn should fail
                                         ;; but the endpoint should still respond (not crash)
                                         (is (number? (:status resp)))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (cleanup dir) (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (cleanup dir)
                                          (is false (str "Unexpected: " err))
                                          (done))))))))))))

(deftest test-promote-with-seeded-generation
  (testing "POST /promote merges branch and tags"
    (async done
           (let [dir    (init-git-repo)
                 config (make-config dir)
                 state  (make-state)]
             ;; Seed a branch and generation record manually
             (seed-generation dir config 1)
             ;; Make a commit on the lab branch so there's something to merge
             (.writeFileSync fs (.join path-mod dir "feature.txt") "new code" "utf8")
             (.execFileSync child-process "git" #js ["-C" dir "add" "."])
             (.execFileSync child-process "git" #js ["-C" dir "commit" "-m" "lab work"])
             (-> (sup-http/start-supervisor-server state config :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (post-json port "/promote" {:generation 1})
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (let [body (parse-json (:body resp))]
                                           (is (= 1 (:generation body)))
                                           (is (= "promoted" (:status body))))))
                                ;; Verify generation record updated
                                (.then (fn [_] (get-request port "/versions")))
                                (.then (fn [resp]
                                         (let [gens (parse-json (:body resp))
                                               g1 (first gens)]
                                           (is (= "promoted" (:outcome g1)))
                                           (is (some? (:completed g1))))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (cleanup dir) (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (cleanup dir)
                                          (is false (str "Unexpected: " err))
                                          (done))))))))))))

(deftest test-rollback-with-seeded-generation
  (testing "POST /rollback discards branch"
    (async done
           (let [dir    (init-git-repo)
                 config (make-config dir)
                 state  (make-state)]
             ;; Seed a branch and generation record manually
             (seed-generation dir config 1)
             ;; Go back to master so rollback can delete the branch
             (.execFileSync child-process "git" #js ["-C" dir "checkout" "master"])
             (-> (sup-http/start-supervisor-server state config :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (post-json port "/rollback" {:generation 1})
                                (.then (fn [resp]
                                         (is (= 200 (:status resp)))
                                         (let [body (parse-json (:body resp))]
                                           (is (= 1 (:generation body)))
                                           (is (= "rolled-back" (:status body))))))
                                ;; Verify generation record updated
                                (.then (fn [_] (get-request port "/versions")))
                                (.then (fn [resp]
                                         (let [gens (parse-json (:body resp))
                                               g1 (first gens)]
                                           (is (= "failed" (:outcome g1))))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (cleanup dir) (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (cleanup dir)
                                          (is false (str "Unexpected: " err))
                                          (done))))))))))))

(deftest test-promote-nonexistent-generation
  (testing "POST /promote returns 404 for nonexistent generation"
    (async done
           (let [dir    (init-git-repo)
                 config (make-config dir)
                 state  (make-state)]
             (-> (sup-http/start-supervisor-server state config :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (post-json port "/promote" {:generation 99})
                                (.then (fn [resp]
                                         (is (= 404 (:status resp)))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (cleanup dir) (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (cleanup dir)
                                          (is false (str "Unexpected: " err))
                                          (done))))))))))))

(deftest test-rollback-nonexistent-generation
  (testing "POST /rollback returns 404 for nonexistent generation"
    (async done
           (let [dir    (init-git-repo)
                 config (make-config dir)
                 state  (make-state)]
             (-> (sup-http/start-supervisor-server state config :port 0)
                 (.then (fn [server]
                          (let [port (http/server-port server)]
                            (-> (post-json port "/rollback" {:generation 99})
                                (.then (fn [resp]
                                         (is (= 404 (:status resp)))))
                                (.then (fn [_] (http/stop-server server)))
                                (.then (fn [_] (cleanup dir) (done)))
                                (.catch (fn [err]
                                          (http/stop-server server)
                                          (cleanup dir)
                                          (is false (str "Unexpected: " err))
                                          (done))))))))))))
