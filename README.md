# web-watchdog

Web-watchdog is a utility that watches given websites (URLs in general) for
changes and availability. The interesting portion of the URL content (HTML,
JSON etc.) is defined by a regular expression and changes of the website are
being watched only in context of the portion matched by the regular expression.

Used technologies:

* Languages: Clojure, ClojureScript, JavaScript
* UI: React.js, Reagent, jQuery, Bootstrap

## Prerequisites

* Java Runtime Environment
* Leiningen (build tool for clojure)
* sendmail executable on the PATH


## Running

Execute the following commands to run web-watchdog:

    $ lein uberjar
    $ java -jar target/web-watchdog-standalone*.jar

Open http://localhost:8080 in your browser.

## Configuration

Configuration and state is contained in *state.edn* file, which is created in the current working directory during the first run.

To register a website for watching or to modify definitions of already watched sites, edit the *state.edn* file. See comment in *src/web_watchdog/state.clj* to understand structure/format of the *state.edn* file.

The application requires a restart for the changes to take effect.
