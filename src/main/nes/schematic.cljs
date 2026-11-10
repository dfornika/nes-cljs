(ns nes.schematic
  "A live diagram of the NES: the chips on the cartridge and in the
  console, and the connections between them, lit up by real traffic on
  the CPU's bus.

  Loosely modelled on the 'Down To Basics' diagram in Nintendo Power
  #20 (January 1991), which put the cartridge's chips above the Control
  Deck's. (That diagram has the CPU and PPU swapped: CHR and video RAM
  belong to the PPU, PRG and work RAM to the CPU.)"
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [nes.console :as console]
            [nes.cpu.disasm :refer [hex]]
            [nes.memory-map :as memory-map]
            [nes.memory-view :as memory-view]
            [nes.registers :as registers]
            [nes.rom :as rom]
            [nes.state :refer [db]]))

(def ^:private muted {:color "#57606a"})
(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})

(defonce ^:private hover (r/atom nil))

;; What the bus log shows: destinations and registers hidden, which
;; destination's registers are listed, and a wire (or chip) to show only
;; the traffic of: [:link id] or [:chip id].
(defonce ^:private bus-filter (r/atom {:hidden #{} :hidden-regs #{} :expanded nil :only nil}))

;; ---------------------------------------------------------------------------
;; Where each logged access went

(def ^:private dest-colors
  {:ppu "#0969da" :sprite-dma "#bf8700" :controllers "#1a7f37" :sound "#cf222e" :mapper "#6e7781"})

(defn- route
  "The wires and chips a logged access traveled over: {:links #{} :chips #{}}.
  All of these are on the CPU's bus; a PPUDATA access then carries on over
  the PPU's own bus, to wherever the PPU's address points."
  [{:keys [addr write? target]}]
  (case (registers/destination addr write?)
    :ppu {:links (cond-> #{:ppu-regs} (= target :chr) (conj :chr) (= target :vram) (conj :vram))
          :chips (cond-> #{:ppu}
                   (= target :chr) (conj :chr) (= target :vram) (conj :vram) (= target :pal) (conj :palette))}
    :sprite-dma {:links #{:dma :wram} :chips #{:wram :oam}}
    :controllers {:links #{:pad} :chips #{:pad}}
    ;; the sound chip is inside the CPU: no wire at all
    :sound {:links #{} :chips #{:apu}}
    :mapper {:links #{:mapper} :chips #{:mapper}}
    {:links #{} :chips #{}}))

(defn- on-route? [{:keys [links chips]} [kind id]]
  (case kind :link (contains? links id) :chip (contains? chips id) false))

;; ---------------------------------------------------------------------------
;; Parts of the diagram

(defn- kb [bytes] (str (/ bytes 1024) " KB"))

(defn- chips
  "The chips to draw, given the cartridge's iNES header (or nil)."
  [header]
  (let [{:keys [prg-size chr-size chr-ram? mapper battery?]} header
        mapper-name (get rom/mapper-names mapper)]
    [{:id :chr :x 60 :y 65 :w 180 :h 60
      :title (if chr-ram? "CHR RAM" "CHR ROM")
      :sub (if chr-ram? "8 KB" (some-> chr-size kb))
      :about (str "The graphics: 8×8 pixel tiles that the PPU draws from. "
                  (if chr-ram?
                    "This cartridge has RAM here instead of ROM, so the game copies its own tiles in."
                    "Only the PPU can see this chip - the CPU can't read it directly."))
      :nav [:tab :tiles]}
     {:id :mapper :x 360 :y 65 :w 180 :h 60
      :title (if (and mapper (pos? mapper)) (str "Mapper: " (or mapper-name (str "#" mapper))) "No mapper")
      :sub (if (and mapper (pos? mapper)) "bank switching" "NROM: fixed wiring")
      :dashed? (not (and mapper (pos? mapper)))
      :about (if (and mapper (pos? mapper))
               "A chip that swaps which banks of PRG and CHR the CPU and PPU can see. The CPU controls it by writing to addresses in ROM ($8000-$FFFF): the ROM ignores the writes, but the mapper is listening."
               "NROM cartridges have no mapper: the CPU and PPU see the whole PRG and CHR directly. (Nintendo Power: 'It all began with NROMs'.)")
      :nav [:tab :cartridge]}
     {:id :prg :x 680 :y 50 :w 180 :h 50
      :title "PRG ROM" :sub (some-> prg-size kb)
      :about "The program: the game's code and data, which the CPU runs. Appears at CPU addresses $8000-$FFFF."
      :nav [:memory :cpu 0x8000]}
     {:id :cart-ram :x 680 :y 115 :w 180 :h 45
      :title "PRG RAM" :sub (if battery? "8 KB, battery-backed" "8 KB, if fitted")
      :dashed? (not battery?)
      :about "Extra RAM on some cartridges, at CPU addresses $6000-$7FFF. With a battery, it keeps saved games when the power is off."
      :nav [:memory :cpu 0x6000]}
     {:id :vram :x 60 :y 260 :w 180 :h 60
      :title "Video RAM" :sub "2 KB · nametables"
      :about "The background layout: which tile goes in each 8×8 square of the screen, and which palette each area uses. Holds two screens' worth; the cartridge decides how they're arranged (mirroring)."
      :nav [:memory :ppu 0x2000]}
     {:id :wram :x 680 :y 260 :w 180 :h 60
      :title "Work RAM" :sub "2 KB"
      :about "The CPU's scratch memory: the game's variables (score, positions, timers...), the stack, and usually a copy of the sprite list. CPU addresses $0000-$07FF."
      :nav [:memory :cpu 0x0000]}
     {:id :ppu :x 40 :y 370 :w 210 :h 140
      :title "PPU" :sub "Ricoh 2C02 · picture"
      :about "The Picture Processing Unit: draws the picture, one pixel at a time, 61,440 pixels per frame, from tiles (CHR), the background layout (video RAM), the sprite list (OAM) and palettes. The CPU talks to it only through 8 registers at $2000-$2007."
      :nav [:tab :tiles]}
     {:id :oam :x 55 :y 425 :w 85 :h 50 :inner? true
      :title "OAM" :sub "256 B · sprites"
      :about "Object Attribute Memory, inside the PPU: the position, tile, palette and flip flags of all 64 sprites. Games usually fill it with sprite DMA from work RAM once per frame."
      :nav [:tab :sprites]}
     {:id :palette :x 150 :y 425 :w 85 :h 50 :inner? true
      :title "Palettes" :sub "32 B"
      :about "Palette RAM, inside the PPU: 8 palettes of 4 colors, each color a number from the NES's 64-color master palette."
      :nav [:memory :ppu 0x3F00]}
     {:id :cpu :x 650 :y 370 :w 210 :h 140
      :title "CPU" :sub "Ricoh 2A03"
      :about "Runs the game's program: a 6502 processor at 1.79 MHz, plus the sound hardware and controller ports on the same chip. It reaches everything else through its address bus: 16 bits of address, 8 bits of data."
      :nav [:tab :cpu]}
     {:id :core :x 665 :y 425 :w 85 :h 50 :inner? true
      :title "6502" :sub "core"
      :about "The processor core: registers A, X, Y, the stack pointer, program counter and status flags."
      :nav [:tab :cpu]}
     {:id :apu :x 760 :y 425 :w 85 :h 50 :inner? true
      :title "APU" :sub "sound + I/O"
      :about "Audio Processing Unit: two square-wave channels, a triangle, noise, and a sample player, controlled through registers at $4000-$4017. (No sound in NES.cljs yet.)"}
     {:id :tv :x 85 :y 605 :w 120 :h 40 :outside? true
      :title "TV" :about "The PPU sends a video signal out: 256×240 pixels, about 60 times a second."}
     {:id :pad :x 695 :y 605 :w 130 :h 40 :outside? true
      :title "Controllers"
      :about "The game reads the buttons one at a time from $4016 (player 1) and $4017 (player 2), after writing 1 then 0 to $4016 to latch them."}]))

(def ^:private cpu-bus "#0969da")
(def ^:private ppu-bus "#8250df")

;; Each link's path starts at the end that drives it (CPU or PPU), so
;; writes flow forwards along the path and reads flow backwards.
(def ^:private links
  [{:id :chr :d "M250,415 H290 V110 H240" :color ppu-bus
    :reads [:chrRender :chr] :writes [] :label [298 170 "start"]
    :title "PPU ↔ CHR"
    :about "The PPU reads tile patterns while drawing. (Estimated from whether drawing is switched on: jsnes doesn't route these through code we can watch.) CPU accesses through PPUDATA are counted exactly."}
   {:id :vram :d "M250,395 H270 V290 H240" :color ppu-bus
    :reads [:vramRender :vram] :writes [] :label [150 338 "middle"]
    :title "PPU ↔ Video RAM"
    :about "The PPU reads the nametable and attribute bytes for each tile it draws (estimated), and the CPU fills video RAM through PPUDATA ($2007)."}
   {:id :prg :d "M650,400 H610 V90 H680" :color cpu-bus
    :reads [:prgR] :writes [] :label [604 255 "end"]
    :title "CPU ↔ PRG ROM"
    :about "Every instruction the CPU runs is fetched from here, byte by byte, plus any data the game keeps in ROM."}
   {:id :cart-ram :d "M650,415 H625 V137 H680" :color cpu-bus
    :reads [:cartR] :writes [:cartW] :label [631 352 "start"]
    :title "CPU ↔ PRG RAM" :about "Reads and writes of cartridge RAM at $6000-$7FFF."}
   {:id :mapper :d "M650,430 H595 V105 H540" :color cpu-bus
    :reads [] :writes [:mapperW] :label [589 300 "end"]
    :title "CPU → mapper"
    :about "Writes to $8000-$FFFF. ROM can't be written, but a mapper chip watches for these writes and switches banks."}
   {:id :wram :d "M720,370 V320" :color cpu-bus
    :reads [:ramR] :writes [:ramW] :label [726 348 "start"]
    :title "CPU ↔ Work RAM" :about "Reads and writes of the console's 2 KB of RAM, including the stack."}
   {:id :ppu-regs :d "M650,445 H250" :color cpu-bus
    :reads [:ppuR] :writes [:ppuW] :label [450 438 "middle"]
    :title "CPU ↔ PPU registers ($2000-$2007)"
    :about "The CPU's only way to talk to the PPU: 8 registers for settings, scrolling, and reading/writing PPU memory one byte at a time. See the bus log below for each access."}
   {:id :dma :d "M650,470 H250" :color "#bf8700"
    :reads [] :writes [:dma] :label [450 463 "middle"]
    :title "Sprite DMA ($4014)"
    :about "Writing $XX to $4014 makes the CPU stop and copy 256 bytes from $XX00-$XXFF straight into the PPU's sprite memory (OAM). Much faster than writing OAMDATA 256 times."}
   {:id :nmi :d "M250,495 H650" :color "#cf222e" :dashed? true
    :reads [] :writes [:nmi] :label [450 508 "middle"]
    :title "NMI: PPU → CPU"
    :about "The Non-Maskable Interrupt. When the PPU finishes drawing a frame it can interrupt the CPU, which jumps to the address at $FFFA. Games do their per-frame updates there, during 'vertical blank'."}
   {:id :video :d "M145,510 V605" :color "#1a7f37"
    :reads [] :writes [:frames] :label [151 560 "start"]
    :title "Video out" :about "Frames sent to the TV."}
   {:id :pad :d "M760,510 V605" :color cpu-bus
    :reads [:padR] :writes [:padW] :label [766 560 "start"]
    :title "CPU ↔ controllers ($4016-$4017)" :about "Reading the buttons."}])

;; ---------------------------------------------------------------------------
;; Activity

(defn- sum [counts ks] (reduce + 0 (map #(get counts % 0) ks)))

(defn- fmt-n [n]
  (cond (>= n 1e6) (str (.toFixed (/ n 1e6) 1) "M")
        (>= n 1e4) (str (js/Math.round (/ n 1e3)) "k")
        (>= n 1e3) (str (.toFixed (/ n 1e3) 1) "k")
        :else (str (js/Math.round n))))

(defn- link-activity
  "How busy `link` is: {:reads :writes :level (0-1) :text}."
  [{:keys [reads writes]} {:keys [counts elapsed-ms]} step?]
  (let [r (sum counts reads)
        w (sum counts writes)
        per-s (if (or step? (not (pos? elapsed-ms))) 1 (/ 1000 elapsed-ms))
        r (* r per-s)
        w (* w per-s)
        total (+ r w)
        unit (if step? "" "/s")
        text (cond
               (zero? total) nil
               (and (seq reads) (seq writes)) (str "R " (fmt-n r) unit " · W " (fmt-n w) unit)
               :else (str (fmt-n total) unit))]
    {:reads r :writes w
     :level (cond (zero? total) 0
                  step? 1
                  :else (min 1 (max 0.15 (/ (js/Math.log10 (inc total)) 6))))
     :text text}))

;; ---------------------------------------------------------------------------
;; Drawing

(def ^:private svg-style
  ".flow { stroke-dasharray: 9 7; animation: nes-flow 0.6s linear infinite; }
   .flow.rev { animation-direction: reverse; }
   @keyframes nes-flow { to { stroke-dashoffset: -16; } }
   @media (prefers-reduced-motion: reduce) { .flow { animation: none; } }")

(defn- navigate! [nav]
  (when nav
    (case (first nav)
      :tab (swap! db assoc :inspector-tab (second nav))
      :memory (let [[_ space addr] nav]
                (memory-view/show! space addr)
                (swap! db assoc :inspector-tab :memory)))))

(defn- chip [{:keys [id x y w h title sub dashed? inner? outside? nav]} hovered? lit? traced]
  [:g {:on-mouse-enter #(reset! hover [:chip id])
       :on-mouse-leave #(reset! hover nil)
       :on-click #(if (= id :apu)
                    ;; the APU has no wire to click: clicking it shows its traffic
                    (swap! bus-filter update :only (fn [o] (when (not= o [:chip :apu]) [:chip :apu])))
                    (navigate! nav))
       :style {:cursor (if (or nav (= id :apu)) "pointer" "default")}}
   (when traced
     [:rect {:x (- x 5) :y (- y 8) :width (+ w 10) :height (+ h 16) :rx 6
             :fill "none" :stroke traced :stroke-width 4 :opacity 0.8}])
   ;; little pins along the top and bottom, like a real chip
   (when-not (or inner? outside?)
     (for [px (range (+ x 12) (+ x w -6) 14)]
       ^{:key px}
       [:g
        [:rect {:x px :y (- y 5) :width 5 :height 5 :fill "#8c959f"}]
        [:rect {:x px :y (+ y h) :width 5 :height 5 :fill "#8c959f"}]]))
   [:rect {:x x :y y :width w :height h :rx (if inner? 4 3)
           :fill (cond outside? "white" inner? (if lit? "#6e40c9" "#57606a") :else "#24292f")
           :stroke (cond hovered? "#fd8c73" dashed? "#8c959f" outside? "#8c959f" :else "none")
           :stroke-width (if hovered? 3 1.5)
           :stroke-dasharray (when dashed? "6 4")
           :opacity (if (and dashed? (not hovered?)) 0.55 1)}]
   [:text {:x (+ x (/ w 2)) :y (+ y (if (or inner? (> h 100)) 20 (if sub (- (/ h 2) 2) (+ (/ h 2) 5))))
           :text-anchor "middle" :fill (if outside? "#24292f" "white")
           :font-size (if inner? 13 17) :font-weight "bold"}
    title]
   (when sub
     [:text {:x (+ x (/ w 2)) :y (+ y (if (or inner? (> h 100)) (if inner? 36 38) (+ (/ h 2) 16)))
             :text-anchor "middle" :fill (if outside? "#57606a" "#d0d7de")
             :font-size (if inner? 10 12)}
      sub])])

(defn- link-path [{:keys [id d color dashed? label]} {:keys [level text reads writes]} hovered? traced selected?]
  (let [[lx ly anchor] label]
    [:g {:on-mouse-enter #(reset! hover [:link id])
         :on-mouse-leave #(reset! hover nil)
         :on-click #(swap! bus-filter update :only (fn [o] (when (not= o [:link id]) [:link id])))
         :style {:cursor "pointer"}}
     ;; the route of the bus log entry under the mouse
     (when traced
       [:path {:d d :fill "none" :stroke traced :stroke-width 12 :stroke-linecap "round" :opacity 0.5}])
     (when selected?
       [:path {:d d :fill "none" :stroke "#fd8c73" :stroke-width 9 :stroke-linecap "round" :opacity 0.5}])
     ;; the idle wire
     [:path {:d d :fill "none" :stroke (if hovered? "#fd8c73" "#afb8c1") :stroke-width (if hovered? 4 2.5)
             :stroke-dasharray (when dashed? "5 4")}]
     ;; traffic on it
     (when (pos? level)
       [:path {:d d :fill "none" :stroke color
               :stroke-width (+ 2.5 (* 4 level)) :stroke-linecap "round"
               :opacity (+ 0.4 (* 0.6 level))
               :class (str "flow" (when (> reads writes) " rev"))}])
     ;; a wide invisible copy, so it's easy to hover
     [:path {:d d :fill "none" :stroke "transparent" :stroke-width 14}]
     (when text
       [:text {:x lx :y ly :text-anchor anchor :font-size 11 :fill color
               :font-family "ui-monospace, Menlo, Consolas, monospace"
               :stroke "white" :stroke-width 3 :paint-order "stroke"}
        text])]))

(defn- diagram [header activity step?]
  (let [h @hover
        [hk route-hovered color-hovered] h
        traced (fn [kind id] (when (and (= hk :event) (on-route? route-hovered [kind id])) color-hovered))
        only (:only @bus-filter)
        acts (into {} (for [l links] [(:id l) (link-activity l activity step?)]))
        counts (:counts activity)
        lit {:oam (pos? (get counts :dma 0))
             :palette (pos? (get counts :pal 0))
             :apu (pos? (+ (get counts :apuR 0) (get counts :apuW 0)))}]
    [:svg {:viewBox "0 0 900 660" :style {:width "100%" :max-width "900px" :display "block"}
           :font-family "system-ui, -apple-system, Segoe UI, sans-serif"}
     [:style svg-style]
     ;; the cartridge
     [:rect {:x 20 :y 20 :width 860 :height 175 :rx 12 :fill "#fff8c5" :stroke "#d4a72c"}]
     [:text {:x 36 :y 44 :font-size 15 :font-weight "bold" :fill "#7d4e00"} "Game Pak (cartridge)"]
     ;; the connector between them
     (for [px (range 40 862 9)]
       ^{:key px} [:rect {:x px :y 198 :width 5 :height 13 :fill "#d4a72c"}])
     [:text {:x 450 :y 228 :text-anchor "middle" :font-size 10 :fill "#7d4e00"} "72-pin cartridge connector"]
     ;; the console
     [:rect {:x 20 :y 215 :width 860 :height 370 :rx 12 :fill "#ddf4ff" :stroke "#54aeff"}]
     [:text {:x 450 :y 572 :text-anchor "middle" :font-size 15 :font-weight "bold" :fill "#0550ae"} "NES Control Deck"]
     ;; the mapper's bank-select lines
     (when (some-> header :mapper pos?)
       [:g {:stroke "#8c959f" :stroke-width 1.5 :stroke-dasharray "2 4" :fill "none"}
        [:path {:d "M360,80 H240"}]
        [:path {:d "M540,75 H570 V60 H680"}]])
     (for [l links]
       ^{:key (:id l)} [link-path l (acts (:id l)) (= h [:link (:id l)]) (traced :link (:id l)) (= only [:link (:id l)])])
     (for [c (chips header)]
       ^{:key (:id c)} [chip c (or (= h [:chip (:id c)]) (= only [:chip (:id c)])) (lit (:id c)) (traced :chip (:id c))])]))

;; ---------------------------------------------------------------------------
;; Text panels

(defn- hover-info [header activity step?]
  (let [[kind id] @hover]
    [:div {:style {:min-height "64px" :font-size "14px" :margin "8px 0"}}
     (case kind
       :chip (let [{:keys [title sub about nav]} (some #(when (= id (:id %)) %) (chips header))]
               [:div [:strong title] (when sub [:span {:style muted} " · " sub])
                [:div about]
                (when nav [:div {:style (merge muted {:font-size "12px"})} "Click to inspect."])])
       :link (let [{:keys [title about] :as l} (some #(when (= id (:id %)) %) links)
                   {:keys [text]} (link-activity l activity step?)]
               [:div [:strong title] (when text [:span {:style (merge mono muted)} " · " text])
                [:div about]
                [:div {:style (merge muted {:font-size "12px"})}
                 "Its entries in the bus log are highlighted. Click to show only those."]])
       :event (let [[_ _ _ text] @hover] [:div text])
       [:div {:style muted}
        "Hover over a chip or a wire to see what it does; click a chip to inspect it. "
        "Wires light up with traffic on the CPU's bus, flowing in the direction the data moves."])]))

(def ^:private link-titles
  {:ppu-regs "the CPU–PPU wire" :chr "on to CHR (PPU bus)" :vram "on to video RAM (PPU bus)"
   :dma "the sprite DMA path" :wram "work RAM" :pad "the controller wire" :mapper "the cartridge wire"})

(defn- route-text
  "An access and its route, in words, for the hover panel."
  [{:keys [addr write? value target dest reg]}]
  (str (when reg (str reg " ")) (if write? "write ← " "read → ") (hex value 2) ": "
       (case dest
         :ppu (str "CPU → PPU registers over the CPU's bus"
                   (case target
                     :chr ", then the PPU carries it on over its own bus to CHR"
                     :vram ", then the PPU carries it on over its own bus to video RAM"
                     :pal ", into palette RAM inside the PPU"
                     ""))
         :sprite-dma "CPU → sprite DMA: 256 bytes from work RAM into the PPU's sprite memory (OAM)"
         :controllers (if write? "CPU → controllers (latching the buttons)" "controller → CPU (one button)")
         :sound "CPU → APU. The sound chip is inside the CPU, so this access never leaves the chip"
         :mapper "CPU → cartridge: ROM ignores it, the mapper chip acts on it"
         "")))

(defn- event-line [{:keys [write? value repeat frame scanline dest reg addr route] :as e} highlight?]
  (let [color (dest-colors dest "#8c959f")
        desc (registers/describe addr value write?)]
    [:tr {:style {:background (when highlight? "#fff8c5") :cursor "default"}
          :on-mouse-enter #(reset! hover [:event route color (route-text e)])
          :on-mouse-leave #(reset! hover nil)}
     [:td {:style {:width "4px" :background color :padding 0}}]
     [:td {:style (merge muted {:padding "0 8px 0 6px" :white-space "nowrap"})} frame ":" scanline]
     [:td {:style {:padding-right "6px" :color (if write? "#cf222e" "#1a7f37")}} (if write? "W" "R")]
     [:td {:style {:padding-right "6px"}} reg]
     [:td {:style (merge muted {:padding-right "6px"})} (subs (hex addr 4) 1)]
     [:td {:style {:padding-right "8px" :white-space "nowrap"}} (if write? "← " "→ ") (hex value 2)]
     [:td {:style muted} desc (when (> repeat 1) (str " (×" repeat ")"))]]))

(defn- toggle [s x] (if (contains? s x) (disj s x) (conj s x)))

(defn- filter-bar [events]
  (let [{:keys [hidden hidden-regs expanded only]} @bus-filter
        by-dest (group-by :dest events)]
    [:div {:style {:font-size "12px" :margin-bottom "6px"}}
     [:div {:style {:display "flex" :gap "6px" :flex-wrap "wrap" :align-items "center"}}
      (for [{:keys [id label about]} registers/destinations
            :let [n (reduce + (map :repeat (by-dest id)))
                  on? (not (hidden id))
                  color (dest-colors id)]]
        ^{:key id}
        [:span {:style {:display "inline-flex" :align-items "center"
                        :border (str "1px solid " (if on? color "#d0d7de")) :border-radius "12px"
                        :background (if on? "white" "#f6f8fa") :opacity (if on? 1 0.6)}}
         [:button {:title (str about ". Click to " (if on? "hide" "show") " these.")
                   :on-click #(swap! bus-filter update :hidden toggle id)
                   :style {:border "none" :background "none" :cursor "pointer" :padding "2px 4px 2px 8px"
                           :display "inline-flex" :gap "5px" :align-items "center" :font-size "12px"}}
          [:span {:style {:width "9px" :height "9px" :border-radius "2px"
                          :background (if on? color "transparent") :border (str "1.5px solid " color)}}]
          label [:span {:style muted} n]]
         [:button {:title "Show or hide individual registers"
                   :on-click #(swap! bus-filter update :expanded (fn [e] (when (not= e id) id)))
                   :style {:border "none" :background "none" :cursor "pointer" :padding "2px 8px 2px 2px"
                           :color "#57606a" :font-size "11px"}}
          (if (= expanded id) "▾" "▸")]])
      (when only
        [:span {:style {:display "inline-flex" :gap "4px" :align-items "center" :margin-left "6px"
                        :background "#fff1e5" :border "1px solid #fd8c73" :border-radius "12px"
                        :padding "1px 4px 1px 8px"}}
         "Only " (if (= only [:chip :apu]) "the APU (inside the CPU)"
                     (:title (some #(when (= (second only) (:id %)) %) links)))
         [:button {:on-click #(swap! bus-filter assoc :only nil) :title "Show everything again"
                   :style {:border "none" :background "none" :cursor "pointer"}} "✕"]])]
     (when expanded
       (let [regs (sort-by key (frequencies (mapcat #(repeat (:repeat %) (:reg %)) (by-dest expanded))))]
         [:div {:style {:display "flex" :gap "4px 14px" :flex-wrap "wrap" :margin "6px 0 0 8px"}}
          (if (empty? regs)
            [:span {:style muted} "None in the log yet."]
            (for [[reg n] regs]
              ^{:key reg}
              [:label {:style {:cursor "pointer" :white-space "nowrap"}}
               [:input {:type "checkbox" :checked (not (hidden-regs reg))
                        :on-change #(swap! bus-filter update :hidden-regs toggle reg)}]
               " " [:span {:style mono} reg] [:span {:style muted} " " n]]))]))]))

(defn- bus-log []
  (let [log @console/bus-log
        {:keys [hidden hidden-regs only]} @bus-filter
        h @hover
        events (for [{:keys [addr write?] :as e} log]
                 (assoc e
                        :dest (registers/destination addr write?)
                        :reg (:name (registers/register-at addr write?))
                        :route (route e)))
        shown (filter #(and (not (hidden (:dest %)))
                            (not (hidden-regs (:reg %)))
                            (or (nil? only) (on-route? (:route %) only)))
                      events)
        hovered-part (when (#{:link :chip} (first h)) h)]
    [:div {:style {:margin-top "12px"}}
     [:h4 {:style {:margin "0 0 4px"}} "Bus log"]
     [:div {:style (merge muted {:font-size "12px" :margin-bottom "6px" :max-width "760px"})}
      "All the blue wires are one bus: the CPU puts an address on it each cycle, and a chip on the "
      "board decides which part answers. This lists the accesses to registers and the mapper, newest "
      "first (RAM and ROM reads are most of the traffic, and are left out). Hover over one to see its "
      "route on the schematic; click a wire to show only its traffic. "
      "Frame:scanline, read or write, register, address, value."]
     [filter-bar events]
     (if (empty? shown)
       [:div {:style (merge muted {:font-size "13px"})} (if (empty? log) "Nothing yet." "Nothing to show with these filters.")]
       [:div {:style {:max-height "260px" :overflow-y "auto"}}
        [:table {:style (merge mono {:font-size "12px" :border-collapse "collapse"})}
         [:tbody
          (for [[i e] (map-indexed vector (take 80 (reverse shown)))]
            ^{:key i} [event-line e (and hovered-part (on-route? (:route e) hovered-part))])]]])]))

(defn- step-accesses [{:keys [accesses dropped]}]
  [:div {:style {:margin-top "12px"}}
   [:h4 {:style {:margin "0 0 4px"}} "This step's bus accesses"]
   [:div {:style (merge muted {:font-size "12px" :margin-bottom "6px"})}
    "Every read and write the CPU made, in order. The first is the CPU fetching the instruction itself from memory."]
   [:table {:style (merge mono {:font-size "12px" :border-collapse "collapse"})}
    [:tbody
     (for [[i {:keys [addr value write?]}] (map-indexed vector accesses)
           :let [region (memory-map/region-at :cpu addr)
                 ;; name the I/O register, if it is one (and the mapper,
                 ;; but only for writes: reads of ROM are just ROM)
                 reg-name (when-let [{:keys [name]} (registers/register-at addr write?)]
                            (when (or write? (not= "MAPPER" name)) name))]]
       ^{:key i}
       [:tr
        [:td {:style {:padding-right "6px" :color (if write? "#cf222e" "#1a7f37")}} (if write? "W" "R")]
        [:td {:style {:padding-right "8px"}} (hex addr 4)]
        [:td {:style {:padding-right "8px" :white-space "nowrap"}} (if write? "← " "→ ") (hex value 2)]
        [:td {:style muted} (:name region) (when reg-name (str " · " reg-name))]])]]
   (when (pos? dropped)
     [:div {:style (merge muted {:font-size "12px"})} "(" dropped " earlier accesses not shown)"])])

;; ---------------------------------------------------------------------------

(defn- schematic-body []
  (let [{:keys [rom status]} @db
        activity @console/bus-activity
        step? (and (= status :paused) (= :step (:kind activity)))
        activity (if (or step? (= status :running)) activity {:counts {}})
        header (:header rom)]
    [:div
     [:div {:style (merge muted {:font-size "13px" :margin-bottom "6px"})}
      (case status
        :running "Live: traffic per second."
        :paused (if step?
                  "Paused: showing the traffic from the last step."
                  "Paused. Use Step instruction or Step frame to see what one step does.")
        "Load a ROM to see traffic.")]
     [diagram header activity step?]
     [hover-info header activity step?]
     (when (and step? (seq (:accesses activity)))
       [step-accesses activity])
     [bus-log]]))

(defn schematic-view
  "The schematic tab. Turns the bus monitor on while it's showing."
  []
  (r/create-class
   {:display-name "schematic-view"
    :component-did-mount #(console/monitor-bus! true)
    :component-will-unmount #(console/monitor-bus! false)
    :reagent-render (fn [] [schematic-body])}))
