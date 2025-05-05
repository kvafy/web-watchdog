(ns web-watchdog.core
  (:require [clojure.set]
            [web-watchdog.state :as state]
            [web-watchdog.utils :as utils])
  (:import [org.jsoup Jsoup]))

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
  (let [[data ex-nfo]  (download-fn site)]
    (update-site-with-download-result site [data ex-nfo])))


;; Handling web requests related to sites.

;; Required and optional keys of a site request.
(def site-req-required-keys #{:title :url :email-notification})
(def site-req-optional-keys #{:request :content-extractors :schedule})
(def site-req-considered-keys (clojure.set/union site-req-required-keys site-req-optional-keys))

(defn site-req->site-state [site-req]
  (let [template {:id         (str (java.util.UUID/randomUUID))
                  :state      {:last-check-time  nil
                               :next-check-time  0
                               :content-hash     nil
                               :content-snippet  nil
                               :last-change-time nil
                               :fail-counter     0
                               :last-error-time  nil
                               :last-error-msg   nil
                               :ongoing-check    "idle"}}]
    (let [missing-keys (clojure.set/difference site-req-required-keys (set (keys site-req)))]
      (when (not-empty missing-keys)
        (throw (ex-info (format "Site is missing required key(s) '%s': '%s'" missing-keys site-req)
                        {:site-req site-req, :missing-keys missing-keys}))))
    (merge template (select-keys site-req site-req-considered-keys))))

(defn add-site [app-state site-req]
  (let [site (site-req->site-state site-req)]
    (update app-state :sites conj site)))

(defn update-site [app-state site-req]
  (if-let [[site-idx cur-site] (find-site-by-id app-state (:id site-req))]
    (let [site-req (select-keys site-req site-req-considered-keys)  ;; Sanitize request.
          new-site (merge cur-site site-req)]
      (assoc-in app-state [:sites site-idx] new-site))
    (throw (IllegalArgumentException. (str "Site has no ID, or the site wasn't found: " site-req)))))

(defn delete-site [app-state site-id]
  (let [keep-site? (fn [site] (not= site-id (:id site)))
        new-app-state (update app-state :sites (partial filterv keep-site?))]
    (when (= app-state new-app-state)
      (throw (IllegalArgumentException. (format "Site with id '%s' not found." site-id))))
    new-app-state))

(defn test-site
  "Simulates the outcome of checking the requested site.
   Returns a 2-tuple [<site-content-str>, <error-str>], where exactly one element is set."
  [site-req download-fn]
  (let [error-stage (atom nil)]
    (try
      (let [_ (reset! error-stage "Request is invalid")
            site (site-req->site-state site-req)
            _ (state/validate-site site)
            _ (reset! error-stage "Download failed")
            checked-site (check-site site download-fn)
            site-content   (get-in checked-site [:state :content-snippet])
            download-error (get-in checked-site [:state :last-error-msg])]
        (if download-error
          [nil (str @error-stage ": " download-error)]
          [site-content nil]))
      (catch RuntimeException e
        [nil (str @error-stage ": " (.getMessage e))]))))
