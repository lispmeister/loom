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
