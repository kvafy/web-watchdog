(ns web-watchdog.persistence
  (:require [web-watchdog.utils :as utils]
            [clojure.tools.reader.edn :as edn]))

(def state-file "state.clj")

(defn save-state! [state]
  (as-> state tmp
        ; make sure to write plain Clojure data digestable by edn reader
        (utils/update-map-keys tmp :re-pattern #(.pattern %))
        (pr-str tmp)
        (spit state-file tmp)))

(defn load-state []
  (try
    (-> state-file
        slurp
        edn/read-string
        (utils/update-map-keys :re-pattern #(re-pattern %)))
    (catch java.io.IOException ex nil)))
