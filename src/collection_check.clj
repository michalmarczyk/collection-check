(ns collection-check
  (:use
    [clojure.pprint])
  (:require
    [clojure.string :as str]
    [clojure.test.check :refer (quick-check)]
    [clojure.test.check
     [generators :as gen]
     [properties :as prop]])
  (:import
    [java.util Collection List Map]))

(set! *warn-on-reflection* false)

;;;

(defn pr-meta [v]
  (if-let [m (meta v)]
    `(with-meta ~v ~m)
    v))

(defn gen-meta [gen]
  (gen/fmap
    (fn [[x mta]]
      (if (instance? clojure.lang.IObj x)
        (with-meta x {:foo meta})
        x))
    (gen/tuple
      gen
      (gen/one-of [gen/int (gen/return nil)]))))

(defn- tuple* [& args]
  (->> args
    (map
      #(if (and (map? %) (contains? % :gen))
         %
         (gen/return %)))
    (apply gen/tuple)))

(defn- ttuple [& args]
  (gen/tuple (apply tuple* args)))

;;;

(defn- seq-actions [element-generator]
  (gen/fmap
    (fn [[pre actions post]]
      (concat [pre] actions [post]))
    (tuple*
      [:seq]
      (gen/list
        (gen/one-of
          [(tuple* :rest)
           (tuple* :map-id)
           (tuple* :cons element-generator)
           (tuple* :conj element-generator)]))
      [:into])))

(defn- transient-actions [& intermediate-actions]
  (gen/fmap
    (fn [[pre actions post]]
      (concat [pre] actions [post]))
    (tuple*
      [:transient]
      (gen/list
        (gen/one-of
          (vec intermediate-actions)))
      [:persistent!])))

(defn- gen-vector-actions [element-generator transient? ordered?]
  (let [element-generator (gen-meta element-generator)
        standard [(ttuple :pop)
                  (ttuple :conj element-generator)
                  (ttuple :assoc (gen/choose 0 1e3) element-generator)]]
    (gen/list
      (gen/one-of
        (concat
          standard
          (when transient?
            [(transient-actions
               (tuple* :conj! element-generator)
               (tuple* :assoc! (gen/choose 0 999) element-generator)
               (tuple* :pop!))])
          (when ordered?
            [(seq-actions element-generator)]))))))

(defn- gen-map-actions [key-generator value-generator transient? ordered?]
  (let [key-generator (gen-meta key-generator)
        value-generator (gen-meta value-generator)
        standard [(ttuple :dissoc key-generator)
                  (ttuple :assoc key-generator value-generator)]]
    (gen/list
      (gen/one-of
        (concat
          standard
          (when transient?
            [(transient-actions
               (tuple* :dissoc! key-generator)
               (tuple* :assoc! key-generator value-generator))])
          (when ordered?
            [(seq-actions (tuple* key-generator value-generator))]))))))

(defn- gen-set-actions [element-generator transient? ordered?]
  (let [element-generator (gen-meta element-generator)
        standard [(ttuple :conj element-generator)
                  (ttuple :disj element-generator)]]
    (gen/list
      (gen/one-of
        (concat
          standard
          (when transient?
            [(transient-actions
               (tuple* :conj! element-generator)
               (tuple* :disj! element-generator))])
          (when ordered?
            [(seq-actions element-generator)]))))))

(defn- transient? [x]
  (instance? clojure.lang.IEditableCollection x))

(defn- build-collections
  "Given a list of actions, constructs two parallel collections that can be compared
   for equivalencies."
  [coll-a coll-b vector-like? actions]
  (let [orig-a coll-a
        orig-b coll-b]
    (reduce
      (fn [[coll-a coll-b prev-actions] [action x y :as act]]

        ;; if it's a vector, we need to choose a valid index
        (let [idx (when (and vector-like? (#{:assoc :assoc!} action))
                    (if (< 900 x)
                      (count coll-a)
                      (int (* (count coll-a) (/ x 1e3)))))
              [action x y :as act] (if idx
                                     [action idx y]
                                     act)
              f (case action
                  :cons #(cons x %)
                  :rest rest
                  :map-id #(map identity %)
                  :seq seq
                  :into [#(into (empty orig-a) %) #(into (empty orig-b) %)]
                  :persistent! persistent!
                  :transient transient
                  :pop #(if (empty? %) % (pop %))
                  :pop! #(if (= 0 (count %)) % (pop! %))
                  :conj #(conj % x)
                  :conj! #(conj! % x)
                  :disj #(disj % x)
                  :disj! #(disj! % x)
                  :assoc #(assoc % x y)
                  :assoc! #(assoc! % x y)
                  :dissoc #(dissoc % x)
                  :dissoc! #(dissoc! % x))
              f-a (if (vector? f) (first f) f)
              f-b (if (vector? f) (second f) f)]

          [(f-a coll-a)
           (f-b coll-b)
           (conj prev-actions act)]))
      [coll-a coll-b []]
      (apply concat actions))))

;;;

;; indirection for 1.4 compatibility
(def reduced* (ns-resolve 'clojure.core 'reduced))

(defn assert-equivalent-collections
  [a b]
  (assert (= (count a) (count b) (.size a) (.size b)))
  (assert (= a b))
  (assert (= b a))
  (assert (.equals ^Object a b))
  (assert (.equals ^Object b a))
  (assert (= (hash a) (hash b)))
  (assert (= (.hashCode ^Object a) (.hashCode ^Object b)))
  (assert (= a b
            (into (empty a) a)
            (into (empty b) b)
            (into (empty a) b)
            (into (empty b) a)))
  (when reduced*
    (assert (= (into (empty a) (take 1 a))
              (reduce #(reduced* (conj %1 %2)) (empty a) a)))
    (assert (= (into (empty b) (take 1 b))
              (reduce #(reduced* (conj %1 %2)) (empty b) b)))))

(defn- meta-map [s]
  (->> s (map #(vector % (meta %))) (into {})))

(defn assert-equivalent-vectors [a b]
  (assert-equivalent-collections a b)
  (assert (= (first a) (first b)))
  (assert (= (map #(nth a %) (range (count a)))
            (map #(nth b %) (range (count b)))))
  (assert (= (map #(a %) (range (count a)))
            (map #(b %) (range (count b)))))
  (assert (= (map #(get a %) (range (count a)))
            (map #(get b %) (range (count b)))))
  (assert (= (map #(.get ^List a %) (range (count a)))
            (map #(.get ^List b %) (range (count b)))))
  (assert (= 0 (compare a b))))

(defn assert-equivalent-sets [a b]
  (assert-equivalent-collections a b)
  (assert (= (set (map #(a %) a))
            (set (map #(b %) b))))
  (assert (= (meta-map a) (meta-map b)))
  (assert (and
            (every? #(contains? a %) b)
            (every? #(contains? b %) a))))

(defn assert-equivalent-maps [a b]
  (assert-equivalent-collections a b)
  (assert (= (set (keys a)) (set (keys b))))
  (let [ks (keys a)]
    (assert (= (map #(get a %) ks)
              (map #(get b %) ks)))
    (assert (= (map #(a %) ks)
              (map #(b %) ks)))
    (assert (= (map #(.get ^Map a %) ks)
              (map #(.get ^Map b %) ks))))
  (assert (and
            (every? #(= (key %) (first %)) a)
            (every? #(= (key %) (first %)) b)))
  (assert (= (meta-map (keys a)) (meta-map (keys b))))
  (assert (every? #(= (meta (a %)) (meta (b %))) (keys a)))
  (assert (every? #(= (val %) (a (key %)) (b (key %))) a)))

;;;

(defn describe-action [[f & rst]]
  (fn [[f & rst]]
    (case f
      'into '(into (empty coll))
      (if (empty? rst)
        (symbol (name f))
        (list*
          (symbol (name f))
          (map pr-meta rst))))))

(defn- assert-not-failed [x]
  (if (:fail x)
    (let [[actions] (-> x :shrunk :smallest)]
      (throw (Exception.
               (str (.getMessage ^Throwable (:result x))
                 "\n  actions = " (->> actions
                                    (apply concat)
                                    (map describe-action)
                                    (list* '-> 'coll)
                                    pr-str))
               (:result x))))
    x))

(defn assert-vector-like
  "Asserts that the given empty collection behaves like a vector."
  ([empty-coll element-generator]
     (assert-vector-like 1e3 empty-coll element-generator nil))
  ([n empty-coll element-generator]
     (assert-vector-like n empty-coll element-generator nil))
  ([n empty-coll element-generator
    {:keys [base ordered?]
     :or {ordered? true
          base []}}]
     (assert-not-failed
       (quick-check n
         (prop/for-all [actions (gen-vector-actions element-generator (transient? empty-coll) ordered?)]
           (let [[a b actions] (build-collections empty-coll base true actions)]
             (assert-equivalent-vectors a b)
             true))))))

(defn assert-set-like
  ([empty-coll element-generator]
     (assert-set-like 1e3 empty-coll element-generator nil))
  ([n empty-coll element-generator]
     (assert-set-like n empty-coll element-generator nil))
  ([n empty-coll element-generator
    {:keys [base ordered?]
     :or {ordered? false
          base #{}}}]
     (assert-not-failed
       (quick-check n
         (prop/for-all [actions (gen-set-actions element-generator (transient? empty-coll) ordered?)]
           (let [[a b actions] (build-collections empty-coll base false actions)]
             (assert-equivalent-sets a b)
             true))))))

(defn assert-map-like
  ([empty-coll key-generator value-generator]
     (assert-map-like 1e3 empty-coll key-generator value-generator nil))
  ([n empty-coll key-generator value-generator]
     (assert-map-like n empty-coll key-generator value-generator nil))
  ([n empty-coll key-generator value-generator
    {:keys [base ordered?]
     :or {ordered? false
          base {}}}]
     (assert-not-failed
       (quick-check n
         (prop/for-all [actions (gen-map-actions key-generator value-generator (transient? empty-coll) ordered?)]
           (let [[a b actions] (build-collections empty-coll base false actions)]
             (assert-equivalent-maps a b)
             true))))))
