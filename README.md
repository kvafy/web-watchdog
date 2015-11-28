# web-watchdog

Web-watchdog is a utility that watches given websites (URLs in general) for
changes and availability. The interesting portion of the URL content (HTML,
JSON etc.) is defined by a regular expression and changes of the website are
being watched only in context of the portion matched by the regular expression.

Used technologies:

* Languages: Clojure, ClojureScript, JavaScript
* UI: React.js, Reagent, jQuery

## Prerequisites

You will need *Leiningen* and Java runtime installed to compile and run
the application.


## Running

Execute the following commands to run web-watchdog:

    $ lein uberjar
    $ java -jar target/web-watchdog-standalone*.jar

Open http://localhost:8080 in your browser.

## Configuration

Edit the *state.clj* file in your current working directory to add
new watched websites or modify definitions of existing ones. The
application requires a restart for the changes to take effect.
