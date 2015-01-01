(ns collection-check-test
  (:require
    [clojure.test :refer :all]
    [collection-check :refer :all]
    [clojure.test.check.generators :as gen]))

(deftest test-identities
  (assert-vector-like 100 [] gen/int)
  (assert-map-like 100 (sorted-map) gen/int gen/int {:base (sorted-map) :ordered? true})
  (assert-map-like 100 {} gen/int gen/int)
  (assert-set-like 100 (sorted-set) gen/int {:base (sorted-set) :ordered? true})
  (assert-set-like 100 #{} gen/int))
