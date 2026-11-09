(ns nes.lens-view
  "The Lens tab: the loaded game's variables, named and decoded by its lens
  (see nes.lens), updating live."
  (:require [reagent.core :as r]
            [nes.console :as console]
            [nes.cpu.disasm :refer [hex]]
            [nes.lens :as lens]
            [nes.memory-view :as memory-view]
            [nes.snapshot :as snapshot]
            [nes.state :refer [db dev-tools]]))

(def ^:private muted {:color "#57606a"})
(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})

;; The previous refresh's values, so changed ones can be highlighted.
(defonce ^:private previous (atom {:tick nil :values nil :changed #{}}))

(defn- track-changes
  "Remember `values` for this `tick`; return the set of keys that changed
  since the last tick."
  [tick values]
  (let [{prev-tick :tick prev :values changed :changed} @previous]
    (if (= tick prev-tick)
      changed
      (let [changed (if prev
                      (set (filter #(not= (get prev %) (get values %)) (keys values)))
                      #{})]
        (reset! previous {:tick tick :values values :changed changed})
        changed))))

;; ---------------------------------------------------------------------------
;; Showing values

(declare value-view)

(defn- scalar? [v] (or (number? v) (boolean? v) (string? v) (keyword? v) (nil? v)))

(defn- table-of-maps
  "A vector of maps with the same keys, as a table."
  [rows]
  (let [cols (keys (first rows))]
    [:table {:style {:border-collapse "collapse" :font-size "12px"}}
     [:thead
      [:tr (for [c cols] ^{:key c} [:th {:style (merge muted {:text-align "left" :padding "0 10px 2px 0"
                                                              :font-weight "normal"})}
                                    (name c)])]]
     [:tbody
      (for [[i row] (map-indexed vector rows)]
        ^{:key i}
        [:tr (for [c cols] ^{:key c} [:td {:style {:padding "0 10px 0 0"}} [value-view (get row c)]])])]]))

(defn value-view
  "A decoded value, shown as readably as we can manage."
  [v]
  (cond
    (number? v) [:span {:style mono} v (when (> v 9) [:span {:style muted} " " (hex v (if (< v 256) 2 4))])]
    (true? v) [:span {:style {:color "#1a7f37"}} "✓ yes"]
    (false? v) [:span {:style muted} "✗ no"]
    (nil? v) [:span {:style muted} "–"]
    (and (map? v) (every? scalar? (vals v)))
    [:span (interpose " · " (for [[k x] v] ^{:key k} [:span [:span {:style muted} (name k) " "] [value-view x]]))]
    (and (sequential? v) (empty? v)) [:span {:style muted} "none"]
    (and (sequential? v) (every? map? v) (apply = (map (comp set keys) v)))
    [table-of-maps v]
    (and (sequential? v) (every? scalar? v))
    [:span {:style mono} "[" (interpose " " (map str v)) "]"]
    :else [:code (pr-str v)]))

(defn- spec-view
  "Where a field comes from: its type, and an address you can click to see
  it in the Memory tab."
  [[type addr & args]]
  (if (= type :fn)
    [:span {:style muted} "computed"]
    [:span {:style mono}
     (name type) " "
     [:a {:href "#" :title "Show in the Memory tab"
          :on-click (fn [e]
                      (.preventDefault e)
                      (memory-view/show! :cpu (- addr (mod addr 16)))
                      (swap! db assoc :inspector-tab :memory))}
      (hex addr 4)]
     (when (seq args)
       [:span {:style muted} " " (pr-str (if (= type :struct) (vec (take-nth 2 (first args))) (first args)))])]))

;; ---------------------------------------------------------------------------

(def ^:private example
  "{:lives  [:u8 0x075A]
 :score  [:digits 0x07DD 6]
 :player [:struct 0x0086 [:x :u8 :y :u8]]}")

(defn- no-lens [rom-name]
  [:div {:style {:font-size "14px"}}
   [:p {:style {:margin-top 0}}
    "There's no lens for " [:code (or rom-name "this ROM")] " yet. "
    "A lens names the bytes where a game keeps its state, so you can see "
    "\"lives: 3\" instead of \"$075A = $03\"."]
   [:p "To make one, find the addresses from the REPL with " [:code "snap"] ", "
    [:code "diff"] " and " [:code "search"] " (see " [:code "nes.repl"] "), then:"]
   [:pre {:style {:background "#f6f8fa" :padding "8px" :font-size "12px"}}
    "(register-lens! " (pr-str (or rom-name "game.nes")) "\n  " example ")"]
   [:p {:style muted} "Field types are listed in " [:code "nes.lens"]
    ". Concentration Room (the sample ROM) has one to look at: "
    [:code "src/main/nes/lenses/croom.cljs"] "."]])

(defn lens-view []
  (let [tick @console/inspect-tick
        rom-name (get-in @db [:rom :name])
        portal (:portal @dev-tools)]
    (if-let [e (console/emulator-instance)]
      (if-let [l (lens/lens-for rom-name)]
        (let [s (snapshot/capture e)
              values (lens/interpret l s)
              changed (track-changes tick values)]
          [:div
           [:div {:style {:display "flex" :justify-content "space-between" :align-items "baseline"
                          :gap "12px" :flex-wrap "wrap"}}
            [:p {:style (merge muted {:margin-top 0 :font-size "14px"})}
             "The game's state through its lens: names for the bytes it keeps its "
             "variables in. Values that just changed are highlighted."]
            (when portal
              [:button {:on-click #(tap> values) :title "Send these values to tap> (Portal)"}
               "tap> values"])]
           [:table {:style {:border-collapse "collapse" :font-size "14px" :width "100%"}}
            [:tbody
             (for [[k spec] l]
               ^{:key k}
               [:tr {:style {:border-top "1px solid #eaeef2"
                             :background (when (changed k) "#fff8c5")}}
                [:td {:style {:padding "6px 12px 6px 0" :vertical-align "top" :font-weight "bold"
                              :white-space "nowrap"}}
                 (name k)]
                [:td {:style {:padding "6px 12px 6px 0" :vertical-align "top" :white-space "nowrap"
                              :font-size "12px"}}
                 [spec-view spec]]
                [:td {:style {:padding "6px 0" :vertical-align "top" :width "100%"}}
                 [value-view (get values k)]]])]]])
        [no-lens rom-name])
      [:p {:style muted} "Load a ROM to see its lens."])))
