(ns web-watchdog.repl
  (:require [integrant.core :as ig]
            [web-watchdog.core :as core]
            [web-watchdog.networking :as networking]
            [web-watchdog.persistence :as persistence]
            [web-watchdog.system :as system]
            [web-watchdog.utils :as utils]
            [web-watchdog.test-utils :as test-utils])
  (:import [java.time Instant LocalDateTime ZoneId]
           [org.jsoup Jsoup]))

(comment
  ;; Start a web server, but don't automatically check sites.
  ;; This can easily be combined with a running `lein cljsbuild auto` for UI development.

  (def system
    (let [repl-system-cfg (-> system/system-cfg
                              (test-utils/with-fake-email-sender {:verbose true})
                              (test-utils/without-site-checker)
                              )]
      (ig/load-namespaces repl-system-cfg)
      (ig/init repl-system-cfg)))

  (ig/halt! system)

  ;; Trigger a site check.
  (let [scope :due  ; one of {:all, :due}
        state-atom (:web-watchdog.state/app-state system)
        download-fn (:web-watchdog.networking/web-downloader system)]
    (case scope
      :all (swap! state-atom update-in [:sites] core/check-all-sites download-fn)
      :due (swap! state-atom update-in [:sites] core/check-due-sites download-fn (:config @state-atom)))
    nil)

  ;; Inspect the history of fakely sent emails.
  (as-> system $
    (get $ [::system/email-sender ::test-utils/fake-email-sender])
    (get $ :history)
    (deref $)
    (map :subject $))

  (do (swap! (::system/app-state system) assoc-in [:sites 0 :state :content-hash] "123")
      nil)

  ;; Add the `:id` property to sites that don't have it.
  (let [state-file "state.edn"
        add-id (fn [site]
                 (if (some? (:id site))
                   (do (printf "Site [%s] already has an :id\n" (:title site))
                       site)
                   (do (printf "Adding :id to site [%s]\n" (:title site))
                       (let [new-id (.toString (java.util.UUID/randomUUID))]
                         (merge {:id new-id} site)))))]
    (-> (persistence/load-state state-file)
        (update-in [:sites] #(mapv add-id %))
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


  (let [tz (ZoneId/of "Europe/London")
        last-check (-> 1723973070578
                       (Instant/ofEpochMilli)
                       (.atZone tz))
        cron (CronExpression/parse "0 */10 7,8,17,18 * * Mon-Fri")]
    (. cron (next last-check)))

  ;; Weird bug in [clj-cron-parse "0.1.5"] - ignores 7am and 8am.
  (clj-cron-parse/next-date
   (clj-time.coerce/from-long 1723973070578)
   "0 */10 7,8,17,18 * * Mon-Fri"
   "Europe/London")

  (do))
