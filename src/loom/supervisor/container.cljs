(ns loom.supervisor.container
  "Pure functions wrapping the Apple `container` CLI via child_process.
   Each function shells out to `container` and returns a promise resolving
   to structured data — never throws."
  (:require [clojure.string :as str]))

(def ^:private child-process (js/require "node:child_process"))

(defn- exec-container
  "Execute 'container' CLI with given args. Returns promise of
   {:ok true :output ...} or {:error true :message ... :exit-code N}."
  [args]
  (js/Promise.
   (fn [resolve _reject]
     (let [cb (fn [err stdout stderr]
                (if err
                  (resolve {:error true
                            :message (str (str/trim (str stderr)) " " (str/trim (str stdout)))
                            :exit-code (or (.-code err) 1)})
                  (resolve {:ok true
                            :output (str/trim (str stdout))})))]
       (.execFile child-process
                  "container"
                  (clj->js args)
                  cb)))))

(defn cli-available?
  "Check if the container CLI is installed. Returns promise of boolean."
  []
  (-> (exec-container ["--help"])
      (.then (fn [result] (:ok result)))))

(defn create
  "Create a container.
   - name: container name (e.g., 'lab-gen-1')
   - image: OCI image (e.g., 'node:22')
   - network: network name to attach to
   - env-vars: map of env vars {:ANTHROPIC_API_KEY '...'}
   - volumes: vector of [host-path container-path] pairs
   Returns promise resolving to {:ok true :container-id '...'} or {:error ...}."
  [name image & {:keys [network env-vars volumes]}]
  (let [args (cond-> ["create" "--name" name]
               network (into ["--network" network])
               env-vars (into (mapcat (fn [[k v]] ["--env" (str (clojure.core/name k) "=" v)])
                                      env-vars))
               volumes (into (mapcat (fn [[host-path container-path]]
                                       ["--volume" (str host-path ":" container-path)])
                                     volumes))
               true (conj image))]
    (-> (exec-container args)
        (.then (fn [result]
                 (if (:ok result)
                   (assoc result :container-id (str/trim (:output result "")))
                   result))))))

(defn start
  "Start a stopped container by name. Returns promise."
  [name]
  (exec-container ["start" name]))

(defn stop
  "Stop a running container by name. Returns promise."
  [name]
  (exec-container ["stop" name]))

(defn destroy
  "Remove/destroy a container by name. Returns promise."
  [name]
  (exec-container ["rm" name]))

(defn inspect
  "Get container details (status, IP, etc). Returns promise resolving
   to parsed data or error."
  [name]
  (-> (exec-container ["inspect" name])
      (.then (fn [result]
               (if (:ok result)
                 (try
                   (let [parsed (js->clj (js/JSON.parse (:output result)) :keywordize-keys true)]
                     {:ok true :data parsed})
                   (catch :default _e
                     ;; Output wasn't JSON — return raw
                     result))
                 result)))))

(defn run
  "Create and run a container in one step.
   Combines create + start. If detach is true, returns immediately.
   Returns promise resolving to {:ok true :container-id '...'} or output if not detached."
  [name image command & {:keys [network networks env-vars volumes publish detach] :or {detach true}}]
  (let [;; Support both :network "foo" and :networks ["foo" "bar"]
        all-networks (cond
                       networks networks
                       network [network]
                       :else nil)
        args (cond-> ["run" "--name" name]
               all-networks (into (mapcat (fn [n] ["--network" n]) all-networks))
               env-vars (into (mapcat (fn [[k v]] ["--env" (str (clojure.core/name k) "=" v)])
                                      env-vars))
               volumes (into (mapcat (fn [[host-path container-path]]
                                       ["--volume" (str host-path ":" container-path)])
                                     volumes))
               publish (into (mapcat (fn [spec] ["--publish" spec])
                                     (if (string? publish) [publish] publish)))
               detach (conj "-d")
               true (conj image)
               command (into (if (string? command) [command] command)))]
    (-> (exec-container args)
        (.then (fn [result]
                 (if (and (:ok result) detach)
                   (assoc result :container-id (str/trim (:output result "")))
                   result))))))

(defn published-port
  "Get the host port for a published container port.
   Apple Containerization may remap the requested host port, so this reads
   the actual mapping from inspect. Returns a promise of the port number or nil."
  [container-name container-port]
  (-> (inspect container-name)
      (.then (fn [result]
               (when (:ok result)
                 (let [config (first (:data result))
                       ports (get-in config [:configuration :publishedPorts])]
                   (some (fn [p]
                           (when (= container-port (:containerPort p))
                             (:hostPort p)))
                         ports)))))))

(defn network-create
  "Create a virtual network. Returns promise."
  [network-name]
  (exec-container ["network" "create" network-name]))

(defn network-exists?
  "Check if a network exists. Returns promise of boolean."
  [network-name]
  (-> (exec-container ["network" "ls"])
      (.then (fn [result]
               (if (:ok result)
                 (let [lines (str/split-lines (:output result ""))]
                   (boolean (some #(str/includes? % network-name) lines)))
                 false)))))

(defn network-delete
  "Delete a virtual network. Returns promise."
  [network-name]
  (exec-container ["network" "rm" network-name]))

(defn network-ensure
  "Create network if it doesn't exist. Returns promise."
  [network-name]
  (-> (network-exists? network-name)
      (.then (fn [exists?]
               (if exists?
                 {:ok true :output (str "Network " network-name " already exists")}
                 (network-create network-name))))))
