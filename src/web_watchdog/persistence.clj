(ns web-watchdog.persistence
  (:require [clojure.pprint]
            [clojure.tools.reader.edn :as edn]))

(def state-file "state.edn")

;; Regexp pattern objects cannot be serialized to JSON/EDN directly.
;; Therefore we need to modify state before write / after read.

(defn- update-state [state vec-updaters]
  (cond
    (map? state)
    (update-vals state #(update-state % vec-updaters))

    (vector? state)
    (let [[vec-key vec-val] state
          val-updater (get vec-updaters vec-key)]
      (if (and vec-val val-updater)
        (update state 1 val-updater)
        (mapv #(update-state % vec-updaters) state)))

    :else
    state))

(defn state-write-preprocess [state]
  (update-state state {:regexp (fn [^java.util.regex.Pattern p] (.pattern p))}))

(defn state-read-postprocess [state]
  (update-state state {:regexp #(re-pattern %)}))

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
