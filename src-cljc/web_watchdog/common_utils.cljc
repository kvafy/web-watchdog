(ns web-watchdog.common-utils)

(defn keywords-to-strings [m]
  (into {} (map (fn [[k v]]
                  (let [k' (cond (keyword? k) (name k)
                                 :else        k)
                        v' (cond (keyword? v) (name v)
                                 (map? v)     (keywords-to-strings v)
                                 :else        v)]
                    [k' v']))
                m)))

(defn unkeywordize-maps-in-site-request-field
  "Undo keywordization inside map values of the `:request` field, which is done
   for convenience by `js->clj` and by the ring JSON middleware.
   E.g. site having {... :request {:headers {:Accept    \"text/xml\"}}} gets
   transformer into {... :request {:headers {\"Accept\" \"text/xml\"}}}."
  [site]
  (if-let [request-map (:request site)]
    (cond-> site
      (contains? request-map :headers)      (update-in [:request :headers] keywords-to-strings)
      (contains? request-map :query-params) (update-in [:request :query-params] keywords-to-strings)
      (contains? request-map :form-params)  (update-in [:request :form-params] keywords-to-strings))
    site))
