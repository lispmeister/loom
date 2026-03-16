(ns loom.agent.claude
  "Minimal HTTP client for the Anthropic Messages API.
   Non-streaming, tool_use aware.
   Supports ANTHROPIC_API_BASE env var for custom API endpoints."
  (:require ["node:https" :as https]
            ["node:http" :as http]
            [clojure.string :as str]))

(def ^:private api-base
  (or (some-> js/process .-env .-ANTHROPIC_API_BASE)
      "https://api.anthropic.com"))

(def ^:private api-url (str api-base "/v1/messages"))

(defn- build-request-body
  "Construct the JSON request body map for the Messages API."
  [{:keys [model messages system tools max-tokens]}]
  (cond-> {:model model
           :messages messages
           :max_tokens max-tokens}
    system (assoc :system system)
    (seq tools) (assoc :tools tools)))

(defn- http-post
  "Make an HTTP/HTTPS POST request (auto-selects based on URL scheme).
   Returns a promise resolving to
   {:status N :body \"raw-string\"} or {:error true :message \"...\"}."
  [url headers body-str]
  (js/Promise.
   (fn [resolve _reject]
     (let [parsed-url (js/URL. url)
           use-https? (= "https:" (.-protocol parsed-url))
           client (if use-https? https http)
           options #js {:hostname (.-hostname parsed-url)
                        :port (.-port parsed-url)
                        :path (str (.-pathname parsed-url) (.-search parsed-url))
                        :method "POST"
                        :headers (clj->js headers)}
           req (.request client options
                         (fn [^js res]
                           (let [chunks #js []]
                             (.on res "data" (fn [chunk] (.push chunks chunk)))
                             (.on res "end"
                                  (fn []
                                    (let [body (.join chunks "")]
                                      (resolve {:status (.-statusCode res)
                                                :headers {:retry-after (aget (.-headers res) "retry-after")}
                                                :body body})))))))]
       (.on req "error"
            (fn [err]
              (resolve {:error true :message (.-message err)})))
       (.write req body-str)
       (.end req)))))

(def ^:private max-retries
  "Maximum number of retries on 429 rate-limit responses."
  3)

(def ^:private default-retry-delay-ms
  "Fallback delay when retry-after header is missing (ms)."
  10000)

(defn parse-retry-after
  "Parse retry-after header value to milliseconds.
   Handles integer seconds. Returns default-retry-delay-ms on failure."
  [value]
  (if (some? value)
    (let [seconds (js/parseFloat value)]
      (if (js/isNaN seconds)
        default-retry-delay-ms
        (* (js/Math.ceil seconds) 1000)))
    default-retry-delay-ms))

(defn- delay-ms
  "Returns a promise that resolves after ms milliseconds."
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn send-message
  "Send a message to the Claude API. Returns a promise resolving to the
   parsed response body (as ClojureScript data).

   Retries up to 3 times on 429 (rate limit) responses, respecting the
   retry-after header.

   On HTTP error, resolves to {:error true :status N :body \"...\"}.
   On network error, resolves to {:error true :message \"...\"}."
  [{:keys [api-key model messages system tools max-tokens on-event]
    :or {model "claude-sonnet-4-20250514"
         max-tokens 4096}}]
  (let [req-headers {"x-api-key" api-key
                     "anthropic-version" "2023-06-01"
                     "content-type" "application/json"}
        body (build-request-body {:model model
                                  :messages messages
                                  :system system
                                  :tools tools
                                  :max-tokens max-tokens})
        body-str (js/JSON.stringify (clj->js body))]
    (letfn [(attempt [retries-left]
              (-> (http-post api-url req-headers body-str)
                  (.then (fn [result]
                           (if (:error result)
                             result
                             (let [{:keys [status body headers]} result]
                               (if (<= 200 status 299)
                                 (let [parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
                                   ;; Attach :token-usage from API response usage field
                                   (if-let [usage (:usage parsed)]
                                     (assoc parsed :token-usage
                                            {:input  (:input_tokens usage 0)
                                             :output (:output_tokens usage 0)})
                                     parsed))
                                 (if (and (= 429 status) (pos? retries-left))
                                   (let [wait-ms (parse-retry-after (:retry-after headers))]
                                     (when on-event
                                       (on-event {:type :rate-limit
                                                  :retry-after-ms wait-ms
                                                  :retries-left retries-left}))
                                     (-> (delay-ms wait-ms)
                                         (.then #(attempt (dec retries-left)))))
                                   {:error true :status status :body body}))))))))]
      (attempt max-retries))))

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
