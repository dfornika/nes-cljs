(ns nes.dev.portal
  "Portal (https://github.com/djblue/portal) for browsing values sent with
  `tap>`. Development builds only: shadow-cljs loads this as a preload.

  Two ways to use it:

  1. In a popup window, from the page itself:

       (nes.dev.portal/open!)       ; or the \"Open Portal\" button

     Browsers only allow popups in response to a click, so if calling this
     from the REPL does nothing, use the button instead.

  2. In VS Code (with the Portal extension), through a Portal server in
     the Clojure (JVM) REPL:

       ;; in the Clojure REPL (see src/dev/user.clj)
       (user/portal)
       ;; in the ClojureScript REPL
       (nes.dev.portal/connect!)

  Then `(tap> x)`, or `(tap-snap!)` from nes.repl, sends values there."
  (:require [portal.client.web :as remote]
            [portal.web :as web]
            [nes.state :as state]))

;; The tap function we've added, so we never add two.
(defonce ^:private current-tap (atom nil))

(defn- use-tap! [f]
  (when-let [old @current-tap] (remove-tap old))
  (reset! current-tap f)
  (add-tap f))

(defn open!
  "Open Portal in a popup window and send taps to it."
  []
  (web/open)
  (use-tap! web/submit)
  :portal/open)

(def default-port
  "The port `user/portal` starts the JVM Portal server on."
  5678)

(defn connect!
  "Send taps to a Portal server on `port` (default 5678), such as the one
  `user/portal` starts in the Clojure REPL to show in VS Code."
  ([] (connect! default-port))
  ([port]
   (use-tap! #(remote/submit {:port port} %))
   [:portal/connected port]))

(swap! state/dev-tools assoc :portal
       {:label "Open Portal"
        :title "Open the Portal data browser in a popup; values sent with tap> appear there"
        :action open!})
