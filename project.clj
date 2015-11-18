(defproject web-watchdog "0.1.1-SNAPSHOT"
  :description "Tool watching a set of websites (URLs) for changes and availability."
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.draines/postal "1.11.3"]]
  :main web-watchdog.core
  :aot [web-watchdog.core])
