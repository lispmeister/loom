(ns loom.lab.eval-server
  "Lightweight form evaluation server for Lab containers.
   Accepts newline-delimited EDN forms over TCP, evaluates via cljs.js/eval-str,
   returns newline-delimited EDN results.
   Not currently in the self-modification critical path — Labs run the
   autonomous worker (lab/worker.cljs) instead. Retained for future
   eval-probe use by Prime."
  (:require [cljs.js :as cljs]
            [cljs.reader :as reader]
            [cognitect.transit :as transit]))

(def fs (js/require "node:fs"))
(def net (js/require "node:net"))

(defn read-transit-json
  "Read and deserialize a transit-JSON file from disk."
  [path]
  (let [json (.readFileSync fs path "utf8")
        rdr (transit/reader :json)]
    (transit/read rdr json)))

(defn init-compiler-state!
  "Create a self-hosted compiler state and populate it with cljs.core
   analysis data from shadow-cljs build artifacts."
  [cache-path]
  (let [state (cljs/empty-state)
        cache (read-transit-json cache-path)
        analyzer-cache (:analyzer cache)]
    (cljs/load-analysis-cache! state 'cljs.core analyzer-cache)
    (js/eval "if (typeof cljs.user === 'undefined') { cljs.user = {}; }")
    state))

(defn eval-form
  "Evaluate a ClojureScript form string. Returns a promise resolving to
   {:status :ok :value v} or {:status :error :message msg}."
  [compiler-state form-str]
  (js/Promise.
   (fn [resolve _]
     (try
       (cljs/eval-str
        compiler-state form-str "lab-eval"
        {:eval cljs/js-eval :ns 'cljs.user :context :expr}
        (fn [result]
          (if (:error result)
            (resolve {:status :error :message (str (:error result))})
            (resolve {:status :ok :value (:value result)}))))
       (catch :default e
         (resolve {:status :error :message (str e)}))))))

(defn handle-line
  "Process one newline-delimited EDN line. Returns a promise of an EDN response string."
  [compiler-state line]
  (js/Promise.
   (fn [resolve _]
     (try
       (let [request (reader/read-string line)
             form (:form request)]
         (if (string? form)
           (-> (eval-form compiler-state form)
               (.then (fn [response]
                        (resolve (str (pr-str response) "\n")))))
           (resolve (str (pr-str {:status :error :message "Missing :form key"}) "\n"))))
       (catch :default e
         (resolve (str (pr-str {:status :error :message (str "Parse error: " e)}) "\n")))))))

(defn start-server
  "Start a TCP eval server. Returns {:server <net.Server> :port <int>}.
   cache-path: path to cljs.core analysis cache transit file.
   port: TCP port (0 for random)."
  [& {:keys [port cache-path]
      :or {port 8402
           cache-path ".shadow-cljs/builds/lab/dev/ana/cljs/core.cljs.cache.transit.json"}}]
  (let [state (init-compiler-state! cache-path)
        server (.createServer net
                              (fn [^js socket]
                                (let [buffer (atom "")]
                                  (.on socket "data"
                                       (fn [data]
                                         (swap! buffer str (.toString data))
                                         (let [lines (.split @buffer "\n")
                                               complete (butlast lines)]
                                           (reset! buffer (last lines))
                                           (doseq [line complete]
                                             (when (seq line)
                                               (-> (handle-line state line)
                                                   (.then (fn [resp]
                                                            (.write socket resp)))))))))
                                  (.on socket "error" (fn [_])))))]
    (js/Promise.
     (fn [resolve _]
       (.listen server port
                (fn []
                  (let [actual-port (.-port (.address server))]
                    (resolve {:server server :port actual-port}))))))))

(defn stop-server
  "Stop a running eval server. Returns a promise."
  [server]
  (js/Promise.
   (fn [resolve _]
     (.close server (fn [] (resolve nil))))))

(defn main []
  (let [cache-path (or (.-CACHE_PATH (.-env js/process))
                       ".shadow-cljs/builds/lab/dev/ana/cljs/core.cljs.cache.transit.json")
        port       (let [p (.-PORT (.-env js/process))]
                     (if p (js/parseInt p 10) 8402))]
    (-> (start-server :port port :cache-path cache-path)
        (.then (fn [{:keys [port]}]
                 (println (str "Loom eval server listening on port " port)))))))
