(ns nes.cpu-view
  "The CPU inspector: registers, status flags, the code about to run, and
  the stack."
  (:require [clojure.string :as str]
            [nes.breakpoints-view :refer [breakpoints-panel]]
            [nes.console :as console]
            [nes.cpu.disasm :as disasm :refer [hex]]
            [nes.cpu.opcodes :as ops]
            [nes.cpu.reference :as ref]
            [nes.instructions-view :as instructions-view]
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

(defn- mnemonic-link
  "An instruction's assembly text, with its mnemonic linking to the
  Instructions tab."
  [{:keys [mnemonic] :as instr}]
  (if-not mnemonic
    [:span (disasm/text instr)]
    [:span
     [:a {:href "#" :title (str (get-in ref/instructions [mnemonic :name]) ": click for details")
          :style {:color "#24292f" :text-decoration "underline dotted #8c959f"}
          :on-click (fn [e] (.preventDefault e) (instructions-view/show! mnemonic))}
      (str/upper-case (name mnemonic))]
     (let [o (disasm/operand-text instr)] (when (seq o) (str " " o)))]))

(defn- code-listing [emulator pc regs]
  (let [peek #(emu/cpu-peek emulator %)
        instrs (disasm/disassemble peek pc 16)
        exec-bps (into {} (for [b (console/breakpoints)
                                :when (and (= :exec (:kind b)) (nil? (:end b)))]
                            [(:addr b) b]))]
    [:table {:style {:border-collapse "collapse" :font-size "13px"}}
     [:tbody
      (for [[i {:keys [addr bytes mnemonic mode] :as instr}] (map-indexed vector instrs)]
        ^{:key i}
        [:tr {:style {:background (when (zero? i) "#fff8c5")}}
         (let [b (exec-bps addr)]
           [:td {:style {:padding "1px 2px 1px 6px" :width "12px" :cursor "pointer"
                         :color (if (and b (:enabled? b)) "#cf222e" "#d0d7de")}
                 :title (if b "Remove the breakpoint here" "Stop before running this instruction")
                 :on-click #(console/toggle-exec-breakpoint! addr)}
            "●"])
         [:td {:style {:padding "1px 6px 1px 2px" :width "14px"}} (when (zero? i) "▶")]
         [:td {:style (merge mono {:padding "1px 6px" :color "#0969da"})} (subs (hex addr 4) 1)]
         [:td {:style (merge mono muted {:padding "1px 6px" :white-space "pre"})}
          (.padEnd (apply str (interpose " " (map #(if % (subs (hex % 2) 1) "??") bytes))) 8)]
         [:td {:style (merge mono {:padding "1px 6px" :white-space "pre"})
               :title (when mnemonic (str (get-in ops/modes [mode :name]) ": " (ref/mode-notes mode)))}
          [mnemonic-link instr]]
         ;; one line each, so the listing keeps its height as the game runs
         [:td {:style {:padding "1px 6px" :color (if (zero? i) "#24292f" "#57606a")
                       :font-size "12px" :white-space "nowrap"}}
          ;; the instruction about to run, with the actual addresses and values
          (ref/explain instr (when (zero? i) {:regs regs :peek peek}))]])]]))

;; ---------------------------------------------------------------------------
;; Stack and vectors

(defn- return-address
  "If the two bytes at `a` (low) and `a`+1 (high) look like an address
  pushed by JSR, where it returns to and the JSR's own address."
  [emulator a]
  (let [lo (emu/cpu-peek emulator a)
        hi (emu/cpu-peek emulator (inc a))]
    (when (and lo hi)
      (let [v (bit-or lo (bit-shift-left hi 8))
            jsr-at (- v 2)]
        ;; JSR pushes the address of its own last byte: the JSR is 2 before
        (when (and (>= jsr-at 0x8000) (= 0x20 (emu/cpu-peek emulator jsr-at)))
          {:returns-to (inc v) :jsr-at jsr-at
           :target (bit-or (emu/cpu-peek emulator (inc jsr-at))
                           (bit-shift-left (emu/cpu-peek emulator (+ 2 jsr-at)) 8))})))))

(defn- stack-view [emulator sp]
  (let [top (+ 0x101 sp)]
    (if (> top 0x1FF)
      [:div {:style (merge muted {:height "262px"})} "Empty."]
      [:div
       [:div {:style (merge muted {:font-size "12px" :margin-bottom "4px" :max-width "340px"})}
        "The stack is RAM at $0100-$01FF, filled from the top down: JSR saves where to come back to "
        "here (2 bytes), and PHA saves A. Newest first:"]
       ;; a fixed height (room for 12 rows), so what's below doesn't jump
       ;; around as the stack grows and shrinks
       [:div {:style {:height "220px" :overflow "hidden"}}
        [:table {:style {:font-size "13px" :border-collapse "collapse"}}
         [:tbody
          (loop [a top rows [] n 0]
            (if (or (> a 0x1FF) (= n 12))
              (seq (cond-> rows (<= a 0x1FF) (conj ^{:key "more"} [:tr [:td {:style muted} "…"]])))
              (if-let [{:keys [returns-to jsr-at target]} (when (< a 0x1FF) (return-address emulator a))]
                (recur (+ a 2)
                       (conj rows
                             ^{:key a}
                             [:tr
                              [:td {:style (merge mono muted {:padding "1px 8px 1px 0"})} (subs (hex a 4) 1) "–" (subs (hex (inc a) 4) 3)]
                              [:td {:style (merge mono {:padding "1px 8px 1px 0"})}
                               (subs (hex (emu/cpu-peek emulator a) 2) 1) " " (subs (hex (emu/cpu-peek emulator (inc a)) 2) 1)]
                              [:td {:style {:font-size "12px"}}
                               "return address: back to " [:span {:style mono} (hex returns-to 4)]
                               [:span {:style muted} " (after the call to " (hex target 4) " at " (hex jsr-at 4) ")"]]])
                       (inc n))
                (recur (inc a)
                       (conj rows
                             ^{:key a}
                             [:tr
                              [:td {:style (merge mono muted {:padding "1px 8px 1px 0"})} (subs (hex a 4) 1)]
                              [:td {:style (merge mono {:padding "1px 8px 1px 0"})} (subs (hex (emu/cpu-peek emulator a) 2) 1)]
                              [:td]])
                       (inc n)))))]]]])))

(defn- word-at [emulator addr]
  (let [lo (emu/cpu-peek emulator addr) hi (emu/cpu-peek emulator (inc addr))]
    (when (and lo hi) (bit-or lo (bit-shift-left hi 8)))))

(def ^:private vectors
  [[0xFFFA "NMI" "Runs every frame, when the PPU finishes the picture (if PPUCTRL turns it on). Games update the screen here."]
   [0xFFFC "RESET" "Where the game starts, at power on and when Reset is pressed."]
   [0xFFFE "IRQ/BRK" "For interrupts from the cartridge or sound chip, and the BRK instruction. Many games don't use it."]])

(defn- vector-table [emulator]
  [:div
   [:div {:style (merge muted {:font-size "12px" :margin-bottom "4px" :max-width "340px"})}
    "The last 6 bytes of ROM hold three addresses: where the CPU goes when something happens."]
   [:table {:style {:font-size "13px" :border-collapse "collapse"}}
    [:tbody
     (for [[addr label about] vectors
           :let [target (word-at emulator addr)]]
       ^{:key addr}
       [:tr {:style {:vertical-align "top"}}
        [:td {:style {:padding "2px 8px 2px 0" :font-weight "bold"}} label]
        [:td {:style (merge mono {:padding "2px 8px 2px 0" :white-space "nowrap"})}
         (some-> target (hex 4))
         (when target
           [:button {:title "Stop when this runs" :style {:font-size "10px" :margin-left "4px"}
                     :on-click #(console/add-breakpoint! {:kind :exec :addr target})} "●"])]
        [:td {:style (merge muted {:padding "2px 0" :font-size "12px" :max-width "240px"})} about]])]]])

;; ---------------------------------------------------------------------------
;; Which CPU

(defn- cpu-choice
  "`_tick` is the inspector tick, so the counts here refresh."
  [emulator _tick]
  (let [which (emu/cpu-in-use emulator)
        {:keys [cljs jsnes]} (emu/cpu-stats emulator)
        {:keys [running? frame of same-for differs-at jsnes-ms cljs-ms stats] :as cmp} (:cpu-compare @db)
        option (fn [k label]
                 [:label {:style {:margin-right "14px" :cursor "pointer"}}
                  [:input {:type "radio" :name "cpu" :checked (= which k)
                           :on-change #(console/use-cpu! k)}]
                  " " label])]
    [:div {:style {:border "1px solid #d0d7de" :border-radius "6px" :padding "8px 12px"
                   :margin-bottom "14px" :font-size "13px"}}
     [:div [:strong "Run the CPU with: "]
      [option :jsnes "jsnes's 6502 (JavaScript)"]
      [option :cljs "NES.cljs's own 6502 (ClojureScript)"]]
     [:div {:style (merge muted {:font-size "12px" :margin-top "4px"})}
      (if (= which :cljs)
        [:span "nes.cpu.core runs each instruction; the PPU, sound and cartridge are still jsnes's. "
         "jsnes still takes interrupts and the few unofficial instructions some games use. So far: "
         [:span {:style mono} cljs] " instructions by ClojureScript, "
         [:span {:style mono} jsnes] " by jsnes."]
        "The rest of the console (PPU, sound, cartridge) is jsnes either way. You can switch while a game runs.")]
     [:div {:style {:margin-top "6px" :display "flex" :gap "8px" :align-items "center" :flex-wrap "wrap"}}
      [:button {:disabled running? :on-click #(console/compare-cpus! 600)
                :title "Run this game from power on with each CPU for 600 frames (10 seconds of game time), comparing the picture and RAM after every frame"}
       "Compare the two CPUs"]
      (cond
        running? [:span {:style muted} "running… frame " frame " of " of]
        same-for [:span [:span {:style {:color "#1a7f37" :font-weight "bold"}} "✓ identical"]
                  " picture and RAM for all " same-for " frames. Time: jsnes "
                  (js/Math.round jsnes-ms) " ms, ClojureScript " (js/Math.round cljs-ms) " ms ("
                  (.toFixed (/ cljs-ms (max jsnes-ms 1)) 1) "×)."
                  (when stats [:span {:style muted} " ClojureScript ran " (:cljs stats) " instructions, jsnes "
                               (:jsnes stats) " (interrupts and unofficial ones)."])]
        differs-at [:span {:style {:color "#cf222e" :font-weight "bold"}}
                    "✗ they differ from frame " differs-at])]]))

;; ---------------------------------------------------------------------------

(defn cpu-view []
  @console/inspect-tick
  (if-let [emulator (console/emulator-instance)]
    (let [{:keys [a x y sp pc p cycles] :as regs} (emu/cpu-state emulator)
          {:keys [scanline dot frame]} (emu/ppu-state emulator)
          paused? (= :paused (:status @db))]
      [:div
       [cpu-choice emulator @console/inspect-tick]
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
           "Status flags (P) = " (hex p 2)]
          [flag-boxes p]
          [:div {:style (merge muted {:font-size "11px" :margin-top "2px" :max-width "260px"})}
           (let [on (for [[i [label about]] (map-indexed vector flags)
                          :when (and (bit-test p (- 7 i)) (not= label "-"))]
                      (str label " " (first (.split about ":"))))]
             (if (seq on) (str "Set: " (str/join ", " on)) "None set"))]]]]
       [:div {:style (merge muted mono {:font-size "12px" :margin "-8px 0 16px"})}
        "Frame " frame " · scanline " scanline " · dot " dot " · CPU cycle " cycles
        (when (<= 241 scanline 260) " · in vertical blank")]
       [:div {:style {:display "flex" :gap "32px" :flex-wrap "wrap"}}
        [section "Next instructions"
         [:div {:style (merge muted {:font-size "12px" :margin-bottom "4px"})}
          "The highlighted one runs next. Click an instruction's name for what it does, or a ● to stop there."]
         [code-listing emulator pc regs]]
        [:div
         [section "Stack" [stack-view emulator sp]]
         [section "Interrupt vectors" [vector-table emulator]]]]
       [section "Breakpoints" [breakpoints-panel]]])
    [:p {:style muted} "Load a ROM to see the CPU."]))
