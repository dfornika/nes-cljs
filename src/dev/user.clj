(ns user
  "Helpers for the Clojure (JVM) REPL during development.")

(defn portal
  "Start a Portal server on port 5678 and open it in VS Code (needs the
  Portal extension). Then, in the ClojureScript REPL,
  `(nes.dev.portal/connect!)` sends the page's taps to it."
  []
  (let [open (requiring-resolve 'portal.api/open)
        start (requiring-resolve 'portal.api/start)]
    (start {:port 5678})
    (open {:launcher :vs-code})))
