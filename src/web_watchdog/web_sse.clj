(ns web-watchdog.web-sse
   (:require [clojure.core.async :as async :refer [<! >!]]
             [clojure.java.io]
             [clojure.string]
             [integrant.core :as ig]
             [ring.core.protocols]
             [web-watchdog.utils :as utils]))

(defn send-periodcally!
  "Sends the message to the given core.async channel in regular intervals."
  [ch interval msg]
  (async/go-loop []
    (<! (async/timeout interval))
    (let [still-open? (>! ch msg)]
      (when still-open? (recur)))))

(defn format-sse [{:keys [id data event retry] :as msg}]
  (if (= msg {})
    (str ":heartbeat" \newline \newline)
    (str (when event (str "event: " event \newline))
         (when data (str "data: " data \newline))
         (when id (str "id: " id \newline))
         (when retry (str "retry: " retry \newline))
         \newline)))

(defn channel->sse-response 
  "Returns the given core.async channel as an SSE streaming response."
  [ch {:keys [on-disconnect] :as _opts}]
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache" 
             "Connection" "keep-alive"}
   :body (reify ring.core.protocols/StreamableResponseBody
           (write-body-to-stream [_body _response out]
             (with-open [writer (clojure.java.io/writer out)]
               (try
                 (loop []
                   (when-let [^String msg (async/<!! ch)]
                     (doto writer (.write msg) (.flush))
                     (recur)))
                 ;; Occurs when a client disconnects.
                 (catch java.io.IOException _)
                 ;; Close the channel after client a disconnects. This will trigger `async/untap`.
                 (finally
                   (async/close! ch)
                   (when on-disconnect (on-disconnect)))))))})

(defn build-broadcasting-sse-handler
  "Builds a synchronous ring handler (i.e. a one arg function that accepts the request map).

   Returns a tuple of:
   * ring handler function.
   * core.async channel whose messages get broadcasted as SSE to all currently connected clients.
     A message is a map with any combination of keys :id, :data, :event, :retry."
  []
  (let [in-chan (async/chan) ;; This is the channel where we'll be broadcasting events.
        mult-chan (async/mult in-chan)
        ;; Handler creates a dedicated channel for the client and `tap`s this channel onto
        ;; the shared broadcasting multichannel.
        handler (fn [_req]
                  (let [client-chan (async/chan (async/dropping-buffer 10)) ; Buffer prevents blocking with slow clients.
                        client-sse-chan (async/map format-sse [client-chan])] ; Transform events to SSE format. 
                    (async/tap mult-chan client-chan)
                    (async/>!! client-chan {:event "connected" :data "dummy"})
                    (channel->sse-response client-sse-chan {:on-disconnect #(async/untap mult-chan client-chan)})))]
    ;; Periodically send an empty message as heartbeat, to detect and untap disconnected clients.
    (send-periodcally! in-chan 10000 {})
    [handler in-chan]))


;; SSE Ring handler component that sends an event whenever the app state changes.

(derive ::state-change-broadcasting-handler :web-watchdog.system/app-state-observer)

(defmethod ig/init-key ::state-change-broadcasting-handler [_ {:keys [app-state debounce-ms]}]
  (let [[handler chan] (build-broadcasting-sse-handler)
        send-broadcast! #(async/>!! chan {:event "app-state-changed" :data "dummy"})
        send-broadcast!-debounced (utils/debounce send-broadcast! debounce-ms)]
    (add-watch app-state
               ::state-change-broadcasting-handler
               (fn [_ _ old-state new-state]
                 (when (not= old-state new-state)
                   (send-broadcast!-debounced))))
    {:handler handler, :watched-atom app-state}))

(defmethod ig/resolve-key ::state-change-broadcasting-handler [_ {:keys [handler]}]
  handler)

(defmethod ig/halt-key! ::state-change-broadcasting-handler [_ {:keys [watched-atom]}]
  (remove-watch watched-atom ::state-change-broadcasting-handler))
