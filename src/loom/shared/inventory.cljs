(ns loom.shared.inventory
  "Source file inventory tracking.")

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(defn source-file-count
  "Recursively count the number of .cljs source files in the src/ directory."
  []
  (let [src-dir (path.resolve "/workspace/src")]
    (letfn [(count-files [dir]
              (let [entries (.readdirSync fs dir #js {:withFileTypes true})]
                (reduce
                  (fn [total entry]
                    (let [full-path (.resolve path dir (.-name entry))]
                      (cond
                        (.-isDirectory entry)
                        (+ total (count-files full-path))
                        
                        (.-isFile entry)
                        (if (.endsWith (.-name entry) ".cljs")
                          (+ total 1)
                          total)
                        
                        :else
                        total)))
                  0
                  entries)))]
      (count-files src-dir))))
