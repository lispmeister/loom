(ns loom.agent.dispatch
  "Dispatch layer connecting Claude tool calls to tool implementations."
  (:require [loom.agent.tools :as tools]
            [loom.agent.claude :as claude]))

(defn dispatch-tool-call
  "Dispatch a single tool call to the appropriate implementation.
   Uses the provided registry (or the global one if not given).
   Returns a promise resolving to a tool-result message map
   (via claude/tool-result-message)."
  ([tool-call] (dispatch-tool-call tool-call tools/registry))
  ([{:keys [id name input]} registry]
   (let [tool-fn (get registry name)]
     (if (nil? tool-fn)
       (js/Promise.resolve
        (claude/tool-result-message id (str "Error: unknown tool '" name "'") :is-error true))
       (-> (tool-fn input)
           (.then (fn [result]
                    (claude/tool-result-message id result)))
           (.catch (fn [err]
                     (claude/tool-result-message
                      id (str "Error: " (.-message err)) :is-error true))))))))

(defn dispatch-all
  "Dispatch all tool calls from a Claude response sequentially.
   Uses the provided registry (or the global one if not given).
   Returns a promise resolving to a vector of tool result messages
   ready to append to the conversation."
  ([tool-calls] (dispatch-all tool-calls tools/registry))
  ([tool-calls registry]
   (reduce
    (fn [chain tc]
      (.then chain
             (fn [results]
               (.then (dispatch-tool-call tc registry)
                      (fn [result]
                        (conj results result))))))
    (js/Promise.resolve [])
    tool-calls)))
