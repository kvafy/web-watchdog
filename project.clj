(defproject web-watchdog "0.2.0-SNAPSHOT"
  :description "Tool watching a set of websites (URLs) for changes and availability."
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.draines/postal "1.11.3"]
                 ; web server
                 [ring/ring-jetty-adapter "1.4.0"]
                 [compojure "1.4.0"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-json "0.4.0"]
                 ; web client
                 [org.clojure/clojurescript "1.7.170"]
                 [reagent "0.5.1"]]
  :plugins [[lein-ring "0.8.13"]
            [lein-cljsbuild "1.1.1"]]
  :cljsbuild
    {:builds
      [{:source-paths ["src-cljs"]
        :compiler
          {:output-to "resources/public/js/main.js"
           :optimizations :advanced
           :externs ["resources/externs.js"]}}]}
  :main web-watchdog.server
  :aot [web-watchdog.server]
  :ring {:handler web-watchdog.handler/app}
  :profiles
    {:dev
      {:dependencies [[javax.servlet/servlet-api "2.5"]
                      [ring-mock "0.1.5"]]}})
