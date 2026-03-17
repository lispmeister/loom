(ns loom.supervisor.generations
  "Read/write/append to a generations.edn file that tracks
   the lineage of Lab containers."
  (:require [cljs.reader :as reader]
            [malli.core :as m]))

(def ^:private fs (js/require "node:fs"))

;; -- Schema --

(def Generation
  [:map
   [:generation :int]
   [:parent :int]
   [:branch :string]
   [:program-md-hash :string]
   [:outcome [:enum :promoted :failed :timeout :in-progress :done]]
   [:created :string]
   [:completed {:optional true} :string]
   [:container-id :string]
   [:source {:optional true} [:enum :user :reflect :cli]]])

(defn valid?
  "Return true if record matches the Generation schema."
  [record]
  (m/validate Generation record))

;; -- File I/O --

(defn read-generations
  "Read generations.edn from disk. Returns vector of generation records.
   Returns [] if file doesn't exist."
  [path]
  (if (.existsSync fs path)
    (let [contents (.readFileSync fs path "utf8")]
      (reader/read-string contents))
    []))

(defn write-generations
  "Write full generations vector to disk as formatted EDN."
  [path generations]
  (let [formatted (str "[\n"
                       (->> generations
                            (map pr-str)
                            (interpose "\n")
                            (apply str))
                       "\n]\n")]
    (.writeFileSync fs path formatted "utf8")))

(defn append-generation
  "Read existing generations, append new record, write back."
  [path generation]
  (when-not (valid? generation)
    (println "WARNING: append-generation received invalid generation record:" (pr-str generation)))
  (let [existing (read-generations path)]
    (write-generations path (conj existing generation))))

(defn update-generation
  "Update a specific generation record by number (merge updates)."
  [path generation-num updates]
  (let [gens (read-generations path)
        updated (mapv (fn [g]
                        (if (= (:generation g) generation-num)
                          (merge g updates)
                          g))
                      gens)]
    (doseq [g updated]
      (when-not (valid? g)
        (println "WARNING: update-generation produced invalid generation record:" (pr-str g))))
    (write-generations path updated)))

(defn next-generation-number
  "Return the next generation number (max existing + 1, or 1 if empty)."
  [path]
  (let [gens (read-generations path)]
    (if (empty? gens)
      1
      (inc (apply max (map :generation gens))))))
