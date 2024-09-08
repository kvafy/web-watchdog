(ns web-watchdog.persistence
  (:require [clojure.pprint]
            [clojure.tools.reader.edn :as edn]))

(def state-file "state.edn")

(defn save-state! [state]
  (as-> state tmp
    (with-out-str (clojure.pprint/pprint tmp))
    (spit state-file tmp)))

(defn load-state []
  (try
    (-> state-file slurp edn/read-string)
    ; ok, state simply does not exist
    (catch java.io.FileNotFoundException _ nil)))
