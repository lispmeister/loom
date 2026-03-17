(ns loom.shared.schemas
  "Malli schemas for inter-component communication.
   These are the fixed points — the agent can modify everything else,
   but not these contracts.

   The eval protocol schemas are used by the eval server/client subsystem.
   The self-modification cycle uses direct HTTP JSON payloads documented
   in PLAN.md (POST /spawn, /promote, /rollback).")

;; -- Eval protocol (Prime ↔ Lab) --

(def EvalRequest
  [:map
   [:form :string]
   [:timeout {:optional true} :int]])

(def EvalResponse
  [:map
   [:status [:enum :ok :error]]
   [:value {:optional true} :any]
   [:message {:optional true} :string]])

;; -- Schema version registry --
;;
;; Each entry tracks the version of the corresponding schema shape.
;; Increment the version number when a schema's shape changes (with user approval).
;; Do NOT change the schemas above without explicit approval — see CLAUDE.md.

(def schema-versions
  {:EvalRequest  1
   :EvalResponse 1})

(defn schema-version
  "Returns the version number for the given schema keyword, or nil if unknown."
  [k]
  (get schema-versions k))

(defn all-schema-versions
  "Returns the full schema-version map. Useful for serialization and drift detection."
  []
  schema-versions)
