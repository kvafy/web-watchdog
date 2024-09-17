(ns web-watchdog.persistence
  (:require [clojure.pprint]
            [clojure.tools.reader.edn :as edn]))

(def state-file "state.edn")

(defn save-state! [state file-path]
  (as-> state tmp
    (with-out-str (clojure.pprint/pprint tmp))
    (spit file-path tmp)))

(defn load-state [file-path]
  (try
    (-> file-path slurp edn/read-string)
    ; ok, state simply does not exist
    (catch java.io.FileNotFoundException _ nil)))
