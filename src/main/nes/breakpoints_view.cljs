(ns nes.breakpoints-view
  "Breakpoints on the page: the list (in the CPU tab), the form for adding
  one, buttons for stopping on writes to an address (in the Memory and
  Lens tabs), and the notice under the screen saying why it stopped."
  (:require [reagent.core :as r]
            [nes.breakpoints :as bp]
            [nes.console :as console]
            [nes.state :refer [db]]))

(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})
(def ^:private muted {:color "#57606a"})

(defn- write-breakpoint-at [addr]
  (some #(when (and (= :write (:kind %)) (= addr (:addr %))
                    (nil? (:end %)) (nil? (:value %)))
           %)
        (console/breakpoints)))

(defn stop-on-write-button
  "A small button that adds (or removes) a breakpoint for writes to `addr`."
  [addr]
  (let [b (write-breakpoint-at addr)]
    [:button {:title (if b
                       "Remove this breakpoint"
                       "Pause the game when it writes to this address (see the CPU tab)")
              :on-click (fn [e]
                          (.preventDefault e)
                          (if b
                            (console/remove-breakpoint! (:id b))
                            (console/add-breakpoint! {:kind :write :addr addr})))
              :style {:font-size "11px" :padding "0 4px" :cursor "pointer"
                      :border "1px solid #d0d7de" :border-radius "3px"
                      :background (if b "#ffebe9" "white")
                      :color (if b "#cf222e" "#57606a")}}
     (if b "● on write" "○ on write")]))

(defn- add-form []
  (let [form (r/atom {:kind "write" :addr "" :end "" :value ""})]
    (fn []
      (let [{:keys [kind addr end value]} @form
            a (bp/parse-addr addr)
            e (when (seq end) (bp/parse-addr end))
            v (when (seq value) (bp/parse-addr value))
            valid? (and a (or (empty? end) (and e (>= e a)))
                        (or (empty? value) (and v (< v 256))))
            input (fn [k placeholder width]
                    [:input {:value (k @form) :placeholder placeholder
                             :style (merge mono {:width width})
                             :on-change #(swap! form assoc k (.. % -target -value))}])
            add! (fn [ev]
                   (.preventDefault ev)
                   (when valid?
                     (console/add-breakpoint!
                      (cond-> {:kind (keyword kind) :addr a}
                        e (assoc :end e)
                        (and v (not= kind "exec")) (assoc :value v)))
                     (reset! form {:kind kind :addr "" :end "" :value ""})))]
        [:form {:on-submit add!
                :style {:display "flex" :gap "6px" :align-items "center" :flex-wrap "wrap"
                        :font-size "13px"}}
         "Stop on"
         [:select {:value kind :on-change #(swap! form assoc :kind (.. % -target -value))}
          (for [k bp/kinds] ^{:key k} [:option {:value (name k)} (bp/kind-names k)])]
         "of" [input :addr "$addr" "64px"]
         "to" [input :end "(end)" "64px"]
         (when (not= kind "exec")
           [:<> "value" [input :value "(any)" "48px"]])
         [:button {:type "submit" :disabled (not valid?)} "Add"]]))))

(defn breakpoints-panel []
  (let [bps (console/breakpoints)
        current (get-in @db [:break :breakpoint :id])]
    [:div
     (if (empty? bps)
       [:div {:style (merge muted {:font-size "13px" :margin-bottom "6px"})}
        "None yet. Click the gutter next to an instruction to stop there, or add one below."]
       [:table {:style {:font-size "13px" :border-collapse "collapse" :margin-bottom "6px"}}
        [:tbody
         (for [{:keys [id enabled? hits] :as b} bps]
           ^{:key id}
           [:tr {:style {:background (when (= id current) "#ffebe9")}}
            [:td [:input {:type "checkbox" :checked (boolean enabled?)
                          :title "On or off"
                          :on-change #(console/update-breakpoint! id update :enabled? not)}]]
            [:td {:style (merge mono {:padding "1px 10px 1px 4px"
                                      :color (if enabled? "#24292f" "#8c959f")})}
             (bp/describe b)]
            [:td {:style (merge muted {:padding "1px 10px"})}
             (when (pos? hits) (str hits (if (= 1 hits) " hit" " hits")))]
            [:td [:button {:on-click #(console/remove-breakpoint! id) :title "Remove"} "×"]]])]])
     [add-form]
     [:div {:style (merge muted {:font-size "12px" :margin-top "6px" :max-width "560px"})}
      "Execute breakpoints stop before the instruction runs. Read and write "
      "breakpoints stop just after the instruction that did it. Reads include "
      "the CPU fetching an instruction's operands. Stepping ignores breakpoints."]]))

(defn break-notice
  "Under the screen, while stopped at a breakpoint: why, and how to go on."
  []
  (when-let [reason (console/break-reason)]
    [:div {:style {:display "flex" :gap "8px" :align-items "center" :flex-wrap "wrap"
                   :background "#ffebe9" :border "1px solid #ff8182" :border-radius "6px"
                   :padding "4px 10px" :font-size "13px" :color "#24292f"}}
     [:strong "Stopped at a breakpoint:"] [:span reason]
     [:span {:style {:flex 1}}]
     [:button {:on-click #(swap! db assoc :inspector-tab :cpu)} "Show code"]
     [:button {:on-click console/resume!} "Continue"]]))
