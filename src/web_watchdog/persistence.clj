(ns web-watchdog.persistence
  (:require [clojure.pprint]
            [clojure.tools.reader.edn :as edn]
            [integrant.core :as ig]
            [web-watchdog.utils :as utils]))

(defn save-state! [state file-path]
  (as-> state tmp
    (with-out-str (clojure.pprint/pprint tmp))
    (spit file-path tmp)))

(defn load-state [file-path]
  (try
    (-> file-path slurp edn/read-string)
    ; ok, state simply does not exist
    (catch java.io.FileNotFoundException _ nil)))


;; The application state persister component.

(defmethod ig/init-key ::state-persister [_ {:keys [app-state file-path]}]
  (let [save-state!-debounced (utils/debounce save-state! 1000)]
    (add-watch app-state
               ::state-persister
               (fn [_ _ old-state new-state]
                 (when (not= old-state new-state)
                   (save-state!-debounced new-state file-path)))))
  {:watched-atom app-state})

(defmethod ig/halt-key! ::state-persister [_ {:keys [watched-atom]}]
  (remove-watch watched-atom ::state-persister))
