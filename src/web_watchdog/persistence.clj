(ns web-watchdog.persistence)

(def state-file "state.clj")

(defn save-state! [state]
  (spit state-file (pr-str state)))

(defn load-state []
  (try
    (read-string (slurp state-file))
    (catch java.io.IOException ex nil)))
