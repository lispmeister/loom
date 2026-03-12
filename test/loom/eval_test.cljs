(ns loom.eval-test
  (:require [cljs.test :refer [deftest is async testing]]
            [cljs.js :as cljs]
            [cognitect.transit :as transit]))

;; ---------------------------------------------------------------------------
;; Self-hosted ClojureScript eval in a shadow-cljs AOT build
;; ---------------------------------------------------------------------------
;;
;; Shadow-cljs compiles ClojureScript ahead-of-time, so cljs.core exists
;; as JavaScript but the self-hosted compiler (cljs.js) has no analysis
;; metadata for it. We must:
;;
;; 1. Load the cljs.core analysis cache from shadow-cljs build artifacts
;;    so the analyzer can resolve core vars (+, map, into, etc.)
;; 2. Ensure the target namespace's JS object exists (shadow-cljs uses
;;    module scoping, so cljs.user may not exist as a global)
;;
;; This enables eval-str to work with core functions and special forms.
;; Core macros (defn, let, when, etc.) require a shadow-cljs :bootstrap
;; build that preserves macro source for runtime use — that will be
;; addressed when the Lab eval server is implemented.
;; ---------------------------------------------------------------------------

(def fs (js/require "fs"))

(defn read-transit-json
  "Read and deserialize a transit-JSON file from disk."
  [path]
  (let [json (.readFileSync fs path "utf8")
        reader (transit/reader :json)]
    (transit/read reader json)))

(defn init-compiler-state!
  "Create a self-hosted compiler state and populate it with cljs.core
   analysis data from shadow-cljs build artifacts."
  []
  (let [state (cljs/empty-state)
        cache-path ".shadow-cljs/builds/test/dev/ana/cljs/core.cljs.cache.transit.json"
        cache (read-transit-json cache-path)
        analyzer-cache (:analyzer cache)]
    ;; Load cljs.core analysis (var definitions, protocols, specs)
    (cljs/load-analysis-cache! state 'cljs.core analyzer-cache)
    ;; Ensure the cljs.user JS namespace object exists for def to work.
    ;; In shadow-cljs AOT builds, module scoping means cljs.user may
    ;; not exist as a property on the global cljs object.
    (js/eval "if (typeof cljs.user === 'undefined') { cljs.user = {}; }")
    state))

(defonce compiler-state (init-compiler-state!))

(defn eval-str
  "Evaluate a ClojureScript string in self-hosted mode.
   Returns a promise that resolves to {:value v} or {:error e}."
  [source]
  (js/Promise.
   (fn [resolve _reject]
     (cljs/eval-str
      compiler-state
      source
      "eval-test"
      {:eval    cljs/js-eval
       :ns      'cljs.user
       :context :expr}
      (fn [result]
        (resolve result))))))

;; --- Tests ---

(deftest eval-simple-arithmetic
  (testing "eval-str evaluates arithmetic: (+ 1 2) => 3"
    (async done
           (-> (eval-str "(+ 1 2)")
               (.then (fn [result]
                        (is (= 3 (:value result)))
                        (is (nil? (:error result)))
                        (done)))))))

(deftest eval-nested-expressions
  (testing "eval-str evaluates nested expressions"
    (async done
           (-> (eval-str "(* (+ 2 3) (- 10 4))")
               (.then (fn [result]
                        (is (= 30 (:value result)))
                        (is (nil? (:error result)))
                        (done)))))))

(deftest eval-def-and-fn-star
  (testing "eval-str can define and call a function via special forms"
    (async done
           ;; def and fn* are special forms (not macros), so they work
           ;; without macro bootstrapping
           (-> (eval-str "(def my-double (fn* [x] (* x 2)))")
               (.then (fn [_]
                        (eval-str "(my-double 21)")))
               (.then (fn [result]
                        (is (= 42 (:value result)))
                        (is (nil? (:error result)))
                        (done)))))))

(deftest eval-higher-order-functions
  (testing "eval-str works with higher-order core functions"
    (async done
           (-> (eval-str "(map inc [1 2 3])")
               (.then (fn [result]
                        (is (= '(2 3 4) (:value result)))
                        (is (nil? (:error result)))
                        (done)))))))

(deftest eval-data-structures
  (testing "eval-str produces ClojureScript data structures"
    (async done
           (-> (eval-str "(into {} [[:a 1] [:b 2]])")
               (.then (fn [result]
                        (is (= {:a 1 :b 2} (:value result)))
                        (is (nil? (:error result)))
                        (done)))))))

(deftest eval-if-special-form
  (testing "eval-str handles if special form"
    (async done
           (-> (eval-str "(if (> 3 2) :yes :no)")
               (.then (fn [result]
                        (is (= :yes (:value result)))
                        (is (nil? (:error result)))
                        (done)))))))

(deftest eval-do-special-form
  (testing "eval-str handles do special form (returns last value)"
    (async done
           (-> (eval-str "(do 1 2 3)")
               (.then (fn [result]
                        (is (= 3 (:value result)))
                        (is (nil? (:error result)))
                        (done)))))))

(deftest eval-invalid-form-returns-error
  (testing "eval-str returns error for invalid syntax"
    (async done
           (-> (eval-str "(def)")
               (.then (fn [result]
                        (is (some? (:error result)))
                        (is (nil? (:value result)))
                        (done)))))))

(deftest eval-undefined-var-returns-error
  (testing "eval-str returns error for undefined var reference"
    (async done
           (-> (eval-str "(totally-undefined-symbol-xyz 1 2 3)")
               (.then (fn [result]
                        (is (some? (:error result)))
                        (done)))))))
