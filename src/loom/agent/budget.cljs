(ns loom.agent.budget
  "Rolling-window API prompt budget tracker.
   Tracks LLM API call timestamps and enforces a max-calls-per-window limit.
   Provider-agnostic — counts calls, not tokens.

   Config via env vars:
     LOOM_API_BUDGET    — max calls per window, 0 = unlimited (default 0)
     LOOM_API_WINDOW_MS — window size in milliseconds (default 18000000 = 5 hours)")

;; ---------------------------------------------------------------------------
;; Configuration from env
;; ---------------------------------------------------------------------------

(def ^:dynamic window-ms
  "Rolling window size in milliseconds. Default: 5 hours."
  (let [v (some-> js/process .-env .-LOOM_API_WINDOW_MS)]
    (if v (js/parseInt v 10) 18000000)))

(def ^:dynamic max-calls
  "Maximum API calls per window. 0 = unlimited."
  (let [v (some-> js/process .-env .-LOOM_API_BUDGET)]
    (if v (js/parseInt v 10) 0)))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

;; Atom holding a vector of epoch-ms timestamps, one per recorded API call.
(defonce ^:private call-timestamps (atom []))

;; ---------------------------------------------------------------------------
;; Core functions
;; ---------------------------------------------------------------------------

(defn record-call!
  "Append the current timestamp (ms since epoch) to the call log."
  []
  (swap! call-timestamps conj (.now js/Date)))

(defn calls-in-window
  "Count calls recorded within the last `ms` milliseconds.
   Prunes timestamps older than the window as a side effect.
   Uses window-ms if ms is not provided."
  ([] (calls-in-window window-ms))
  ([ms]
   (let [cutoff (- (.now js/Date) ms)
         recent (filterv #(> % cutoff) @call-timestamps)]
     (reset! call-timestamps recent)
     (count recent))))

(defn budget-remaining
  "Return how many calls remain in the current window.
   Uses dynamic vars `max-calls` and `window-ms` by default.
   Returns ##Inf when max-calls is 0 (unlimited)."
  ([] (budget-remaining max-calls window-ms))
  ([limit ms]
   (if (zero? limit)
     ##Inf
     (let [used (calls-in-window ms)]
       (max 0 (- limit used))))))

(defn budget-exhausted?
  "Returns true when the budget is finite and no calls remain."
  ([] (budget-exhausted? max-calls window-ms))
  ([limit ms]
   (and (pos? limit)
        (<= (budget-remaining limit ms) 0))))

(defn reset-for-testing!
  "Reset the call-timestamps atom. For use in tests only."
  []
  (reset! call-timestamps []))
