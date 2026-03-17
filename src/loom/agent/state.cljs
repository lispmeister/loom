(ns loom.agent.state
  "Prime state serialization and loading across generation promotions.
   Writes/reads tmp/prime-state.edn with a version tag for schema evolution.
   Version mismatch, missing file, or parse error all yield nil (fresh start)."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]))

(def ^:private current-version 1)

(defn state-file-path
  "Returns the absolute path to the Prime state file."
  []
  (.join path "tmp" "prime-state.edn"))

(defn serialize-state
  "Write state-map to the state file as EDN with version and timestamp metadata.
   Creates the parent directory if needed. Returns nil."
  [state-map]
  (let [fpath (state-file-path)]
    (try
      (.mkdirSync fs (.dirname path fpath) #js {:recursive true})
      (catch :default _e nil)))
  (let [payload (assoc state-map
                       :version current-version
                       :timestamp (.toISOString (js/Date.)))
        edn-str (pr-str payload)]
    (try
      (.writeFileSync fs (state-file-path) edn-str "utf8")
      nil
      (catch :default e
        (println (str "[state] Warning: failed to write state file: " (.-message e)))
        nil))))

(defn load-state
  "Read the state file and parse it.
   Returns the state map (minus :version and :timestamp) when the version matches,
   or nil on missing file, parse error, or version mismatch."
  []
  (let [fpath (state-file-path)]
    (try
      (let [content  (.readFileSync fs fpath "utf8")
            payload  (reader/read-string content)
            version  (:version payload)]
        (if (= version current-version)
          (dissoc payload :version :timestamp)
          (do
            (println (str "[state] Version mismatch (got " version
                          ", expected " current-version ") — fresh start"))
            nil)))
      (catch :default _e
        nil))))

(defn clear-state
  "Delete the state file if it exists. Returns nil."
  []
  (let [fpath (state-file-path)]
    (try
      (when (.existsSync fs fpath)
        (.unlinkSync fs fpath))
      nil
      (catch :default e
        (println (str "[state] Warning: failed to clear state file: " (.-message e)))
        nil))))
