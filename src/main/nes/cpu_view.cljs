(ns nes.cpu-view
  "The CPU inspector: registers, status flags, the code about to run, and
  the stack."
  (:require [nes.console :as console]
            [nes.cpu.disasm :as disasm :refer [hex]]
            [nes.cpu.opcodes :as ops]
            [nes.emulator :as emu]
            [nes.state :refer [db]]))

(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})
(def ^:private muted {:color "#57606a"})

(defn- section [title & children]
  (into [:div {:style {:margin-bottom "16px"}}
         [:h4 {:style {:margin "0 0 6px"}} title]]
        children))

;; ---------------------------------------------------------------------------
;; Registers

(def ^:private registers
  [[:a "A" 2 "Accumulator: where arithmetic and logic happen"]
   [:x "X" 2 "Index register X: often a loop counter or array index"]
   [:y "Y" 2 "Index register Y: often a loop counter or array index"]
   [:sp "SP" 2 "Stack pointer: the stack lives at $0100-$01FF and grows downward from $01FF"]
   [:pc "PC" 4 "Program counter: the address of the next instruction"]])

(defn- register-box [label value width title]
  [:div {:title title
         :style {:border "1px solid #d0d7de" :border-radius "4px"
                 :padding "4px 10px" :text-align "center" :min-width "52px"}}
   [:div {:style (merge muted {:font-size "11px"})} label]
   [:div {:style (merge mono {:font-size "18px"})} (hex value width)]
   [:div {:style (merge muted mono {:font-size "11px"})} value]])

(def ^:private flags
  "The status register P, from bit 7 down to bit 0."
  [["N" "Negative: bit 7 of the last result"]
   ["V" "Overflow: the last signed addition/subtraction overflowed"]
   ["-" "Unused: always 1"]
   ["B" "Break: only exists in the copy of P pushed to the stack"]
   ["D" "Decimal mode: does nothing on the NES"]
   ["I" "Interrupt disable: when set, IRQ interrupts are ignored"]
   ["Z" "Zero: the last result was zero"]
   ["C" "Carry: carry/borrow out of the last addition, subtraction, shift or compare"]])

(defn- flag-boxes [p]
  [:div {:style {:display "flex" :gap "3px" :align-items "flex-end"}}
   (for [[i [label title]] (map-indexed vector flags)
         :let [on? (bit-test p (- 7 i))]]
     ^{:key i}
     [:div {:title (str title (if on? " (set)" " (clear)"))
            :style (merge mono
                          {:width "22px" :height "26px" :line-height "26px"
                           :text-align "center" :border-radius "3px"
                           :border "1px solid #d0d7de"
                           :background (if on? "#2da44e" "white")
                           :color (if on? "white" "#8c959f")})}
      label])])

;; ---------------------------------------------------------------------------
;; Disassembly

(defn- code-listing [emulator pc]
  (let [peek #(emu/cpu-peek emulator %)
        instrs (disasm/disassemble peek pc 16)]
    [:table {:style (merge mono {:border-collapse "collapse" :font-size "13px"})}
     [:tbody
      (for [[i {:keys [addr bytes mnemonic mode] :as instr}] (map-indexed vector instrs)]
        ^{:key i}
        [:tr {:style {:background (when (zero? i) "#fff8c5")}}
         [:td {:style {:padding "1px 6px" :width "14px"}} (when (zero? i) "▶")]
         [:td {:style {:padding "1px 6px" :color "#0969da"}} (subs (hex addr 4) 1)]
         [:td {:style (merge muted {:padding "1px 6px" :white-space "pre"})}
          (.padEnd (apply str (interpose " " (map #(if % (subs (hex % 2) 1) "??") bytes))) 8)]
         [:td {:style {:padding "1px 6px" :white-space "pre"}
               :title (when mnemonic (get-in ops/modes [mode :name]))}
          (disasm/text instr)]])]]))

;; ---------------------------------------------------------------------------
;; Stack and vectors

(defn- stack-view [emulator sp]
  (let [entries (for [a (range (+ 0x101 sp) 0x200)] [a (emu/cpu-peek emulator a)])]
    (if (empty? entries)
      [:div {:style muted} "(empty)"]
      [:div {:style (merge mono {:font-size "13px" :display "flex" :flex-wrap "wrap" :gap "2px 10px"})}
       (for [[a v] (take 16 entries)]
         ^{:key a} [:span [:span {:style muted} (subs (hex a 4) 1) ":"] (subs (hex v 2) 1)])
       (when (> (count entries) 16) [:span {:style muted} "…"])])))

(defn- word-at [emulator addr]
  (let [lo (emu/cpu-peek emulator addr) hi (emu/cpu-peek emulator (inc addr))]
    (when (and lo hi) (bit-or lo (bit-shift-left hi 8)))))

(def ^:private vectors
  [[0xFFFA "NMI" "Where the CPU jumps each time the PPU starts vertical blank (if enabled)"]
   [0xFFFC "RESET" "Where the CPU starts running at power on and reset"]
   [0xFFFE "IRQ/BRK" "Where the CPU jumps on an IRQ interrupt or BRK instruction"]])

(defn- vector-table [emulator]
  [:table {:style (merge mono {:font-size "13px"})}
   [:tbody
    (for [[addr label title] vectors]
      ^{:key addr}
      [:tr {:title title}
       [:td {:style {:padding-right "10px"}} label]
       [:td {:style muted} (hex addr 4)]
       [:td {:style {:padding "0 6px"}} "→"]
       [:td (some-> (word-at emulator addr) (hex 4))]])]])

;; ---------------------------------------------------------------------------

(defn cpu-view []
  @console/inspect-tick
  (if-let [emulator (console/emulator-instance)]
    (let [{:keys [a x y sp pc p cycles] :as regs} (emu/cpu-state emulator)
          {:keys [scanline dot frame]} (emu/ppu-state emulator)
          paused? (= :paused (:status @db))]
      [:div
       (when-not paused?
         [:p {:style (merge muted {:margin-top 0 :font-size "14px"})}
          "Pause, then use " [:strong "Step instruction"]
          " to watch the CPU run one instruction at a time."])
       [section "Registers"
        [:div {:style {:display "flex" :gap "8px" :flex-wrap "wrap" :align-items "flex-end"}}
         (for [[k label width title] registers]
           ^{:key k} [register-box label (regs k) width title])
         [:div {:style {:margin-left "8px"}}
          [:div {:style (merge muted {:font-size "11px" :margin-bottom "2px"})}
           "Status (P) = " (hex p 2)]
          [flag-boxes p]]]]
       [:div {:style (merge muted mono {:font-size "12px" :margin "-8px 0 16px"})}
        "Frame " frame " · scanline " scanline " · dot " dot " · CPU cycle " cycles
        (when (<= 241 scanline 260) " · in vertical blank")]
       [:div {:style {:display "flex" :gap "32px" :flex-wrap "wrap"}}
        [section "Next instructions" [code-listing emulator pc]]
        [:div
         [section "Stack" [:div {:style (merge muted {:font-size "12px" :margin-bottom "4px"})}
                           "Most recently pushed first"]
          [stack-view emulator sp]]
         [section "Interrupt vectors" [vector-table emulator]]]]])
    [:p {:style muted} "Load a ROM to see the CPU."]))
