(ns nes.ui
  "Building blocks for the site's UI"
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [nes.components :as c]
            [nes.state :refer [reload-count]]))

(defn- error-box [error]
  [c/callout {:kind :error}
   [:strong "Something went wrong while drawing this part of the page:"]
   [:pre {:style {:white-space "pre-wrap" :margin "8px 0"}}
    (str (or (ex-message error) error))]
   [:small "Fix the code and save the file to try again. "
    "The browser console (F12) has more details."]])

(defn error-boundary
  "Renders its children, but if they throw an error while rendering, shows
  the error instead of breaking the whole page. Retries after the next hot
  reload, so fixing the code and saving clears the error."
  [& _children]
  (r/create-class
   {:display-name "ErrorBoundary"
    ;; React calls this when a child throws, and immediately re-renders
    ;; with the state it returns - so the error has to be recorded here.
    :get-derived-state-from-error (fn [error]
                                    #js {:error error
                                         :reload @reload-count})
    :reagent-render
    (fn [& children]
      (let [state (.-state (r/current-component))]
        (if (and state
                 (.-error state)
                 ;; deref'ing reload-count here also re-renders us after
                 ;; every hot reload, which is when we retry the children
                 (= (.-reload state) @reload-count))
          [error-box (.-error state)]
          (into [:<>] children))))}))


