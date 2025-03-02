(defproject web-watchdog "0.3.0-SNAPSHOT"
  :description "Tool watching a set of websites for content changes and availability."
  :url "https://github.com/kvafy/web-watchdog"
  :main web-watchdog.server
  :aot [web-watchdog.server]
  :dependencies [[org.clojure/clojure "1.11.4"]
                 [integrant "0.11.0"]
                 [org.clojure/core.async "1.6.681"]
                 [prismatic/schema "1.4.1"]
                 [org.jsoup/jsoup "1.18.1"]
                 [plumula/diff "0.1.1"]
                 [com.cronutils/cron-utils "9.2.0"]
                 [com.draines/postal "2.0.5"]
                 [clj-http "3.13.0"]
                 ; web server
                 [ring/ring-core "1.12.2"]
                 [org.ring-clojure/ring-core-protocols "1.12.2"]
                 [ring/ring-defaults "0.5.0"]
                 [ring/ring-jetty-adapter "1.12.2"]
                 [ring/ring-json "0.5.1"]
                 [compojure "1.7.1"]]
  :plugins [[lein-ring "0.12.6"]
            [lein-cljsbuild "1.1.8"]]
  :cljsbuild
    {:builds
      [{:source-paths ["src-cljs"]
        :compiler
          {:output-to "resources/public/js/main.js"
           :optimizations :advanced
           :externs ["resources/externs.js"]}}]}
  :profiles
    {:provided
     {:dependencies [[org.clojure/clojurescript "1.11.132"]
                     [reagent "1.2.0"]
                     [cljsjs/react "18.2.0-1"]     ;; required by reagent
                     [cljsjs/react-dom "18.2.0-1"] ;; required by reagent
                     ]}
     :dev
      {:dependencies [[cheshire "5.13.0"]
                      [ring/ring-mock "0.4.0"]]}
     :uberjar
     {:aot :all}})
