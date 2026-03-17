(ns loom.agent.reflect
  "Reflect step: analyze generation history and user priorities,
   then propose the next program.md via LLM."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [loom.agent.claude :as claude]
            ["node:fs" :as fs]
            ["node:path" :as path]))

;; ---------------------------------------------------------------------------
;; Data gathering (synchronous fs reads)
;; ---------------------------------------------------------------------------

(defn read-file-safe
  "Read a file, returning its contents as a string or nil on any error."
  [filepath]
  (try
    (.readFileSync fs filepath "utf8")
    (catch :default _e nil)))

(defn read-generations
  "Read tmp/generations.edn from repo. Returns a vector of maps, or []."
  [repo]
  (let [filepath (.join path repo "tmp" "generations.edn")
        content  (read-file-safe filepath)]
    (if content
      (try
        (reader/read-string content)
        (catch :default _e []))
      [])))

(defn read-report
  "Read tmp/programs/gen-N-report.json. Returns a parsed map, or nil."
  [repo generation]
  (let [filepath (.join path repo "tmp" "programs" (str "gen-" generation "-report.json"))
        content  (read-file-safe filepath)]
    (when content
      (try
        (js->clj (js/JSON.parse content) :keywordize-keys true)
        (catch :default _e nil)))))

(defn read-program-md
  "Read tmp/programs/gen-N.md. Returns the string, or nil."
  [repo generation]
  (let [filepath (.join path repo "tmp" "programs" (str "gen-" generation ".md"))]
    (read-file-safe filepath)))

(defn read-priorities
  "Read priorities.md from repo root. Returns the string, or nil."
  [repo]
  (read-file-safe (.join path repo "priorities.md")))

(defn gather-codebase-summary
  "Read key source files from the repo for reflect introspection.
   Returns a map with :fitness-fn, :tool-definitions, :reflect-prompt,
   :loop-system-prompt. Values are full file contents (strings) or nil on failure."
  [repo]
  {:fitness-fn        (read-file-safe (.join path repo "src" "loom" "supervisor" "fitness.cljs"))
   :tool-definitions  (read-file-safe (.join path repo "src" "loom" "agent" "tools.cljs"))
   :reflect-prompt    (read-file-safe (.join path repo "src" "loom" "agent" "reflect.cljs"))
   :loop-system-prompt (read-file-safe (.join path repo "src" "loom" "agent" "loop.cljs"))})

(defn- fitness-score-from-report
  "Calculate fitness score from a report map, matching supervisor/fitness.cljs formula.
   score = (tests-run * 10) + (assertions * 1) - (total-tokens / 1000)"
  [report]
  (when report
    (let [test-results (:test-results report)
          token-usage  (:token-usage report)
          tests        (:tests-run test-results 0)
          assertions   (:assertions test-results 0)
          tokens       (+ (:input token-usage 0) (:output token-usage 0))]
      (- (+ (* tests 10) assertions)
         (/ tokens 1000)))))

(defn gather-context
  "Assemble context for the reflect prompt.
   Returns {:priorities str-or-nil
            :generations [{:generation N :outcome kw :program-md str :report map :fitness-score N} ...]
            :latest-gen N-or-nil
            :codebase {:fitness-fn str-or-nil :tool-definitions str-or-nil
                       :reflect-prompt str-or-nil :loop-system-prompt str-or-nil}}"
  [repo lookback]
  (let [priorities  (read-priorities repo)
        all-gens    (read-generations repo)
        recent-gens (take-last lookback all-gens)
        enriched    (mapv (fn [gen]
                            (let [n      (:generation gen)
                                  report (read-report repo n)
                                  prog   (read-program-md repo n)]
                              {:generation    n
                               :outcome       (:outcome gen)
                               :program-md    prog
                               :report        report
                               :fitness-score (fitness-score-from-report report)}))
                          recent-gens)
        latest      (when (seq all-gens)
                      (:generation (last all-gens)))]
    {:priorities  priorities
     :generations enriched
     :latest-gen  latest
     :codebase    (gather-codebase-summary repo)}))

;; ---------------------------------------------------------------------------
;; Prompt building
;; ---------------------------------------------------------------------------

(def ^:private default-reflect-system-prompt
  "You are Prime's improvement planner for the Loom self-modification system.

Your job: given the recent generation history and user priorities, propose the
next program.md — a task spec that a Lab will execute autonomously.

## program.md format

Your output MUST follow this structure:

# Task
<one-line title>

## Requirements
- Bullet list of what the Lab must do

## Acceptance Criteria
- Bullet list of testable success conditions

## Constraints
- Any constraints or things to avoid (optional section)

## Rules

- Keep tasks small and focused — one clear objective per program.md.
- Prefer user priorities (work top-down through the priority list).
- Do NOT repeat an approach that already failed. If a previous generation
  attempted something and was rolled back or failed, try a different angle.
- If no priorities remain and all recent generations succeeded, propose a
  stability or quality improvement (better tests, error handling, docs).
- The Lab has access to: read_file, write_file, edit_file, bash.
  It works in a copy of the repo and its changes are verified before promotion.")

(defn load-reflect-system-prompt
  "Load the reflect system prompt from templates/reflect-system.md relative to
   the given base directory. Falls back to the embedded default if the file is
   missing or unreadable."
  [base-dir]
  (try
    (let [template-path (.join path base-dir "templates" "reflect-system.md")]
      (.readFileSync fs template-path "utf8"))
    (catch :default _e default-reflect-system-prompt)))

(def ^:private system-prompt
  (load-reflect-system-prompt (.resolve path ".")))

(defn- format-gen-entry
  "Format a single generation entry for the prompt."
  [{:keys [generation outcome program-md report fitness-score]}]
  (let [prog-preview (when program-md
                       (let [trimmed (subs program-md 0 (min 500 (count program-md)))]
                         (if (< (count program-md) 500)
                           trimmed
                           (str trimmed "..."))))
        token-usage  (:token-usage report)
        test-results (:test-results report)
        llm-verdict  (:llm-verdict report)]
    (str "### Generation " generation "\n"
         "- **Outcome:** " (name outcome) "\n"
         (when fitness-score
           (str "- **Fitness score:** " (.toFixed fitness-score 1) "\n"))
         (when token-usage
           (str "- **Tokens:** " (:input token-usage) " in / " (:output token-usage) " out\n"))
         (when test-results
           (str "- **Tests:** " (:tests-run test-results 0) " run, "
                (:failures test-results 0) " failures, "
                (:errors test-results 0) " errors"
                (if (:passed? test-results) " (PASSED)" " (FAILED)") "\n"))
         (when llm-verdict
           (str "- **LLM verdict:** "
                (if (:approved llm-verdict) "APPROVED" "REJECTED")
                " (" (name (or (:confidence llm-verdict) :unknown)) ")"
                (when (:summary llm-verdict) (str " — " (:summary llm-verdict)))
                "\n"))
         (if prog-preview
           (str "- **program.md:**\n```\n" prog-preview "\n```\n")
           "- **program.md:** not found\n"))))

(defn- format-codebase-section
  "Format the System Internals section for the prompt.
   Only includes subsections where the file was successfully read (not nil)."
  [codebase]
  (when codebase
    (let [{:keys [fitness-fn tool-definitions reflect-prompt loop-system-prompt]} codebase
          parts (cond-> []
                  fitness-fn
                  (conj (str "### Fitness Function (src/loom/supervisor/fitness.cljs)\n"
                             "```clojure\n" fitness-fn "\n```"))
                  tool-definitions
                  (conj (str "### Tool Definitions (available to Labs)\n"
                             "```clojure\n" tool-definitions "\n```"))
                  reflect-prompt
                  (conj (str "### Current Reflect Prompt Template\n"
                             "```clojure\n" reflect-prompt "\n```"))
                  loop-system-prompt
                  (conj (str "### Agent Loop System Prompt (src/loom/agent/loop.cljs)\n"
                             "```clojure\n" loop-system-prompt "\n```")))]
      (when (seq parts)
        (str "## System Internals\n\n" (str/join "\n\n" parts))))))

(defn build-reflect-prompt
  "Build the system + user messages for the reflect LLM call.
   Returns {:system str :messages [{:role \"user\" :content str}]}
   Accepts an optional :base-dir in context to load the system prompt template
   from a specific directory (useful for testing)."
  [context]
  (let [{:keys [priorities generations latest-gen codebase base-dir]} context
        loaded-system (if base-dir
                        (load-reflect-system-prompt base-dir)
                        system-prompt)
        priorities-section (if priorities
                             (str "## User Priorities\n\n" priorities)
                             "## User Priorities\n\nNo priorities file found. Focus on stability improvements: better test coverage, error handling, or code quality.")
        history-section (if (seq generations)
                          (str "## Recent Generation History\n\n"
                               (str/join "\n" (map format-gen-entry generations)))
                          "## Recent Generation History\n\nNo previous generations. This is the first run — propose an initial task based on the priorities above.")
        state-section (str "## Current State\n\n"
                           (if latest-gen
                             (str "Latest generation: " latest-gen "\n"
                                  "Latest outcome: " (name (:outcome (last generations))) "\n")
                             "No generations have run yet.\n"))
        codebase-section (format-codebase-section codebase)
        user-content (str/join "\n\n"
                               (cond-> [priorities-section
                                        history-section
                                        state-section]
                                 codebase-section (conj codebase-section)
                                 true (conj "Respond with ONLY the program.md content. No preamble, no explanation.")))]
    {:system   loaded-system
     :messages [{:role "user" :content user-content}]}))

;; ---------------------------------------------------------------------------
;; Main function
;; ---------------------------------------------------------------------------

(defn reflect-and-propose
  "Analyze recent generation history and user priorities, then propose the
   next program.md via LLM.
   Returns a promise resolving to:
     {:program-md \"...\" :token-usage {:input N :output N}}
   On any error, resolves with an error string (never rejects)."
  [{:keys [repo_path lookback]
    :or   {repo_path "." lookback 5}}]
  (let [api-key (.. js/process -env -ANTHROPIC_API_KEY)
        model   (or (.. js/process -env -LOOM_MODEL)
                    "claude-sonnet-4-20250514")]
    (if-not api-key
      (js/Promise.resolve "Error: No ANTHROPIC_API_KEY set — cannot run reflect step")
      (try
        (let [context (gather-context repo_path lookback)
              {:keys [system messages]} (build-reflect-prompt context)]
          (-> (claude/send-message
               {:api-key    api-key
                :model      model
                :system     system
                :messages   messages
                :max-tokens 2048})
              (.then (fn [response]
                       (if (:error response)
                         (str "Error: Claude API error: "
                              (or (:message response) (:body response)))
                         (let [text (claude/extract-text response)]
                           (if (str/blank? text)
                             "Error: Claude returned empty response"
                             {:program-md  text
                              :token-usage (:token-usage response)})))))
              (.catch (fn [err]
                        (str "Error: reflect exception: " (.-message err))))))
        (catch :default err
          (js/Promise.resolve (str "Error: reflect exception: " (.-message err))))))))

;; ---------------------------------------------------------------------------
;; Tool definitions and registry
;; ---------------------------------------------------------------------------

(def tool-definitions
  [{:name "reflect_and_propose"
    :description "Analyze recent generation history and user priorities, then propose the next program.md. Returns proposed task spec ready for spawn_lab."
    :input_schema {:type "object"
                   :properties {:repo_path {:type "string" :description "Path to repo (default: current dir)"}
                                :lookback {:type "integer" :description "Recent generations to analyze (default: 5)"}}
                   :required []}}])

(def registry
  {"reflect_and_propose" reflect-and-propose})
