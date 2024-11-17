(ns web-watchdog.core
  (:require [clojure.set]
            [web-watchdog.utils :as utils])
  (:import [org.jsoup Jsoup]))

;; Adding a new site to state.

(defn add-site [app-state site]
  (let [required-keys #{:title :url :email-notification}
        optional-keys #{:content-extractors :schedule}
        all-copied-keys (clojure.set/union required-keys optional-keys)
        template {:id         (str (java.util.UUID/randomUUID))
                  :state      {:last-check-time  nil
                               :next-check-time  0
                               :content-hash     nil
                               :content-snippet  nil
                               :last-change-time nil
                               :fail-counter     0
                               :last-error-time  nil
                               :last-error-msg   nil
                               :ongoing-check "idle"}}]
    (when (not= (count required-keys)
                (-> site (select-keys required-keys) count))
      (throw (IllegalArgumentException.
              (format "Site '%s' is missing (one of the) required keys '%s'" site required-keys))))
    (let [new-site (merge template (select-keys site all-copied-keys))]
      (update app-state :sites conj new-site))))


;; Extracting content from a site.

(defn as-element-or-elements [x]
  (cond
    (instance? org.jsoup.nodes.Element x) x
    (instance? org.jsoup.select.Elements x) x
    (instance? java.lang.String x) (Jsoup/parse x)
    :else (throw (IllegalArgumentException. (format "Cannot handle value of type %s" (type x))))))

(defn as-string [x]
  (cond
    (instance? java.lang.String x) x
    (instance? org.jsoup.nodes.Element x) (.text x)
    (instance? org.jsoup.select.Elements x) (.text x)
    :else (throw (IllegalArgumentException. (format "Cannot handle value of type %s" (type x))))))

(defn assert-elements [x]
  (if (instance? org.jsoup.select.Elements x)
    x
    (throw (IllegalArgumentException. (format "Expected instance of Elements, got %s" (type x))))))

(defn apply-content-extractor
  "Depending on the way how the extractors are chained, the `content` argument can be one of the
   following data types:
       plain String,
       JSoup Element (includes JSoup Document),
       JSoup Elements (result of a CSS/XPath selector)."
  [content [op op-arg]]
  (case op
    :xpath
    (-> content as-element-or-elements (.selectXpath op-arg))
    :css
    (-> content as-element-or-elements (.select op-arg))
    :sort-elements-by-text
    (->> content assert-elements (sort-by #(.text %)) (org.jsoup.select.Elements.))
    :html->text
    (-> content as-element-or-elements (.text))
    :regexp
    (if-let [match (re-find (re-pattern op-arg) (as-string content))]
      (if (vector? match) (second match) match)
      "")))

(defn extract-content [content-str extractor-chain]
  (as-> content-str $
    (reduce apply-content-extractor $ extractor-chain)
    (as-string $)))


;; Checking sites for a change: Core logic.

(defn find-site-by-id
  "Returns a 2-tuple [index site] when found, nil otherwise.

   `app-state` is plain data structure, not an atom."
  [app-state site-id]
  (->> (:sites app-state)
       (map-indexed (fn [i s] [i s]))
       (filter (fn [[_ s]] (= site-id (:id s))))
       first))

(defn common-sites [old-state new-state]
  (->> (concat (:sites old-state) (:sites new-state))
       (group-by :id)
       vals
       (filter #(= 2 (count %)))))

(defn site-change-type [old-site new-site]
  (let [hash  (get-in old-site [:state :content-hash])
        hash' (get-in new-site [:state :content-hash])
        fails  (get-in old-site [:state :fail-counter])
        fails' (get-in new-site [:state :fail-counter])]
    (cond
      (and hash hash' (not= hash hash')) :content-changed
      (and fails (zero? fails) (pos? fails'))  :site-failing
      :else nil)))

(defn update-site-with-download-result
  "Updates the given site with download result.
   `download-result` is a 2-tuple of `[success failure]` where exactly one
   component is non-nil, `success` is a string of the raw site content,
   and `failure` is an `ex-info` object."
  [site download-result]
  (let [[data ex-nfo] download-result
        now (utils/now-ms)
        content (some-> data (extract-content (get site :content-extractors [])))
        hash (some-> content utils/md5)
        changed (and content (not= hash (-> site :state :content-hash)))]
    (cond-> site
      true         (assoc-in [:state :last-check-time] now)
      true         (assoc-in [:state :last-error-msg] (ex-message ex-nfo))
      (not ex-nfo) (assoc-in [:state :content-hash] hash)
      (not ex-nfo) (assoc-in [:state :content-snippet] (utils/truncate-at-max content 50000))
      (not ex-nfo) (assoc-in [:state :fail-counter] 0)
      changed      (assoc-in [:state :last-change-time] now)
      ex-nfo       (update-in [:state :fail-counter] inc)
      ex-nfo       (assoc-in [:state :last-error-time] now))))

(defn check-site [site download-fn]
  (let [[data ex-nfo]  (-> site :url download-fn)]
    (update-site-with-download-result site [data ex-nfo])))
