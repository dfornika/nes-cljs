(ns nes.instructions-view
  "The Instructions tab: a reference to the 6502's instructions and
  addressing modes, in plain English. Mnemonics in the CPU tab link here."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [nes.cpu.disasm :refer [hex]]
            [nes.cpu.opcodes :as ops]
            [nes.cpu.reference :as ref]
            [nes.state :refer [db]]))

(def ^:private muted {:color "#57606a"})
(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})

;; The instruction last scrolled to, so re-rendering doesn't scroll again
(defonce ^:private scrolled-to (atom nil))

(defn show!
  "Open the Instructions tab at `mnemonic` (a keyword like :lda)."
  [mnemonic]
  (reset! scrolled-to nil)
  (swap! db assoc :inspector-tab :instructions :instruction-focus mnemonic :instruction-filter ""))

(defn- matches? [query [m {:keys [name does about]}]]
  (or (str/blank? query)
      (let [q (str/lower-case query)]
        (some #(str/includes? (str/lower-case (str %)) q) [(cljs.core/name m) name does about]))))

(defn- mode-chip [mode op]
  (let [{:keys [name example size]} (ops/modes mode)]
    [:span {:title (str name " — " (ref/mode-notes mode) " Example: " example ". " size (if (= 1 size) " byte." " bytes."))
            :style {:display "inline-block" :border "1px solid #d0d7de" :border-radius "10px"
                    :padding "0 7px" :margin "0 4px 3px 0" :font-size "11px" :white-space "nowrap"
                    :background "#f6f8fa"}}
     name " " [:span {:style (merge muted mono)} (subs (hex op 2) 1)]]))

(defn- instruction-row [m {:keys [name does flags about]} focused? modes]
  (let [bg (when focused? "#fff8c5")]
    [:<>
     [:tr {:ref (fn [el]
                  (when (and el focused? (not= @scrolled-to m))
                    (reset! scrolled-to m)
                    (.scrollIntoView el #js {:block "center"})))
           :style {:border-top "1px solid #eaeef2" :background bg}}
      [:td {:style (merge mono {:padding "6px 10px 0 0" :font-weight "bold" :font-size "15px" :width "44px"})}
       (str/upper-case (cljs.core/name m))]
      [:td {:style {:padding "6px 10px 0 0"}} name]
      [:td {:style (merge mono {:padding "6px 10px 0 0" :white-space "nowrap" :font-size "13px"})} does]
      [:td {:style (merge mono {:padding "6px 0 0" :font-size "12px" :white-space "nowrap" :text-align "right"})
            :title "Status flags it can change"}
       (if (str/blank? flags) [:span {:style muted} "no flags"] flags)]]
     [:tr {:style {:background bg}}
      [:td]
      [:td {:col-span 3 :style {:padding "2px 0 6px"}}
       (when about [:div {:style (merge muted {:font-size "12px" :margin-bottom "3px"})} about])
       [:div (for [[mode op] modes] ^{:key op} [mode-chip mode op])]]]]))

(defn- modes-table []
  [:table {:style {:border-collapse "collapse" :font-size "13px"}}
   [:tbody
    (for [[mode {:keys [name example size]}] (sort-by (comp :size val) ops/modes)]
      ^{:key mode}
      [:tr {:style {:border-top "1px solid #eaeef2" :vertical-align "top"}}
       [:td {:style {:padding "4px 10px 4px 0" :white-space "nowrap"}} name]
       [:td {:style (merge mono {:padding "4px 10px 4px 0" :white-space "nowrap"})} example]
       [:td {:style (merge muted {:padding "4px 10px 4px 0" :white-space "nowrap"})} size (if (= 1 size) " byte" " bytes")]
       [:td {:style {:padding "4px 0"}} (ref/mode-notes mode)]])]])

(defn instructions-view []
  (let [{:keys [instruction-focus instruction-filter]} @db
        query (or instruction-filter "")]
    [:div
     [:p {:style (merge muted {:margin-top 0 :font-size "14px"})}
      "The 6502 has 56 instructions. Each name is a three-letter abbreviation: LDA is "
      [:strong "L"] "oa" [:strong "D"] " " [:strong "A"] ", BEQ is " [:strong "B"] "ranch if "
      [:strong "EQ"] "ual. Most come in several addressing modes, which say where the number they work on is "
      "(hover over one); each mode is a different opcode byte. "
      "M below is the byte in memory the instruction uses; the flags column lists the status flags it can change "
      "(N negative, V overflow, Z zero, C carry, I interrupt disable, D decimal)."]
     [:input {:type "search" :value query :placeholder "Search: lda, branch, carry…"
              :style {:width "260px" :margin-bottom "8px"}
              :on-change #(swap! db assoc :instruction-filter (.. % -target -value) :instruction-focus nil)}]
     (for [[cat label blurb] ref/categories
           :let [rows (filter #(and (= cat (:category (val %))) (matches? query %))
                              (sort-by key ref/instructions))]
           :when (seq rows)]
       ^{:key cat}
       [:div {:style {:margin-bottom "12px"}}
        [:h4 {:style {:margin "8px 0 2px"}} label]
        (when (seq blurb) [:div {:style (merge muted {:font-size "13px" :margin-bottom "4px"})} blurb])
        [:table {:style {:border-collapse "collapse" :width "100%"}}
         [:tbody
          (for [[m info] rows]
            ^{:key m} [instruction-row m info (= m instruction-focus) (ref/modes-of m)])]]])
     (let [rows (filter #(matches? query %) (sort-by key ref/unofficial-instructions))
           extra (filter #(matches? query %) [[:nop {:name "No operation (unofficial)" :does "nothing"
                                                     :flags "" :about "Some read their address anyway."}]
                                              [:sbc {:name "Subtract with borrow (unofficial)"
                                                     :does "A = A - M - (1 - C)" :flags "N V Z C"
                                                     :about "The same as the official $E9."}]])]
       (when (or (seq rows) (seq extra))
         [:div {:style {:margin-bottom "12px"}}
          [:h4 {:style {:margin "8px 0 2px"}} "Unofficial"]
          [:div {:style (merge muted {:font-size "13px" :margin-bottom "4px"})}
           "105 of the 256 opcode bytes were never documented, but still do something: "
           "the chip's decoding logic mixes parts of neighbouring instructions. Most combine two "
           "official instructions. A few games use them; the disassembly marks them with a *. "
           "12 more (not shown) jam the CPU."]
          [:table {:style {:border-collapse "collapse" :width "100%"}}
           [:tbody
            (for [[m info] (concat rows extra)]
              ^{:key (str "u" (cljs.core/name m))}
              [instruction-row m info (= m instruction-focus) (ref/modes-of m true)])]]]))
     [:h4 {:style {:margin "16px 0 4px"}} "Addressing modes"]
     [:p {:style (merge muted {:margin "0 0 6px" :font-size "13px"})}
      "Where an instruction's operand comes from. The size is the whole instruction: "
      "one byte of opcode, plus the operand."]
     [modes-table]]))
