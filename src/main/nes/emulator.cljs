(ns nes.emulator
  "The boundary between the UI and whatever is actually emulating the NES.

  The rest of the app talks to an emulator only through these protocols.
  Today the only implementation wraps jsnes (a mature JavaScript
  emulator); later we can add our own ClojureScript CPU behind the same
  protocols and run the two side by side.")

(defprotocol Emulator
  (load-rom! [emu bytes] "Insert a cartridge: `bytes` is a Uint8Array of an iNES file.")
  (press-reset! [emu] "Press the console's reset button.")
  (frame! [emu] "Run the emulator until the PPU has drawn one full frame.")
  (step-instruction! [emu]
    "Run exactly one CPU instruction. Returns true if that finished a frame.")
  (button-down! [emu player button] "Press `button` (a keyword like :a or :up) on controller `player` (1 or 2).")
  (button-up! [emu player button] "Release `button` on controller `player`."))

(def buttons
  "The eight buttons on an NES controller, in the order the console reads them."
  [:a :b :select :start :up :down :left :right])

(defprotocol Inspect
  "Read-only views into the emulated hardware, for the inspector panels.
  None of these should change the emulator's state."
  (ppu-memory [emu]
    "The PPU's live memory as a Uint8Array, indexed by PPU address. Pattern
    tables (tile graphics) are at $0000-$1FFF, palette RAM at $3F00-$3F1F.
    Read it, never write it. Use `ppu-peek` for nametables ($2000-$3EFF),
    whose addresses are mirrored.")
  (ppu-peek [emu addr]
    "The byte the PPU would see at PPU address `addr` ($0000-$3FFF).")
  (master-palette [emu]
    "The 64 colors the NES can produce, as a Uint32Array of 0x00BBGGRR
    numbers. Palette RAM holds indexes into this.")
  (cpu-peek [emu addr]
    "The byte at CPU address `addr`, or nil if reading it could change
    something. (Reading some I/O registers, like the PPU's at $2000-$2007,
    has side effects on real hardware, so we don't.)")
  (cpu-state [emu]
    "The CPU's registers, as a map:
    `:a :x :y` (8-bit), `:sp` (stack pointer, 8-bit), `:pc` (program
    counter, 16-bit, the address of the next instruction), `:p` (status
    flags, 8-bit) and `:cycles` (CPU cycles since power on).")
  (ppu-state [emu]
    "Where the PPU is in drawing the picture: `:scanline` (-1 to 260;
    0-239 are visible) and `:dot` (0-340, the position along the line),
    plus `:frame` (frames since the cartridge was loaded), and `:scroll`,
    {:x :y}: where in the 512x480 space of the four nametables the top-left
    of the picture was this frame, as of the first visible line.")
  (oam [emu]
    "The PPU's sprite memory (OAM, 'object attribute memory') as a
    256-byte Uint8Array: 64 sprites of 4 bytes each (Y, tile, attributes, X).")
  (ppu-flags [emu]
    "The PPU's current settings from PPUCTRL and PPUMASK, as a map:
    `:nmi?` `:sprite-height` (8 or 16) `:bg-table` `:sprite-table` (0 or 1)
    `:show-bg?` `:show-sprites?`")
  (mirroring [emu]
    "How the cartridge arranges the nametables right now: :horizontal,
    :vertical, :single-screen or :four-screen. (Some mappers change it.)"))

(defprotocol Poke
  "Changing the emulated machine's memory from outside, for experiments."
  (cpu-poke! [emu addr value]
    "Store `value` at CPU address `addr`, directly in memory, without the
    side effects of a real bus write. Only RAM ($0000-$1FFF, mirrored) and
    cartridge RAM ($6000-$7FFF) can be poked. Returns true if it was
    written, false if `addr` isn't RAM."))

(defprotocol BusMonitor
  "Watching the traffic on the CPU's bus. Monitoring slows emulation down
  a little, so it's off unless something asks for it."
  (monitor-bus! [emu on?] "Turn bus monitoring on or off.")
  (take-bus-activity! [emu]
    "What happened on the bus since the last call, or nil if monitoring is
    off. A map of `:counts` (counter keyword -> number), `:events` (I/O
    register accesses), `:accesses` (the most recent individual accesses)
    and `:dropped`."))
