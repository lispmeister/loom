(ns loom.shared.eval-client
  "TCP client for the Lab eval server.
   Sends EvalRequest as newline-delimited EDN, receives EvalResponse.
   Not currently in the self-modification critical path — verify_generation
   uses git checkout + npm test instead. Retained for future eval-probe use."
  (:require [cljs.reader :as reader]))

(def net (js/require "node:net"))

(defn eval-form
  "Send a form string to the eval server at host:port.
   Returns a promise resolving to {:status :ok/:error ...}."
  [host port form-str & {:keys [timeout] :or {timeout 5000}}]
  (js/Promise.
   (fn [resolve reject]
     (let [socket (.createConnection net #js {:host host :port port})
           buffer (atom "")
           cleanup (fn []
                     (.removeAllListeners socket)
                     (.destroy socket))]
       (.setTimeout socket timeout)
       (.on socket "connect"
            (fn []
              (.write socket (str (pr-str {:form form-str}) "\n"))))
       (.on socket "data"
            (fn [data]
              (swap! buffer str (.toString data))
              (let [idx (.indexOf @buffer "\n")]
                (when (>= idx 0)
                  (let [line (.substring @buffer 0 idx)
                        response (reader/read-string line)]
                    (cleanup)
                    (resolve response))))))
       (.on socket "timeout"
            (fn []
              (cleanup)
              (resolve {:status :error :message "Connection timed out"})))
       (.on socket "error"
            (fn [err]
              (cleanup)
              (resolve {:status :error :message (str "Connection error: " err)})))))))
