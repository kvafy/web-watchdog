(ns web-watchdog.core
  (:require [web-watchdog.networking :as networking]
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
  [html-str [op & op-args]]
  (when-not (nil? html-str)
    (let [doc (. Jsoup parse html-str)]
      (case op
        :xpath
        (-> doc (.selectXpath (first op-args)) process-select-result)
        :css
        (-> doc (.select (first op-args)) process-select-result)
        :html->text
        (-> doc (.text))
        :regexp
        (when-let [match (re-find (first op-args) html-str)]
          (if (vector? match) (second match) match))))))

(defn extract-content [data extractor-chain]
  (reduce apply-content-extractor data extractor-chain))

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
        content (some-> data (extract-content (get site :content-extractors [])))
        hash (some-> content utils/md5)
        changed (and content (not= hash (-> site :state :content-hash)))]
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
