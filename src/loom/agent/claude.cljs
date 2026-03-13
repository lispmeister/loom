(ns loom.agent.claude
  "Minimal HTTP client for the Anthropic Messages API.
   Non-streaming, tool_use aware."
  (:require ["node:https" :as https]
            [clojure.string :as str]))

(def ^:private api-url "https://api.anthropic.com/v1/messages")

(defn- build-request-body
  "Construct the JSON request body map for the Messages API."
  [{:keys [model messages system tools max-tokens]}]
  (cond-> {:model model
           :messages messages
           :max_tokens max-tokens}
    system (assoc :system system)
    (seq tools) (assoc :tools tools)))

(defn- https-post
  "Make an HTTPS POST request. Returns a promise resolving to
   {:status N :body \"raw-string\"} or {:error true :message \"...\"}."
  [url headers body-str]
  (js/Promise.
   (fn [resolve _reject]
     (let [parsed-url (js/URL. url)
           options #js {:hostname (.-hostname parsed-url)
                        :path (.-pathname parsed-url)
                        :method "POST"
                        :headers (clj->js headers)}
           req (.request https options
                         (fn [^js res]
                           (let [chunks #js []]
                             (.on res "data" (fn [chunk] (.push chunks chunk)))
                             (.on res "end"
                                  (fn []
                                    (let [body (.join chunks "")]
                                      (resolve {:status (.-statusCode res)
                                                :body body})))))))]
       (.on req "error"
            (fn [err]
              (resolve {:error true :message (.-message err)})))
       (.write req body-str)
       (.end req)))))

(defn send-message
  "Send a message to the Claude API. Returns a promise resolving to the
   parsed response body (as ClojureScript data).

   On HTTP error, resolves to {:error true :status N :body \"...\"}.
   On network error, resolves to {:error true :message \"...\"}."
  [{:keys [api-key model messages system tools max-tokens]
    :or {model "claude-sonnet-4-20250514"
         max-tokens 4096}}]
  (let [headers {"x-api-key" api-key
                 "anthropic-version" "2023-06-01"
                 "content-type" "application/json"}
        body (build-request-body {:model model
                                  :messages messages
                                  :system system
                                  :tools tools
                                  :max-tokens max-tokens})
        body-str (js/JSON.stringify (clj->js body))]
    (-> (https-post api-url headers body-str)
        (.then (fn [result]
                 (if (:error result)
                   ;; Network error — pass through
                   result
                   ;; Got an HTTP response
                   (let [{:keys [status body]} result]
                     (if (<= 200 status 299)
                       (js->clj (js/JSON.parse body) :keywordize-keys true)
                       {:error true :status status :body body}))))))))

;; ---------------------------------------------------------------------------
;; Response parsing helpers
;; ---------------------------------------------------------------------------

(defn extract-tool-calls
  "Extract tool use blocks from a Claude API response.
   Returns a vector of {:id \"...\" :name \"...\" :input {...}} maps.
   Returns [] if no tool calls."
  [response]
  (->> (:content response)
       (filter #(= "tool_use" (:type %)))
       (mapv (fn [block]
               {:id (:id block)
                :name (:name block)
                :input (:input block)}))))

(defn extract-text
  "Extract text content from a Claude API response.
   Returns concatenated text from all text blocks, or \"\"."
  [response]
  (->> (:content response)
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "")))

(defn tool-result-message
  "Construct a tool_result content block for feeding back to Claude.
   Returns a message map with role \"user\" and tool_result content."
  [tool-use-id result & {:keys [is-error] :or {is-error false}}]
  {:role "user"
   :content [{:type "tool_result"
              :tool_use_id tool-use-id
              :content (str result)
              :is_error is-error}]})
