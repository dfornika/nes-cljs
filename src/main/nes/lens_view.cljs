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
(defonce ^:private previous (atom {:tick nil :lens nil :values nil :changed #{}}))

(defn- track-changes
  "Remember lens `lens-name`'s `values` for this `tick`; return the set of
  keys that changed since the last tick. (Switching lenses starts afresh.)"
  [tick lens-name values]
  (let [{prev-tick :tick prev-lens :lens prev :values changed :changed} @previous]
    (if (and (= tick prev-tick) (= lens-name prev-lens))
      changed
      (let [changed (if (and prev (= lens-name prev-lens))
                      (set (filter #(not= (get prev %) (get values %)) (keys values)))
                      #{})]
        (reset! previous {:tick tick :lens lens-name :values values :changed changed})
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
  "(lens! \"Player\"
  {:lives  [:u8 0x075A]
   :score  [:digits 0x07DD 6]
   :player [:struct 0x0086 [:x :u8 :y :u8]]})")

(defn- no-lens []
  [:div {:style {:font-size "14px" :background "#f6f8fa" :border "1px solid #d0d7de"
                 :border-radius "6px" :padding "8px 12px" :margin-bottom "12px"}}
   [:p {:style {:margin-top 0}}
    "This game has no lens of its own yet. "
    "A lens names the bytes where a game keeps its state, so you can see "
    "\"lives: 3\" instead of \"$075A = $03\"."]
   [:p "Find the addresses from the REPL with " [:code "snap"] ", "
    [:code "diff"] " and " [:code "search"] " (see " [:code "nes.repl"] "), then try a lens out:"]
   [:pre {:style {:background "white" :padding "8px" :font-size "12px" :margin "0 0 8px"}} example]
   [:p {:style (merge muted {:margin-bottom 0})}
    "It appears here straight away; run it again to change it. "
    [:code "(lens-source)"] " prints it as code to keep in a file. Field types are listed in "
    [:code "nes.lens"] "; " [:code "src/main/nes/lenses/croom.cljs"] " has examples."]])

(defn- copy-button [text]
  (let [copied? (r/atom false)]
    (fn [text]
      [:button {:title "Copy this lens as ClojureScript, to paste into a file"
                :on-click (fn []
                            (-> (js/navigator.clipboard.writeText text)
                                (.then (fn []
                                         (reset! copied? true)
                                         (js/setTimeout #(reset! copied? false) 1500)))))}
       (if @copied? "Copied ✓" "Copy as code")])))

(defn- picker [lenses current]
  [:div {:style {:display "flex" :gap "6px" :flex-wrap "wrap" :margin-bottom "8px"}}
   (for [{:keys [name matched-by]} lenses
         :let [on? (= name current)]]
     ^{:key name}
     [:button {:on-click #(swap! db assoc :lens-name name)
               :title (case matched-by
                        :sha1 "For this game (matched by its SHA-1)"
                        :file-name "For this game (matched by file name)"
                        :any "For every game")
               :style {:border-radius "12px" :padding "2px 12px" :cursor "pointer"
                       :border (if on? "1px solid #0969da" "1px solid #d0d7de")
                       :background (if on? "#ddf4ff" "white")
                       :color (if (= matched-by :any) "#57606a" "#24292f")
                       :font-weight (when on? "bold")}}
      name])])

(defn- fields-table [fields values changed]
  [:table {:style {:border-collapse "collapse" :font-size "14px" :width "100%"}}
   [:tbody
    (for [[k spec] fields]
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
        [value-view (get values k)]]])]])

(defn lens-view []
  (let [tick @console/inspect-tick
        rom (:rom @db)
        portal (:portal @dev-tools)]
    (if-let [e (console/emulator-instance)]
      (let [lenses (lens/lenses-for rom)
            own? (some #(not= :any (:matched-by %)) lenses)
            {:keys [name doc fields matched-by] :as current}
            (or (some #(when (= (:lens-name @db) (:name %)) %) lenses)
                (first lenses))
            s (snapshot/capture e)
            values (lens/interpret fields s)
            changed (track-changes tick name values)]
        [:div
         (when-not own? [no-lens])
         [:div {:style {:display "flex" :justify-content "space-between" :align-items "flex-start"
                        :gap "12px" :flex-wrap "wrap"}}
          [picker lenses name]
          [:div {:style {:display "flex" :gap "6px"}}
           [copy-button (lens/source current (:sha1 rom))]
           (when portal
             [:button {:on-click #(tap> values) :title "Send these values to tap> (Portal)"}
              "tap> values"])]]
         [:p {:style (merge muted {:margin "0 0 8px" :font-size "13px"})}
          (when doc [:span doc " "])
          (case matched-by
            :file-name "(Matched by file name: copy it as code to match by the ROM's hash instead.) "
            nil)
          "Values that just changed are highlighted."]
         [fields-table fields values changed]])
      [:p {:style muted} "Load a ROM to see its lenses."])))
