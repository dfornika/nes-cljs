(ns nes.lens-view
  "The Lens tab: the loaded game's variables, named and decoded by its lens
  (see nes.lens), updating live."
  (:require [reagent.core :as r]
            [nes.breakpoints-view :refer [stop-on-write-button]]
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
    ;; fixed-point values have fractions: three decimal places is plenty
    (and (number? v) (not (int? v))) [:span {:style mono} (js/parseFloat (.toFixed v 3))]
    (number? v) [:span {:style mono} v (when (> v 9) [:span {:style muted} " " (hex v (if (< v 256) 2 4))])]
    (true? v) [:span {:style {:color "#1a7f37"}} "✓ yes"]
    (false? v) [:span {:style muted} "✗ no"]
    (nil? v) [:span {:style muted} "–"]
    (string? v) [:span v]
    (keyword? v) [:span (name v)]
    (and (map? v) (every? scalar? (vals v)))
    [:span (interpose " · " (for [[k x] v] ^{:key k} [:span [:span {:style muted} (name k) " "] [value-view x]]))]
    (and (sequential? v) (empty? v)) [:span {:style muted} "none"]
    (and (sequential? v) (every? string? v))
    [:div {:style (merge mono {:font-size "12px" :white-space "pre"})}
     (for [[i line] (map-indexed vector v)] ^{:key i} [:div line])]
    (and (sequential? v) (every? map? v) (apply = (map (comp set keys) v)))
    [table-of-maps v]
    (and (sequential? v) (every? scalar? v))
    [:span {:style mono} "[" (interpose " " (map str v)) "]"]
    :else [:code (pr-str v)]))

(defn- spec-view
  "Where a field comes from: its type, and an address you can click to see
  it in the Memory tab (or, for ROM, its offset in the .nes file)."
  [[type addr & args]]
  (case type
    :fn [:span {:style muted} "computed"]
    :screen-text [:span {:style muted} "screen text"]
    :rom (let [[t offset & more] addr]
           [:span {:style mono}
            [:span {:style {:background "#fbefff" :color "#8250df" :border-radius "3px"
                            :padding "0 3px" :margin-right "4px"}
                    :title "Read from the cartridge. Changing it changes the game itself (until you load another ROM)."}
             "ROM"]
            (name t) " "
            [:span {:title "Offset in the .nes file (counting its 16-byte header)"}
             "0x" (.padStart (.toUpperCase (.toString offset 16)) 5 "0")]
            (when (#{:array :text} t) [:span {:style muted} " ×" (first more)])])
    [:span {:style mono}
     (name type) " "
     [:a {:href "#" :title "Show in the Memory tab"
          :on-click (fn [e]
                      (.preventDefault e)
                      (memory-view/show! :cpu (- addr (mod addr 16)))
                      (swap! db assoc :inspector-tab :memory))}
      (hex addr 4)]
     (when (and (seq args) (not (#{:enum :bits :text} type)))
       [:span {:style muted} " " (pr-str (if (= type :struct) (vec (take-nth 2 (first args))) (first args)))])
     " " [stop-on-write-button addr]]))

;; ---------------------------------------------------------------------------
;; Changing values

(defn- parse-number
  "A number typed by a person: 42, $2A, 0x2A or (for fixed-point values)
  1.5. nil if it isn't one."
  [text]
  (let [t (.trim (str text))]
    (cond
      (re-matches #"(\$|0x|0X)[0-9a-fA-F]+" t) (js/parseInt (.replace t (js/RegExp. "^(\\$|0x)" "i") "") 16)
      (re-matches #"-?\d+" t) (js/parseInt t 10)
      (re-matches #"-?\d*\.\d+" t) (js/parseFloat t))))

(defn- unwrap [[type inner :as spec]] (if (= type :rom) inner spec))

(defn- commit!
  "Write `value` into field `spec` (at `path`) of the running game."
  [spec path value]
  (when-let [e (console/emulator-instance)]
    (when-let [ws (lens/writes (snapshot/capture e) spec path value)]
      (console/write! ws))))

(defn- editor
  "A value that turns into an input when clicked. `kind` is :number,
  :text, or {:options [...]} for a fixed list."
  [_ _ _ _]
  (let [editing (r/atom nil)]   ; the text being typed, or nil
    (fn [spec path value kind]
      (let [rom? (= :rom (first spec))
            done! (fn [] (reset! editing nil))
            save! (fn [text]
                    (let [v (case kind
                              :number (parse-number text)
                              :text text
                              text)]
                      (when (some? v) (commit! spec path v))
                      (done!)))]
        (cond
          (map? kind)
          [:select {:value (str value)
                    :title (if rom? "Change it in the cartridge" "Change it")
                    :on-change #(commit! spec path (let [v (.. % -target -value)]
                                                     (or (some (fn [o] (when (= v (str o)) o)) (:options kind)) v)))
                    :style {:font-size "13px"}}
           (for [o (distinct (cons value (:options kind)))]
             ^{:key (str o)} [:option {:value (str o)} (str o)])]

          @editing
          [:input {:value @editing :auto-focus true
                   :style (merge mono {:width (if (= kind :text) "200px" "70px") :font-size "13px"})
                   :on-change #(reset! editing (.. % -target -value))
                   :on-blur #(save! @editing)
                   :on-key-down (fn [e]
                                  (case (.-key e)
                                    "Enter" (save! @editing)
                                    "Escape" (done!)
                                    nil)
                                  ;; keep the game from seeing these keys
                                  (.stopPropagation e))}]

          :else
          [:span {:on-click #(reset! editing (str value))
                  :title (str "Click to change" (when rom? " (in the cartridge!)"))
                  :style {:cursor "pointer" :border-bottom (str "1px dashed " (if rom? "#8250df" "#8c959f"))}}
           [value-view value]])))))

(defn- scalar-editor [spec path value]
  (let [[type _ & args] (unwrap spec)
        ;; the type at `path` within an :array or :struct
        elem (fn elem [[t & a :as sp] p]
               (if (empty? p)
                 sp
                 (case t
                   :array (let [e (second (rest a))] (elem (if (vector? e) (into [(first e) 0] (rest e)) [e 0]) (rest p)))
                   :struct (let [fields (second a)
                                 e (some (fn [[k ft]] (when (= k (first p)) ft)) (partition 2 fields))]
                             (elem (if (vector? e) (into [(first e) 0] (rest e)) [e 0]) (rest p)))
                   sp)))
        [t _ names] (elem (unwrap spec) path)]
    (case t
      :bool [:input {:type "checkbox" :checked (boolean value)
                     :on-change #(commit! spec path (not value))}]
      :enum [editor spec path value {:options (vec (vals names))}]
      :text [editor spec path value :text]
      [editor spec path value :number])))

(defn- field-value
  "A field's value, editable where the field allows it."
  [spec value]
  (let [[type _ & args] (unwrap spec)
        part-editable? #(lens/editable? spec %)]
    (cond
      (lens/editable? spec)
      [scalar-editor spec [] value]

      (and (= type :bits) (map? value))
      [:span (for [[k on?] value]
               ^{:key k}
               [:label {:style {:margin-right "10px" :white-space "nowrap" :cursor "pointer"}}
                [:input {:type "checkbox" :checked (boolean on?)
                         :on-change #(commit! spec [k] (not on?))}]
                (name k)])]

      (and (= type :struct) (map? value) (part-editable? [(ffirst value)]))
      [:span (interpose " · "
                        (for [[k v] (let [order (take-nth 2 (first args))]
                                      (map (fn [k] [k (get value k)]) order))]
                          ^{:key k}
                          [:span {:style {:white-space "nowrap"}}
                           [:span {:style muted} (name k) " "]
                           [scalar-editor spec [k] v]]))]

      (and (= type :array) (vector? value) (seq value) (part-editable? [0]))
      [:span {:style mono} "["
       (interpose " " (for [[i v] (map-indexed vector value)]
                        ^{:key i} [scalar-editor spec [i] v]))
       "]"]

      :else [value-view value])))

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

(defn- wide?
  "Values shown as a table get a row of their own, the full width."
  [v]
  (and (sequential? v) (seq v) (every? map? v)))

(defn- fields-table [fields values changed]
  [:table {:style {:border-collapse "collapse" :font-size "14px" :width "100%"}}
   [:tbody
    (for [[k spec] fields
          :let [v (get values k)
                bg (when (changed k) "#fff8c5")
                label-cells [:<>
                             [:td {:style {:padding "6px 12px 6px 0" :vertical-align "top" :font-weight "bold"
                                           :white-space "nowrap"}}
                              (name k)]
                             [:td {:style {:padding "6px 12px 6px 0" :vertical-align "top" :white-space "nowrap"
                                           :font-size "12px"}}
                              [spec-view spec]]]]]
      (if (wide? v)
        ^{:key k}
        [:<>
         [:tr {:style {:border-top "1px solid #eaeef2" :background bg}}
          label-cells [:td]]
         [:tr {:style {:background bg}}
          [:td {:col-span 3 :style {:padding "0 0 8px 12px"}} [field-value spec v]]]]
        ^{:key k}
        [:tr {:style {:border-top "1px solid #eaeef2" :background bg}}
         label-cells
         [:td {:style {:padding "6px 0" :vertical-align "top" :width "100%"}}
          [field-value spec v]]]))]])

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
