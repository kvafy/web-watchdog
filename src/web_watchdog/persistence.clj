(ns web-watchdog.persistence
  (:require [clojure.pprint]
            [clojure.tools.reader.edn :as edn]))

(def state-file "state.edn")

;; Regexp pattern objects cannot be serialized to JSON/EDN directly.
;; Therefore we need to modify state before write / after read.

(defn state-write-preprocess [x]
  (cond
    (map? x)
    (update-vals x state-write-preprocess)

    (vector? x)
    (if (and (<= 2 (count x))
             (= :regexp (first x)))
        ; a vector in form `[:regexp <pattern> & rest]`.
      (update x 1 (fn [^java.util.regex.Pattern p] (.pattern p)))
      (mapv state-write-preprocess x))

    :else
    x))

(defn state-read-postprocess [x]
  (cond
    (map? x)
    (update-vals x state-read-postprocess)

    (vector? x)
    (if (and (<= 2 (count x))
             (= :regexp (first x)))
      ; a vector in form `[:regexp <string> & rest]`.
      (update x 1 re-pattern)
      (mapv state-read-postprocess x))

    :else
    x))

(defn save-state! [state]
  (as-> state tmp
    ; make sure to write plain Clojure data digestable by edn reader
    (state-write-preprocess tmp)
    (with-out-str (clojure.pprint/pprint tmp))
    (spit state-file tmp)))

(defn load-state []
  (try
    (-> state-file slurp edn/read-string state-read-postprocess)
    ; ok, state simply does not exist
    (catch java.io.FileNotFoundException ex nil)))
