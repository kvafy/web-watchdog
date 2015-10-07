(defproject web-watchdog "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [compojure "1.3.1"]
                 [ring/ring-defaults "0.1.2"]
                 [com.draines/postal "1.11.3"]]
  :plugins [[lein-ring "0.8.13"]]
  :main web-watchdog.core
  :aot [web-watchdog.core]
  :ring {:handler web-watchdog.handler/app}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring-mock "0.1.5"]]}})
