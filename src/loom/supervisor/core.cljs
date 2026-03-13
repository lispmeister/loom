(ns loom.supervisor.core
  "Host-side supervisor. Manages container lifecycle and version history."
  (:require [loom.supervisor.http :as http]
            [loom.supervisor.container :as container]
            [loom.supervisor.generations :as gen]))

(def ^:private fs (js/require "node:fs"))
(def ^:private path-mod (js/require "node:path"))

(defonce state (atom {:started-at (.now js/Date)}))

(defn- ensure-generations-file
  "Create generations.edn if it doesn't exist."
  [path]
  (when-not (.existsSync fs path)
    (.writeFileSync fs path "[]" "utf8")))

(defn main []
  (let [port       (let [p (.-PORT (.-env js/process))]
                     (if p (js/parseInt p 10) 8400))
        repo-path  (or (.-LOOM_REPO_PATH (.-env js/process)) ".")
        gens-path  (.join path-mod repo-path "generations.edn")
        network    (or (.-LOOM_NETWORK (.-env js/process)) "loom-net")
        config     {:repo-path        repo-path
                    :generations-path gens-path
                    :network          network}]
    (ensure-generations-file gens-path)
    (println "Loom supervisor starting...")
    (-> (container/cli-available?)
        (.then (fn [available?]
                 (if available?
                   (do
                     (println "Container CLI available, ensuring network:" network)
                     (container/network-ensure network))
                   (do
                     (println "WARNING: Container CLI not available — spawn will fail")
                     (js/Promise.resolve {:ok true})))))
        (.then (fn [_]
                 (http/start-supervisor-server state config :port port)))
        (.then (fn [server]
                 (let [actual-port (.-port (.address server))]
                   (println (str "Supervisor listening on port " actual-port)))))
        (.catch (fn [err]
                  (js/console.error "Supervisor failed to start:" err)
                  (.exit js/process 1))))))
