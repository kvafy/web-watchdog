(ns web-watchdog.repl
  (:require [integrant.core :as ig]
            [web-watchdog.core :as core]
            [web-watchdog.networking :as networking]
            [web-watchdog.persistence :as persistence]
            [web-watchdog.scheduling :as scheduling]
            [web-watchdog.state :as state]
            [web-watchdog.system :as system]
            [web-watchdog.utils :as utils]
            [web-watchdog.test-utils :as test-utils])
  (:import [org.jsoup Jsoup]))

;; For the reloaded workflow.

(defonce repl-system nil)

(defn reloaded-start [system-cfg]
  (when (some? repl-system)
    (throw (IllegalStateException. "A system already appears to be running.")))
  (alter-var-root #'repl-system
                  (fn [_]
                    (ig/load-namespaces system-cfg)
                    (ig/init system-cfg)))
  nil)

(defn reloaded-stop []
  (when (some? repl-system)
    (ig/halt! repl-system)
    (alter-var-root #'repl-system (constantly nil))))


(comment
  ;; In dev, the system can be started with various modifications (e.g. fake
  ;; email sender, or without CRON schedules.)
  ;; If the web serer was started, it can easily be combined with a running
  ;; `lein cljsbuild auto` for UI development.

  (reloaded-start (-> system/system-cfg
                      ;(assoc-in [:web-watchdog.state/file-based-app-state :file-path] "state-debug.edn")
                      ;(assoc-in [:web-watchdog.state/file-based-app-state :save-on-change?] false)
                      ;(dissoc :web-watchdog.scheduling/site-checker)
                      ;(dissoc :web-watchdog.web/server)
                      ;(test-utils/with-fake-email-sender {:verbose true})
                      ))

  (reloaded-stop)

  ;; Triggerring site checks.
  (let [scope :site-0
        app-state-atom (:web-watchdog.state/file-based-app-state repl-system)
        reset-next-check-utc (fn [site] (assoc-in site [:state :next-check-utc] (utils/now-utc)))]
    (case scope
      :all-sites
      (swap! app-state-atom update-in [:sites] #(mapv reset-next-check-utc %))
      :site-0
      (swap! app-state-atom update-in [:sites 0] reset-next-check-utc))
    nil)


  ;; Inspect the history of fakely sent emails.
  (as-> repl-system $
    (get-in $ [:web-watchdog.test-utils/fake-email-sender :history])
    (deref $) ; `:history` holds an atom
    (map :subject $))

  ;; Simulate a content change for a site.
  (let [app-state-atom (:web-watchdog.state/file-based-app-state repl-system)]
    (swap! app-state-atom assoc-in [:sites 0 :state :content-hash] "123")
    nil)

  ;; Backfill a property to all sites in the `state.edn` file.
  (let [state-file "state.edn"
        prop-path [:state :next-check-utc]
        merge-val-fn (constantly {:state {:next-check-utc 0}})
        backfill-site (fn [site]
                        (if (some? (get-in site prop-path))
                          (do (printf "Site '%s' already has '%s' set, skipping\n" (:title site) prop-path)
                              site)
                          (do (printf "Adding '%s' to site '%s'\n" prop-path (:title site))
                              ;; Handles correct both top-level and nested properties.
                              (merge-with merge site (merge-val-fn)))))]
    (-> (persistence/load-state state-file)
        (update-in [:sites] #(mapv backfill-site %))
        (persistence/save-state! state-file)))


  ;; Jsoup playground.
  (.. Jsoup (parse "<span> x <a>hello</a> </span>") (select "span") (select "a#x"))

  (let [^org.jsoup.nodes.Document doc (.. Jsoup (connect "https://tfl.gov.uk/tube-dlr-overground/status/") (get))
        ^org.jsoup.select.Elements board (.. doc (select "#service-status-page-board li[data-line-id=lul-district]"))
        district-status (.. doc (selectXpath "//div[@id='service-status-page-board']//li[@data-line-id='lul-district']//span[contains(@class, 'disruption-summary')]"))]
    (. district-status html)
    board)

  (let [^org.jsoup.nodes.Document doc (.. Jsoup (connect "https://se-radio.net/") (get))
        ^org.jsoup.select.Elements first-episode (.. doc (select ".megaphone-episodes > *:first-child"))]
    (. first-episode text))
  ; se-radio RSS, but the URL in links would be ugly :(
  (let [^org.jsoup.nodes.Document doc (.. Jsoup (connect "https://seradio.libsyn.com/rss") (get))
        ^org.jsoup.select.Elements first-episode (.. doc (select "channel item:first-of-type title"))]
    (. first-episode text))

  (core/extract-content
   (-> "https://tfl.gov.uk/tube-dlr-overground/status/" (networking/download) (first))
   [[:css "#service-status-page-board"]
   ;[:xpath "//div[@id='service-status-page-board']"]

    [:xpath "//li[@data-line-id='lul-district']"]

    [:css "span.disruption-summary"]
   ;[:xpath "//span[contains(@class, 'disruption-summary')]"]

    [:html->text]])

  (core/extract-content
   (-> "https://www.knihydobrovsky.cz/knihy/serie/legie" (networking/download) (first))
   [[:css ".crossroad-products li:last-of-type .title"]
    [:html->text]])

  nil
  )
