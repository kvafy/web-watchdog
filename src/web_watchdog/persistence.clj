(ns web-watchdog.persistence
  (:require [web-watchdog.utils :as utils]
            [clojure.pprint]
            [clojure.tools.reader.edn :as edn]))

(def state-file "state.edn")

(defn save-state! [state]
  (as-> state tmp
        ; make sure to write plain Clojure data digestable by edn reader
        (utils/update-map-keys tmp :re-pattern #(.pattern %))
        (with-out-str (clojure.pprint/pprint tmp))
        (spit state-file tmp)))

(defn load-state []
  (try
    (-> state-file
        slurp
        edn/read-string
        (utils/update-map-keys :re-pattern #(re-pattern %)))
    ; ok, state simply does not exist
    (catch java.io.FileNotFoundException ex nil)))
