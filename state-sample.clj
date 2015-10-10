
(def sample-state
    {:sites [{:title      "European LISP Symposium"
              :url        "http://www.european-lisp-symposium.org/content-programme-full.html"
              :re-pattern #"(?s).*"
              :emails     ["happy@lisper.com"]
              :state      {:content-hash nil}}]
     :config {:check-interval-ms (* 1000 60 60)}})

