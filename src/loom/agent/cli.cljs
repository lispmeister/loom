(ns loom.agent.cli
  "CLI dispatcher for running individual agent tools from the command line.
   Usage: node out/agent.js <command> [args...]

   Commands:
     reflect [--lookback N] [--repo PATH]  — propose next program.md
     spawn <program.md-path>               — spawn Lab with program.md file
     verify <generation> [--repo PATH]     — verify a generation
     promote <generation>                  — promote a generation
     rollback <generation>                 — rollback a generation
     serve                                 — start HTTP server (default)"
  (:require [loom.agent.reflect :as reflect]
            [loom.agent.self-modify :as sm]
            [loom.agent.autonomous :as auto]
            ["node:fs" :as fs]
            ["node:process" :as process]))

(defn- exit
  "Print message and exit with code."
  [code msg]
  (if (zero? code)
    (println msg)
    (binding [*print-fn* *print-err-fn*]
      (println msg)))
  (.exit process code))

(defn- parse-flag
  "Find --flag value in argv. Returns value or default."
  [argv flag default]
  (let [idx (.indexOf argv flag)]
    (if (and (>= idx 0) (< (inc idx) (count argv)))
      (nth argv (inc idx))
      default)))

(defn- run-reflect
  "Run reflect_and_propose and print the result.
   reflect-and-propose now returns {:program-md str :token-usage map} on success,
   or an error string on failure."
  [argv]
  (let [lookback (js/parseInt (parse-flag argv "--lookback" "5") 10)
        repo     (parse-flag argv "--repo" ".")]
    (-> (reflect/reflect-and-propose {:repo_path repo :lookback lookback})
        (.then (fn [result]
                 (if (map? result)
                   (exit 0 (:program-md result))
                   (exit 0 result))))
        (.catch (fn [err] (exit 1 (str "Error: " (.-message err))))))))

(defn- run-spawn
  "Read a program.md file and spawn a Lab."
  [argv]
  (if (< (count argv) 1)
    (exit 1 "Usage: node out/agent.js spawn <program.md-path>")
    (let [filepath (first argv)
          content  (try (.readFileSync fs filepath "utf8")
                        (catch :default e
                          (exit 1 (str "Error reading " filepath ": " (.-message e)))))]
      (when content
        (-> (sm/spawn-lab {:program_md content})
            (.then (fn [result] (exit 0 result)))
            (.catch (fn [err] (exit 1 (str "Error: " (.-message err))))))))))

(defn- run-verify
  "Verify a generation."
  [argv]
  (if (< (count argv) 1)
    (exit 1 "Usage: node out/agent.js verify <generation> [--repo PATH]")
    (let [gen  (js/parseInt (first argv) 10)
          repo (parse-flag argv "--repo" ".")]
      (-> (sm/verify-generation {:generation gen :repo_path repo})
          (.then (fn [result] (exit 0 result)))
          (.catch (fn [err] (exit 1 (str "Error: " (.-message err)))))))))

(defn- run-promote
  "Promote a generation."
  [argv]
  (if (< (count argv) 1)
    (exit 1 "Usage: node out/agent.js promote <generation>")
    (let [gen (js/parseInt (first argv) 10)]
      (-> (sm/promote-generation {:generation gen})
          (.then (fn [result] (exit 0 result)))
          (.catch (fn [err] (exit 1 (str "Error: " (.-message err)))))))))

(defn- run-rollback
  "Rollback a generation."
  [argv]
  (if (< (count argv) 1)
    (exit 1 "Usage: node out/agent.js rollback <generation>")
    (let [gen (js/parseInt (first argv) 10)]
      (-> (sm/rollback-generation {:generation gen})
          (.then (fn [result] (exit 0 result)))
          (.catch (fn [err] (exit 1 (str "Error: " (.-message err)))))))))

(defn- run-autonomous
  "Run the autonomous improvement loop."
  [argv]
  (let [lookback (js/parseInt (parse-flag argv "--lookback" "5") 10)
        repo     (parse-flag argv "--repo" ".")]
    (-> (auto/run-loop {:repo repo :lookback lookback})
        (.then (fn [summary]
                 (exit 0 (str "Loop finished: " (:stop-reason summary)))))
        (.catch (fn [err]
                  (exit 1 (str "Error: " (.-message err))))))))

(def usage-text
  "Loom Agent CLI

Usage: node out/agent.js <command> [args...]

Commands:
  reflect    [--lookback N] [--repo PATH]  Propose next program.md via LLM
  spawn      <program.md-path>           Spawn Lab with program.md file
  verify     <generation> [--repo PATH]  Verify a generation (run tests, diff)
  promote    <generation>                Promote a generation to main
  rollback   <generation>                Rollback a generation
  autonomous [--lookback N] [--repo PATH] Run reflect-spawn-verify loop
  serve                                  Start HTTP server (default)

Environment:
  ANTHROPIC_API_KEY      Required for reflect/serve/autonomous
  LOOM_MODEL             Model for Prime (default: claude-sonnet-4-20250514)
  LOOM_LAB_TIMEOUT_MS    Poll timeout in ms (default: 300000)
  LOOM_MAX_GENERATIONS   Max generations for autonomous loop (default: 5)
  LOOM_TOKEN_BUDGET      Max cumulative tokens, 0=unlimited (default: 0)
  LOOM_PLATEAU_WINDOW    Stop after N generations without improvement (default: 3)")

(defn dispatch
  "Parse process.argv and dispatch to the appropriate command.
   Returns :cli if a command was handled, :serve if the server should start."
  []
  (let [argv   (vec (drop 2 (.-argv process)))  ;; drop 'node' and script path
        cmd    (first argv)
        rest   (vec (rest argv))]
    (case cmd
      "reflect"    (do (run-reflect rest) :cli)
      "spawn"      (do (run-spawn rest) :cli)
      "verify"     (do (run-verify rest) :cli)
      "promote"    (do (run-promote rest) :cli)
      "rollback"   (do (run-rollback rest) :cli)
      "autonomous" (do (run-autonomous rest) :cli)
      "help"     (do (exit 0 usage-text) :cli)
      "--help"   (do (exit 0 usage-text) :cli)
      "-h"       (do (exit 0 usage-text) :cli)
      "serve"    :serve
      ;; Default: no args or unknown → serve
      (if (and cmd (not= "" cmd))
        (do (exit 1 (str "Unknown command: " cmd "\n\n" usage-text)) :cli)
        :serve))))
