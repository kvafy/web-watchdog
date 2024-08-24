(ns web-watchdog.core
  (:require [web-watchdog.networking :as networking]
            [web-watchdog.scheduling :as scheduling]
            [web-watchdog.utils :as utils])
  (:import [org.jsoup Jsoup]))

(defn process-select-result
  "Post-processes the result of Jsoup `select` or `selectXpath` methods.
   If nothing was matched, returns nil, otherwise converts the result to an HTML string."
  [^org.jsoup.select.Elements els]
  (let [no-match (nil? (. els first))]
    (if no-match nil (. els html))))

(defn apply-content-extractor
  "Each type of extractor takes and produces a string, enabling easy chaining."
  [html-str [op op-arg]]
  (when-not (nil? html-str)
    (let [doc (. Jsoup parse html-str)]
      (case op
        :xpath
        (-> doc (.selectXpath op-arg) process-select-result)
        :css
        (-> doc (.select op-arg) process-select-result)
        :html->text
        (-> doc (.text))
        :regexp
        (when-let [match (re-find (re-pattern op-arg) html-str)]
          (if (vector? match) (second match) match))))))

(defn extract-content [data extractor-chain]
  (reduce apply-content-extractor data extractor-chain))

(defn common-sites [old-state new-state]
  (->> (concat (:sites old-state) (:sites new-state))
       (group-by #(select-keys % [:url :content-extractors]))
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
  (utils/log (format "Checking site [%s] ..." (:title site)))
  (let [now (utils/now-utc)
        [data ex-nfo]  (-> site :url networking/download-with-cache)
        content (some-> data (extract-content (get site :content-extractors [])))
        hash (some-> content utils/md5)
        changed (and content (not= hash (-> site :state :content-hash)))]
    (cond-> site
      true         (assoc-in [:state :last-check-utc] now)
      true         (assoc-in [:state :last-error-msg] (ex-message ex-nfo))
      (not ex-nfo) (assoc-in [:state :content-hash] hash)
      (not ex-nfo) (assoc-in [:state :content-snippet] (utils/truncate-at-max content 200))
      (not ex-nfo) (assoc-in [:state :fail-counter] 0)
      changed      (assoc-in [:state :last-change-utc] now)
      ex-nfo       (update-in [:state :fail-counter] inc)
      ex-nfo       (assoc-in [:state :last-error-utc] now))))

(defn next-check-time [site global-config]
  (let [last-check-utc (get-in site [:state :last-check-utc])]
    (if (nil? last-check-utc)
      (utils/now-utc)
      (let [schedule (or (get site :schedule) (get global-config :default-schedule))
            tz (get global-config :timezone "UTC")
            next-check-utc (scheduling/next-cron-time schedule last-check-utc tz)]
        next-check-utc))))

(defn due-for-check? [site global-config]
  (let [next-check-utc (next-check-time site global-config)
        now-utc (utils/now-utc)]
    (<= next-check-utc now-utc)))

(defn check-sites [pred sites]
  (mapv (fn [site]
          (if (pred site) (check-site site) site))
        sites))

(defn check-all-sites [sites]
  (check-sites (constantly true) sites))

(defn check-due-sites [sites global-config]
  (check-sites #(due-for-check? % global-config) sites))
