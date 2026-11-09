(ns nes.emulator.jsnes
  "An `Emulator` backed by jsnes (https://github.com/bfirsh/jsnes).

  jsnes does all the work; this namespace only translates between it and
  our protocol. The underlying jsnes object is kept on the record as
  `:nes`, so you can poke at its internals from the REPL, e.g.
  `(.-mem (.-cpu (:nes emu)))` is the CPU's 64 KB address space."
  (:require ["jsnes" :refer [NES Controller]]
            [nes.emulator :as emu]))

(def ^:private button-codes
  {:a      (.-BUTTON_A Controller)
   :b      (.-BUTTON_B Controller)
   :select (.-BUTTON_SELECT Controller)
   :start  (.-BUTTON_START Controller)
   :up     (.-BUTTON_UP Controller)
   :down   (.-BUTTON_DOWN Controller)
   :left   (.-BUTTON_LEFT Controller)
   :right  (.-BUTTON_RIGHT Controller)})

(defrecord JsnesEmulator [^js nes]
  emu/Emulator
  (load-rom! [_ bytes] (.loadROM nes bytes))
  (press-reset! [_] (.reset nes))
  (frame! [_] (.frame nes))
  (button-down! [_ player button]
    (when-let [code (button-codes button)]
      (.buttonDown nes player code)))
  (button-up! [_ player button]
    (when-let [code (button-codes button)]
      (.buttonUp nes player code))))

(defn create
  "Make a jsnes-backed emulator.

  `on-frame` is called with a Uint32Array of 256x240 pixels each time a
  frame finishes. Each pixel is 0x00BBGGRR (blue in the high byte)."
  [{:keys [on-frame]}]
  (->JsnesEmulator
   (NES. #js {:onFrame on-frame
              ;; No sound yet - it needs an audio pipeline of its own.
              :emulateSound false})))
