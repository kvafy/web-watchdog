(ns web-watchdog.core
  (:require [web-watchdog.networking :as networking]
            [web-watchdog.utils :as utils]))

(defn common-sites [old-state new-state]
  (->> (concat (:sites old-state) (:sites new-state))
       (group-by #(:url %))
       vals
       (filter #(= 2 (count %)))))

(defn site-change-type [old-site new-site]
  (let [hash  (get-in old-site [:state :content-hash])
        hash' (get-in new-site [:state :content-hash])
        fails  (get-in old-site [:state :fail-counter])
        fails' (get-in new-site [:state :fail-counter])]
    (cond
      (and hash hash' (not= hash hash')) :content-changed
      (and (zero? fails) (pos? fails'))  :site-failing
      :else nil)))

(defn check-site [site]
  (let [now (utils/now-utc)
        [data error]  (-> site :url networking/download)
        hash (some->> data (re-find (:re-pattern site)) utils/md5)
        changed (and data (not= hash (-> site :state :content-hash)))]
    (cond-> site
      true        (assoc-in [:state :last-check-utc] now)
      true        (assoc-in [:state :last-error-msg] error)
      (not error) (assoc-in [:state :content-hash] hash)
      (not error) (assoc-in [:state :fail-counter] 0)
      changed     (assoc-in [:state :last-change-utc] now)
      error       (update-in [:state :fail-counter] inc)
      error       (assoc-in [:state :last-error-utc] now))))

(defn check-sites [sites]
  (reduce (fn [res-sites site]
            (conj res-sites (check-site site)))
          []
          sites))
