# web-watchdog

*Web-watchdog* is a utility with a web front-end that checks configured set of
websites (URLs in general) for changes of content and/or availability, sending
email notifications when the content changes or site goes down.

Each website can be checked according to a custom schedule defined with a
[CRON expression](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/support/CronExpression.html#parse(java.lang.String)).

The watched portion of a website content can be narrowed down by CSS and
XPath selectors, regular expressions, or their arbitrary combinations.

Example use cases:

* New articles on a blog or podcast.
* Disruptions of public transport links that are relevant to me.
* Price changes and discounts.
* ...

Used technologies:

* Languages: Clojure, ClojureScript, JavaScript
* Server: JSoup, Ring, Jetty
* UI: React.js, Reagent, jQuery, Bootstrap

## Prerequisites

* JRE 21+
* Leiningen (build tool for Clojure)

## Running

Execute the following commands to run web-watchdog:

    ```
    export MAILER_USER=<gmail-account>
    export MAILER_PASSWORD=<app-password>

    lein do clean, cljsbuild once, uberjar
    java -jar target/web-watchdog-*-standalone.jar
    ```

Open http://localhost:8080 in your browser.

Alternatively, you can install web-watchdog as a _systemd_ service. See the
instructions in `scripts/web-watchdog`.

## Configuration

Configuration and state is contained in *state.edn* file, which is created in
the current working directory during the first run.

Watched websites can be added/edited through the web UI. The global app config
(e.g. the default check schedule) can be adjusted only manually by editting the
*state.edn* file. The application requires a restart for config changes to take
effect.

### Internals

The `:content-extractors` field in a site defines a sequence of extractors that
are applied to the website HTML to narrow down the checked content and can be
configured with the following entries:

* `[:css "<selector>"]` narrows down the current content by a CSS selector.
* `[:xpath "<selector>"]` narrows down the current content by an XPath
  selector.
* `[:sort-elements-by-text]` takes the output of a previous CSS or XPath
  selector (which, in general, is a sequence of DOM elements) and sorts these
  elements by their text content.
* `[:html->text]` extracts normalized plain text from the current HTML 
  element(s) and all their children.
* `[:regexp "<regexp>"]` narrows down the current content to the first match
  of the regex. If the regex contains a capturing group, contents of the group
  is taken instead.
* If the `:content-extractors` field is omitted, the whole website is checked.

Examples:

* Check the whole contents of the URL (equivalent to not specifying the
  `:content-extractors` field):
  ```
  :content-extractors [[:regexp ".*"]]
  ```
  
* Extract item price (first narrow down to the right element using CSS
  selectors, extract the plaintext from possibly nested HTML elements,
  and finally carve out the number from *"Only $56.99"* string):
  ```
  :content-extractors [[:css "#content"]
                       [:css "#price"]
                       [:html->text]
                       [:regexp "Only \\$([0-9.]+)"]]
  ```
