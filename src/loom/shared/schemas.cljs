(ns loom.shared.schemas
  "Malli schemas for inter-component communication.
   These are the fixed points — the agent can modify everything else,
   but not these contracts."
  (:require [malli.core :as m]))

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

;; -- Modification cycle (Prime ↔ Supervisor) --

(def Proposal
  [:map
   [:id :string]
   [:description :string]
   [:files [:vector
            [:map
             [:path :string]
             [:content :string]]]]
   [:rationale :string]
   [:parent-version :int]])

(def ProbeResult
  [:map
   [:status [:enum :ok :error :timeout]]
   [:value {:optional true} :any]
   [:elapsed-ms :int]])

(def Verdict
  [:map
   [:proposal-id :string]
   [:decision [:enum :promote :revert]]
   [:evidence [:vector ProbeResult]]
   [:reasoning :string]])
