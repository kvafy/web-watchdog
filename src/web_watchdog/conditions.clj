(ns web-watchdog.conditions
  (:require [clojure.edn :as edn]))

;; A module for evaluating conditional expressions.
;; Currently used to configure circumstances under which a website alert should
;; be skipped.

(def base-env
  {'true true, 'false false
   ;; Implement `and`, `or` as regular functions (macros can't be referenced as values).
   'and (fn [& args]
          (let [and-2 (fn [x y] (boolean (and x y)))]
            (reduce and-2 true args)))
   'or (fn [& args]
         (let [or-2 (fn [x y] (boolean (or x y)))]
           (reduce or-2 false args)))
   'not not
   '< <, '<= <=
   '> >, '>= >=
   '= =, 'not= not=
   })

(defn- build-env-for-site [old-site new-site]
  (let [current-content  (get-in new-site [:state :content-snippet])
        last-change-time (get-in new-site [:state :last-change-time])
        last-check-time  (-> new-site (get-in [:state :last-check-time]) (or 0))  ;; Hack: default 0 to avoid null exceptions.
        last-error-time  (get-in new-site [:state :last-error-time])
        ;; Derived properties.
        prev-check-time  (-> old-site (get-in [:state :last-check-time]) (or 0))  ;; Hack: default 0 to avoid null exceptions.
        check-failed? (= last-check-time last-error-time)
        check-succeeded? (not= last-check-time last-error-time)
        site-changed? (= last-check-time last-change-time)]
    (merge
     base-env
     {'check-failed? check-failed?
      'check-succeeded? check-succeeded?
      'content-matches? (fn [regex]
                          (boolean (re-matches (re-pattern regex) current-content)))
      'last-check-age-secs (-> (- last-check-time prev-check-time) (/ 1000) long)
      'site-changed? site-changed?
      })))


(defn- look-up [sym env]
  (when-not (contains? env sym)
    (throw (IllegalArgumentException. (format "Symbol '%s' not found in environment: %s" sym env))))
  (get env sym))

(defn- eval-expr-impl [expr env]
  (cond
    (list? expr)
    (let [[op-str & args] expr
          op (look-up op-str env)]
      (when-not (fn? op)
        (throw (IllegalArgumentException. (format "Symbol '%s' in expression '%s' is not a function" op-str expr))))
      (apply op (mapv #(eval-expr-impl % env) args)))

    (symbol? expr)
    (look-up expr env)

    ((some-fn boolean? number? string?) expr)
    expr

    :else
    (throw (IllegalArgumentException. (format "Unknown type of expression: %s" expr)))))

(defn eval-expr
  "Evaluates given expression in context of a site with old & new state.

   For example, the following expression checks whether a site changed to empty extracted content
   while the previous site occurred very long time ago:

       (and site-changed?
            (content-matches? \"^$\")
            (< 3600 last-check-age-secs))
  "
  [expr-str [old-site new-site]]
  (eval-expr-impl (edn/read-string expr-str) (build-env-for-site old-site new-site)))


(comment

  (let [now (web-watchdog.utils/now-ms)
        old-site {:state {:last-check-time  (- now (* 1000 3600 4))}}
        new-site {:state {:last-check-time  now
                          :last-change-time now
                          :content-snippet ""}}
        expr (str '(and site-changed? (content-matches? "^$") (< 3600 last-check-age-secs)))]
    (eval-expr expr [old-site new-site]))

  nil
  )
