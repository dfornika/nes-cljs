(ns nes.emulator
  "The boundary between the UI and whatever is actually emulating the NES.

  The rest of the app talks to an emulator only through the `Emulator`
  protocol. Today the only implementation wraps jsnes (a mature JavaScript
  emulator); later we can add our own ClojureScript CPU behind the same
  protocol and run the two side by side.")

(defprotocol Emulator
  (load-rom! [emu bytes] "Insert a cartridge: `bytes` is a Uint8Array of an iNES file.")
  (press-reset! [emu] "Press the console's reset button.")
  (frame! [emu] "Run the emulator until the PPU has drawn one full frame.")
  (button-down! [emu player button] "Press `button` (a keyword like :a or :up) on controller `player` (1 or 2).")
  (button-up! [emu player button] "Release `button` on controller `player`."))

(def buttons
  "The eight buttons on an NES controller, in the order the console reads them."
  [:a :b :select :start :up :down :left :right])

(defprotocol Inspect
  "Read-only views into the emulated hardware, for the inspector panels.
  These return the emulator's live memory, not copies: read from them,
  never write to them."
  (ppu-memory [emu]
    "A Uint8Array of the PPU's 16 KB address space, $0000-$3FFF:
    pattern tables (tile graphics) at $0000-$1FFF, nametables (background
    layouts) at $2000-$2FFF, palette RAM at $3F00-$3F1F.")
  (master-palette [emu]
    "The 64 colors the NES can produce, as a Uint32Array of 0x00BBGGRR
    numbers. Palette RAM holds indexes into this."))
