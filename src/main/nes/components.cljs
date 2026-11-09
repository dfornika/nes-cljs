(ns nes.components
  (:require [cljs.pprint :as pp]
            [clojure.string :as str]
            [reagent.dom.server :as rdom-server]
            [nes.state :refer [db]]))

(defn- split-props
  "Split a component's arguments into [options-map children]."
  [args]
  (if (map? (first args))
    [(first args) (rest args)]
    [{} args]))

(defn- html-attrs
  "The HTML attributes for a component's outermost element.

  Starts from the component's own `attrs` (including its default :style),
  then adds:
    - any option in `opts` the component doesn't use itself (anything not
      in `own-keys`), like :id or :class, as an HTML attribute
    - data-component, naming the component
    - the caller's :style, merged over the default style"
  [component-name opts own-keys attrs]
  (merge attrs
         (apply dissoc opts :style own-keys)
         {:data-component component-name
          :style (merge (:style attrs) (:style opts))}))


(def ^:private callout-colors
  {:info    {:background-color "#ddf4ff" :border-color "#54aeff"}
   :success {:background-color "#dafbe1" :border-color "#4ac26b"}
   :warning {:background-color "#fff8c5" :border-color "#d4a72c"}
   :error   {:background-color "#ffebe9" :border-color "#ff8182"}})

(defn callout
  "A colored box to make a message stand out.

  Options: `:kind` (one of :info :success :warning :error, default :info), `:style`"
  [& args]
  (let [[{:keys [kind] :or {kind :info} :as opts} children] (split-props args)]
    (into [:div (html-attrs "callout" opts [:kind]
                            {:style (merge {:padding "12px 16px"
                                            :border-left "4px solid"
                                            :border-radius "4px"}
                                           (get callout-colors kind (:info callout-colors)))})]
          children)))