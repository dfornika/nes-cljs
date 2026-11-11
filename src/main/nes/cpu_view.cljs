(ns nes.cpu-view
  "The CPU inspector: registers, status flags, the code about to run, and
  the stack."
  (:require [clojure.string :as str]
            [nes.breakpoints-view :refer [breakpoints-panel]]
            [nes.console :as console]
            [nes.cpu.disasm :as disasm :refer [hex]]
            [nes.cpu.opcodes :as ops]
            [nes.cpu.reference :as ref]
            [nes.debug-info :as debug-info]
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
  Instructions tab. `name-of` names the addresses that have names."
  [{:keys [mnemonic] :as instr} name-of]
  (if-not mnemonic
    [:span (disasm/text instr)]
    [:span
     [:a {:href "#" :title (str (get-in ref/instructions [mnemonic :name]) ": click for details")
          :style {:color "#24292f" :text-decoration "underline dotted #8c959f"}
          :on-click (fn [e] (.preventDefault e) (instructions-view/show! mnemonic))}
      (str/upper-case (name mnemonic))]
     (let [o (disasm/operand-text instr name-of)] (when (seq o) (str " " o)))]))

(defn- th
  "A column heading: short, with the long version as a tooltip."
  [label title]
  [:th {:title title
        :style (merge muted {:padding "1px 6px 3px" :font-size "11px" :font-weight "normal"
                             :text-align "left" :white-space "nowrap"
                             :text-transform "uppercase" :letter-spacing "0.04em"
                             :border-bottom "1px solid #d0d7de"
                             :cursor (when (seq title) "help")})}
   label])

(defn- code-listing
  "The code around the program counter: the last few instructions stepped
  through, the one about to run (highlighted), and what comes after it in
  memory."
  [emulator pc regs]
  (let [peek #(emu/cpu-peek emulator %)
        instrs (disasm/disassemble peek pc 16)
        done @console/recent-steps
        ;; names for addresses: the game's own, if it was built here, and
        ;; the hardware registers'
        table (debug-info/table-for (get-in @db [:rom :sha1]))
        name-of (debug-info/namer table)
        labels? (some #(debug-info/label-at table (:addr %)) (concat done instrs))
        exec-bps (into {} (for [b (console/breakpoints)
                                :when (and (= :exec (:kind b)) (nil? (:end b)))]
                            [(:addr b) b]))
        ;; `state` is :done (already run), :next (about to run) or :later
        row (fn [k {:keys [addr bytes mnemonic mode] :as instr} state]
              ^{:key k}
              [:tr {:style {:background (when (= state :next) "#fff8c5")
                            :opacity (when (= state :done) 0.6)}}
               (let [b (exec-bps addr)]
                 [:td {:style {:padding "1px 2px 1px 6px" :width "12px" :cursor "pointer"
                               :color (if (and b (:enabled? b)) "#cf222e" "#d0d7de")}
                       :title (if b "Remove the breakpoint here" "Stop before running this instruction")
                       :on-click #(console/toggle-exec-breakpoint! addr)}
                  "●"])
               [:td {:style (merge muted {:padding "1px 6px 1px 2px" :width "14px"})
                     :title (case state :next "Runs next" :done "Already run" nil)}
                (case state :next [:span {:style {:color "#24292f"}} "▶"] :done "✓" nil)]
               [:td {:style (merge mono {:padding "1px 6px" :color "#0969da"})} (subs (hex addr 4) 1)]
               [:td {:style (merge mono muted {:padding "1px 6px" :white-space "pre"})}
                (.padEnd (apply str (interpose " " (map #(if % (subs (hex % 2) 1) "??") bytes))) 8)]
               ;; the label on this address, in games that have them
               (when labels?
                 [:td {:style (merge mono {:padding "1px 0 1px 6px" :text-align "right" :color "#8250df"
                                           :white-space "nowrap"})}
                  (some-> (debug-info/label-at table addr) (str ":"))])
               [:td {:style (merge mono {:padding "1px 6px" :white-space "pre"})
                     :title (when mnemonic
                              (str (get-in ops/modes [mode :name]) ": " (ref/mode-notes mode)
                                   ;; a name hides the number, so say it here
                                   (let [plain (disasm/operand-text instr)]
                                     (when (not= plain (disasm/operand-text instr name-of))
                                       (str "\nThe address is " plain)))))}
                [mnemonic-link instr name-of]]
               ;; one line each, so the listing keeps its height as the game runs
               [:td {:style {:padding "1px 6px" :color (if (= state :next) "#24292f" "#57606a")
                             :font-size "12px" :white-space "nowrap"}}
                ;; the instruction about to run, with the actual addresses and values
                (ref/explain instr (when (= state :next) {:regs regs :peek peek}))]])
        blanks (- console/recent-steps-kept (count done))]
    [:table {:style {:border-collapse "collapse" :font-size "13px"}}
     [:thead
      [:tr
       [th "" ""]
       [th "" ""]
       [th "Addr" "Address: where in memory the instruction is. The highlighted row's address is the program counter (PC)."]
       [th "Bytes" (str "The instruction as it sits in memory. The first byte is the opcode: which instruction, and how it "
                        "finds its data. Then 0, 1 or 2 operand bytes; a 2-byte operand is an address, low byte first.")]
       (when labels?
         [th "Label" "The name the game's source gives this address."])
       [th "Instruction" (str "The same bytes written as assembly: the mnemonic (click it for details), then the operand. "
                              "A name in the operand stands for an address; hover over it for the number.")]
       [th "What it does" "In plain English. For the instruction about to run, with the actual addresses and values."]]]
     [:tbody
      ;; Room for the instructions stepped through is always there, so the
      ;; highlighted row doesn't move down as it fills.
      (for [i (range blanks)]
        ^{:key (str "blank" i)}
        [:tr [:td {:col-span (if labels? 7 6)
                   :style (merge muted {:padding "1px 6px 1px 34px" :font-size "12px" :font-style "italic"
                                        :line-height "19px"})}
              (if (and (empty? done) (= i (dec blanks)))
                "Instructions you step through stay here."
                "\u00A0")]])
      (map-indexed (fn [i instr] (row (str "done" i) instr :done)) done)
      (map-indexed (fn [i instr] (row i instr (if (zero? i) :next :later))) instrs)]]))

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

(defn- stack-view
  "The stack, newest first, under a row showing where the stack pointer
  is. `table` (a game's symbol table, or nil) names the routines that
  return addresses belong to."
  [emulator sp table]
  (let [top (+ 0x101 sp)
        used (- 0xFF sp)
        cell (merge mono {:padding "1px 8px 1px 6px"})
        byte-at #(subs (hex (emu/cpu-peek emulator %) 2) 1)
        named (fn [addr]
                (if-let [n (debug-info/name-at table addr)]
                  [:span [:span {:style (merge mono {:color "#8250df"})} n] " (" (hex addr 4) ")"]
                  [:span {:style mono} (hex addr 4)]))]
    [:div
     [:div {:style (merge muted {:font-size "12px" :margin-bottom "4px" :max-width "340px"})}
      "The stack is RAM at $0100-$01FF, filled from the top down. JSR saves where to come back "
      "to (2 bytes), PHA saves A, and an interrupt saves where it interrupted and the flags (3 bytes)."]
     ;; a fixed height (room for 12 rows), so what's below doesn't jump
     ;; around as the stack grows and shrinks
     [:div {:style {:height "244px" :overflow "hidden"}}
      [:table {:style {:font-size "13px" :border-collapse "collapse"}}
       [:thead
        [:tr
         [th "Addr" "Address in the stack's page of RAM, $0100-$01FF. Newest at the top of this list."]
         [th "Value" "The byte (or the two bytes of a return address) saved there."]
         [th "What it is" "A guess, for pairs of bytes that look like a return address saved by JSR."]]]
       [:tbody
        ;; where the stack pointer points: the first free byte
        ^{:key "sp"}
        [:tr {:style {:background "#f6f8fa"}}
         [:td {:style (merge cell muted)} (subs (hex (+ 0x100 sp) 4) 1)]
         [:td {:style (merge cell muted)} "··"]
         [:td {:style {:font-size "12px" :white-space "nowrap"}}
          "← SP (" (hex sp 2) "): the next byte saved goes here"]]
        (if (zero? used)
          ^{:key "empty"}
          [:tr [:td {:col-span 3 :style (merge muted {:padding "1px 6px" :font-size "12px"})}
                "Nothing is saved on the stack."]]
          (loop [a top rows [] n 0]
            (if (or (> a 0x1FF) (= n 11))
              (seq (cond-> rows (<= a 0x1FF) (conj ^{:key "more"} [:tr [:td {:style (merge cell muted)} "…"]])))
              (if-let [{:keys [returns-to jsr-at target]} (when (< a 0x1FF) (return-address emulator a))]
                (recur (+ a 2)
                       (conj rows
                             ^{:key a}
                             [:tr
                              [:td {:style (merge cell muted)} (subs (hex a 4) 1) "–" (subs (hex (inc a) 4) 3)]
                              [:td {:style cell} (byte-at a) " " (byte-at (inc a))]
                              [:td {:style {:font-size "12px" :white-space "nowrap"}}
                               "return to " [:span {:style mono} (hex returns-to 4)]
                               [:span {:style muted} ", after the call to " (named target)]]])
                       (inc n))
                (recur (inc a)
                       (conj rows
                             ^{:key a}
                             [:tr
                              [:td {:style (merge cell muted)} (subs (hex a 4) 1)]
                              [:td {:style cell} (byte-at a)]
                              [:td]])
                       (inc n))))))]]]
     [:div {:style (merge muted {:font-size "12px"})}
      used " of 256 bytes in use"]]))

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
        [:span "nes.cpu.core runs every instruction and interrupt (official and unofficial); "
         "the PPU, sound and cartridge are still jsnes's. So far: "
         [:span {:style mono} cljs] " instructions and interrupts by ClojureScript"
         (when (pos? jsnes) [:span ", " [:span {:style mono} jsnes] " by jsnes (opcodes that jam the CPU)"])
         "."]
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
                  (when stats [:span {:style muted} " ClojureScript ran " (:cljs stats) " instructions and interrupts"
                               (when (pos? (:jsnes stats)) (str ", jsnes " (:jsnes stats)))
                               "."])]
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
        [section "Instructions"
         [:div {:style (merge muted {:font-size "12px" :margin-bottom "4px"})}
          "The highlighted one runs next. Click an instruction's name for what it does, or a ● to stop there."]
         [code-listing emulator pc regs]]
        [:div
         [section "Stack" [stack-view emulator sp (debug-info/table-for (get-in @db [:rom :sha1]))]]
         [section "Interrupt vectors" [vector-table emulator]]]]
       [section "Breakpoints" [breakpoints-panel]]])
    [:p {:style muted} "Load a ROM to see the CPU."]))
