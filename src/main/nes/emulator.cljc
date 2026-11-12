(ns nes.emulator
  "The boundary between the UI and whatever is actually emulating the NES.

  The rest of the app talks to an emulator only through these protocols.
  Today the only implementation wraps jsnes (a mature JavaScript
  emulator); later we can add our own ClojureScript CPU behind the same
  protocols and run the two side by side.

  'Byte array' here means whatever nes.bytes makes: a Uint8Array in the
  browser, a byte[] on the JVM.")

(defprotocol Emulator
  (load-rom! [emu bytes] "Insert a cartridge: `bytes` is a byte array of an iNES file.")
  (press-reset! [emu] "Press the console's reset button.")
  (frame! [emu]
    "Run the emulator until the PPU has drawn one full frame. Returns nil,
    or, if a breakpoint (see `Debug`) stopped it first, the hit:
    {:kind :exec/:read/:write, :addr, :value, :pc, :breakpoint}.
    Calling it again carries on from there.")
  (step-instruction! [emu]
    "Run exactly one CPU instruction. Returns true if that finished a frame.")
  (button-down! [emu player button] "Press `button` (a keyword like :a or :up) on controller `player` (1 or 2).")
  (button-up! [emu player button] "Release `button` on controller `player`.")
  (set-audio-sink! [emu f]
    "Send sound to `f`, called with each sample as (f left right), both
    -1.0 to 1.0, at `sample-rate` samples per second of emulated time.
    nil for no sound.")
  (picture-so-far! [emu]
    "While the PPU is partway through drawing the picture: {:pixels :line},
    where `:pixels` (an array of 32-bit numbers of 256x240 0x00BBGGRR pixels, like a
    finished frame) is complete for lines above `:line`, the one being
    drawn now. nil when the PPU isn't drawing (in vertical blank).
    Makes sure every finished line is in `:pixels`, which doesn't change
    what the frame ends up looking like."))

(def sample-rate
  "Audio samples per second the emulator produces."
  48000)

(def buttons
  "The eight buttons on an NES controller, in the order the console reads them."
  [:a :b :select :start :up :down :left :right])

(defprotocol Inspect
  "Read-only views into the emulated hardware, for the inspector panels.
  None of these should change the emulator's state."
  (ppu-memory [emu]
    "The PPU's live memory as a byte array, indexed by PPU address. Pattern
    tables (tile graphics) are at $0000-$1FFF, palette RAM at $3F00-$3F1F.
    Read it, never write it. Use `ppu-peek` for nametables ($2000-$3EFF),
    whose addresses are mirrored.")
  (ppu-peek [emu addr]
    "The byte the PPU would see at PPU address `addr` ($0000-$3FFF).")
  (master-palette [emu]
    "The 64 colors the NES can produce, as an array of 32-bit numbers of 0x00BBGGRR
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
    256-byte array: 64 sprites of 4 bytes each (Y, tile, attributes, X).")
  (ppu-flags [emu]
    "The PPU's current settings from PPUCTRL and PPUMASK, as a map:
    `:nmi?` `:sprite-height` (8 or 16) `:bg-table` `:sprite-table` (0 or 1)
    `:show-bg?` `:show-sprites?`")
  (ppu-registers [emu]
    "The PPU's three settings/status registers as bytes:
    `:ctrl` (PPUCTRL, $2000), `:mask` (PPUMASK, $2001) and `:status`
    (PPUSTATUS, $2002). PPUCTRL and PPUMASK are write-only, so these are
    what the CPU last wrote. Reading them here has no side effects (a real
    read of PPUSTATUS would clear its vblank bit).")
  (rom-file [emu]
    "The cartridge as an iNES file (a byte array, header included), with
    any changes made by `rom-poke!`. Read it, never write it.")
  (mirroring [emu]
    "How the cartridge arranges the nametables right now: :horizontal,
    :vertical, :single-screen or :four-screen. (Some mappers change it.)"))

(defprotocol Poke
  "Changing the emulated machine's memory from outside, for experiments."
  (cpu-poke! [emu addr value]
    "Store `value` at CPU address `addr`, directly in memory, without the
    side effects of a real bus write. Only RAM ($0000-$1FFF, mirrored) and
    cartridge RAM ($6000-$7FFF) can be poked. Returns true if it was
    written, false if `addr` isn't RAM.")
  (rom-poke! [emu offset value]
    "Change the cartridge's program ROM: the byte at `offset` in the iNES
    file (counting the 16-byte header, as hex editors do). Takes effect in
    the running game straight away, and lasts until another ROM is loaded.
    Returns true, or false if `offset` isn't in the program ROM (for the
    graphics, see `tile-poke!`).")
  (tile-poke! [emu addr value]
    "Change a byte of tile graphics: PPU address `addr` ($0000-$1FFF, the
    two pattern tables), as the PPU sees them right now. The picture
    changes straight away. Returns where the change went:
      :rom   into the cartridge's CHR ROM as well, so it survives Reset
             and is in the file from `rom-file`
      :live  only into the PPU's memory: the tiles there came from CHR
             RAM, or from a bank that couldn't be identified, so it lasts
             until the game writes or switches them again
      false  `addr` isn't in the pattern tables"))

(defprotocol BusMonitor
  "Watching the traffic on the CPU's bus. Monitoring slows emulation down
  a little, so it's off unless something asks for it."
  (monitor-bus! [emu on?] "Turn bus monitoring on or off.")
  (take-bus-activity! [emu]
    "What happened on the bus since the last call, or nil if monitoring is
    off. A map of `:counts` (counter keyword -> number), `:events` (I/O
    register accesses), `:accesses` (the most recent individual accesses)
    and `:dropped`."))

(defprotocol CodeLog
  "Recording which bytes of the program ROM run as code and which are read
  as data, and where subroutines start. See nes.code-log."
  (log-code! [emu on?]
    "Start (true) or stop (false) recording. Stopping keeps what was
    recorded; recording again adds to it.")
  (clear-code-log! [emu] "Forget everything recorded.")
  (code-log [emu]
    "What's been recorded: {:flags :extra :calls :frames :header-size
    :chr-size :recording?}, or nil if nothing has been. The arrays are live:
    read them, don't change them."))

(defprotocol CpuChoice
  "Which 6502 runs the game. (The rest of the console stays the same.)"
  (use-cpu! [emu which]
    ":jsnes for jsnes's own CPU, :cljs for NES.cljs's ClojureScript one
    (nes.cpu.core).")
  (cpu-in-use [emu] ":jsnes or :cljs.")
  (cpu-stats [emu]
    "With the ClojureScript CPU: how many instructions each CPU has run,
    {:cljs n :jsnes n} (jsnes still takes interrupts and unofficial
    opcodes)."))

(defprotocol Debug
  "Stopping the emulator when something happens. See nes.breakpoints."
  (set-breakpoints! [emu bps]
    "Use `bps` (a seq of breakpoint maps, see nes.breakpoints) from now
    on. `frame!` stops at them; single steps don't."))
