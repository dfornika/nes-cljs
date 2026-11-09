(ns nes.main
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdomc]
            [nes.app :as app]
            [nes.state :refer [db reload-count]]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]))

(defonce root (atom nil))

(defonce root
  (when (exists? js/document)
    (rdomc/create-root (.getElementById js/document "app"))))

(defn render
  "Render the app into the root app div."
  []
  (rdomc/render @root [app/app]))

(defn ^:dev/after-load re-render
  "Hot-reload hook called by shadow-cljs after code changes.
  Re-renders from root so that new component definitions take effect.
  State is preserved because it lives in `defonce` app state db atom."
  []
  (swap! reload-count inc)
  (render))

(defn main
  "Main entrypoint."
  []
  (render))

(defn ^:export init
  "Exported entry point called by shadow-cljs on page load."
  []
  (when-let [el (.getElementById js/document "app")]
    (reset! root (rdomc/create-root el)))
  (main))
