(ns loom.agent.tools
  "Tool implementations for the Loom agent.
   Each tool takes an input map and returns a promise resolving to a string."
  (:require ["node:fs/promises" :as fsp]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [loom.agent.self-modify :as self-modify]
            [loom.agent.reflect :as reflect]))

;; ---------------------------------------------------------------------------
;; Tool definitions for the Claude API
;; ---------------------------------------------------------------------------

(def base-tool-definitions
  "Core tool definitions for file operations and shell commands."
  [{:name "read_file"
    :description "Read a file's contents"
    :input_schema {:type "object"
                   :properties {:path {:type "string" :description "Absolute or relative file path"}}
                   :required ["path"]}}
   {:name "write_file"
    :description "Write content to a file (creates or overwrites)"
    :input_schema {:type "object"
                   :properties {:path {:type "string" :description "File path"}
                                :content {:type "string" :description "Content to write"}}
                   :required ["path" "content"]}}
   {:name "edit_file"
    :description "Replace a string in a file"
    :input_schema {:type "object"
                   :properties {:path {:type "string" :description "File path"}
                                :old_string {:type "string" :description "String to find"}
                                :new_string {:type "string" :description "Replacement string"}}
                   :required ["path" "old_string" "new_string"]}}
   {:name "bash"
    :description "Execute a shell command"
    :input_schema {:type "object"
                   :properties {:command {:type "string" :description "Shell command to execute"}
                                :timeout {:type "integer" :description "Timeout in ms (default 30000)"}}
                   :required ["command"]}}])

;; ---------------------------------------------------------------------------
;; Tool implementations
;; ---------------------------------------------------------------------------

(defn read-file
  "Read a file's contents with 1-based line numbers.
   Format: right-aligned number padded to width of max line number, tab, then line content.
   On error, returns error message string."
  [{:keys [path]}]
  (-> (.readFile fsp path "utf8")
      (.then (fn [content]
               (let [lines (str/split-lines content)
                     num-lines (count lines)
                     width (count (str num-lines))
                     numbered-lines (map-indexed
                                     (fn [idx line]
                                       (let [line-num (inc idx)
                                             padded-num (str/replace
                                                         (str (str/join (repeat width " ")) line-num)
                                                         #" +(.+)$"
                                                         "$1")]
                                         (str padded-num "\t" line)))
                                     lines)]
                 (str/join "\n" numbered-lines))))
      (.catch (fn [err]
                (str "Error reading " path ": " (.-message err))))))

(defn write-file
  "Write content to a file (creates or overwrites). Creates parent dirs if needed.
   Returns confirmation string."
  [{:keys [path content]}]
  (js/Promise.
   (fn [resolve _reject]
     (try
       (let [dir (.dirname node-path path)]
         (.mkdirSync fs dir #js {:recursive true}))
       (-> (.writeFile fsp path content "utf8")
           (.then (fn [_] (resolve (str "Wrote " (count content) " chars to " path))))
           (.catch (fn [err] (resolve (str "Error writing " path ": " (.-message err))))))
       (catch :default err
         (resolve (str "Error writing " path ": " (.-message err))))))))

(defn edit-file
  "Replace first occurrence of old_string with new_string in a file.
   Returns confirmation or error if old_string not found.
   Note: keys use underscores to match Claude API JSON convention."
  [{:keys [path old_string new_string]}]
  (-> (.readFile fsp path "utf8")
      (.then (fn [content]
               (if (str/includes? content old_string)
                 (let [updated (.replace content old_string new_string)]
                   (-> (.writeFile fsp path updated "utf8")
                       (.then (fn [_] (str "Edited " path ": replaced 1 occurrence")))))
                 (str "Error: old_string not found in " path))))
      (.catch (fn [err]
                (str "Error editing " path ": " (.-message err))))))

(defn bash
  "Execute a shell command. Returns stdout/stderr/exit-code as a string.
   Enforces timeout (default 30s). Kills process on timeout."
  [{:keys [command timeout] :or {timeout 30000}}]
  (js/Promise.
   (fn [resolve _reject]
     (let [opts #js {:timeout timeout
                     :maxBuffer (* 10 1024 1024)
                     :shell true}]
       (.exec cp command opts
              (fn [err stdout stderr]
                (let [exit-code (if (nil? err)
                                  0
                                  (or (.-code err) 1))
                      timed-out? (and (some? err)
                                      (true? (.-killed ^js err)))
                      lines (cond-> []
                              (seq stdout) (conj (str "stdout:\n" stdout))
                              (seq stderr) (conj (str "stderr:\n" stderr))
                              true         (conj (str "exit-code: " exit-code))
                              timed-out?   (conj (str "timed out after " timeout "ms")))]
                  (resolve (str/join "\n" lines)))))))))

;; ---------------------------------------------------------------------------
;; Tool registry
;; ---------------------------------------------------------------------------

(def base-registry
  "Map from tool name to implementation for core tools."
  {"read_file"  read-file
   "write_file" write-file
   "edit_file"  edit-file
   "bash"       bash})

(def tool-definitions
  "All tool definitions: core + self-modify + reflect."
  (-> base-tool-definitions
      (into self-modify/tool-definitions)
      (into reflect/tool-definitions)))

(defn- reflect-and-propose-tool
  "Wrap reflect-and-propose for the tool interface: extracts :program-md string
   from the result map so Claude receives a plain string, not a Clojure map.
   Error strings (from reflect failure) are passed through unchanged."
  [input]
  (-> (reflect/reflect-and-propose input)
      (.then (fn [result]
               (if (map? result)
                 (:program-md result)
                 result)))))

(def registry
  "All tools: core + self-modify + reflect."
  (-> (merge base-registry self-modify/registry reflect/registry)
      (assoc "reflect_and_propose" reflect-and-propose-tool)))
