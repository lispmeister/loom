(ns loom.schemas-test
  "Tests for schema version metadata in loom.shared.schemas."
  (:require [cljs.test :refer [deftest is testing]]
            [loom.shared.schemas :as schemas]))

(deftest schema-versions-contains-expected-schemas-test
  (testing "schema-versions map contains all expected schema keys"
    (let [versions (schemas/all-schema-versions)]
      (is (contains? versions :EvalRequest))
      (is (contains? versions :EvalResponse)))))

(deftest schema-versions-are-positive-integers-test
  (testing "every version number is a positive integer"
    (doseq [[_k v] (schemas/all-schema-versions)]
      (is (integer? v))
      (is (pos? v)))))

(deftest schema-version-returns-version-for-known-schema-test
  (testing "schema-version returns a version number for known schemas"
    (is (= 1 (schemas/schema-version :EvalRequest)))
    (is (= 1 (schemas/schema-version :EvalResponse)))))

(deftest schema-version-returns-nil-for-unknown-schema-test
  (testing "schema-version returns nil for an unknown schema keyword"
    (is (nil? (schemas/schema-version :NonExistentSchema)))))

(deftest all-schema-versions-returns-map-test
  (testing "all-schema-versions returns a map"
    (is (map? (schemas/all-schema-versions)))))

(deftest all-schema-versions-covers-all-known-schemas-test
  (testing "all-schema-versions covers every expected schema"
    (let [expected #{:EvalRequest :EvalResponse}
          actual   (set (keys (schemas/all-schema-versions)))]
      (is (= expected actual)))))
