# Writing NES games in ClojureScript: design notes

Notes from a conversation about where NES.cljs could go: writing an NES
game in ClojureScript and building it into a `.nes` ROM that runs here or
in any other emulator. This is thinking out loud, to come back to.

Built so far (see "Making games" in `TODO.md`): layer 1, as the
`gamedev-toolkit` library (`src/main/gamedev_toolkit`), and the first
milestone, `src/main/games/hello.cljc`.

## The question

Assembly language is procedural and stateful. ClojureScript is functional,
with immutable data. How do the two worlds meet? Can 6502 subroutines be
ClojureScript functions, and can they compose? Could a game engine (say,
for platformers like Super Mario Bros. or Mega Man) be built from them?

## The reframe: Clojure runs at build time, the 6502 runs the game

ClojureScript never has to run *on* the NES. Its job is to **generate** the
program. The NES has 2 KB of RAM and no garbage collector, so persistent
data structures and closures can't live there. But a 6502 program is just
data: a sequence of instructions, tables and graphics. Building and
transforming data is what Clojure is good at.

| | Build time (ClojureScript) | Run time (6502) |
|---|---|---|
| Style | immutable, functional, REPL | mutable, procedural, tiny |
| Does | code generators, asset pipelines, lookup tables, level data, checking | only what has to happen while the game runs |
| Has | as much memory and time as you like | 2 KB of RAM, ~29,800 CPU cycles per frame |

The more that can be computed in the first column, the less the second has
to do. NES programmers did this by hand: Mega Man 2's damage tables and
speeds (see `src/main/nes/lenses/megaman2.cljs`) are values precomputed and
baked into the ROM.

## Walking up the abstraction ladder

The plan is to climb in well-defined steps, each layer built from the one
below, and each usable and testable on its own. That's how Lisp programmers
like to work, and it's close to how *Structure and Interpretation of
Computer Programs* builds things up (see "The SICP connection" below).

1. **Instructions as data.** `[:lda 0x10]`, `[:sta :player-x]`. An assembler
   turns a vector of these into bytes, with labels and the iNES header. This
   is the only layer that knows opcodes. (We already have the opcode table,
   for the disassembler: `src/main/nes/cpu/opcodes.cljc`.)
2. **Code generators.** Functions that return instructions, and so compose
   like any other function: `(add16 :score :points)`, `(times 8 body)`.
3. **Subroutines.** Generated code called with `JSR`, defined once, with
   the linker including only what's reached.
4. **Memory as data.** Declare variables and let an allocator place them.
   The compiler can then write the game's lens and debugger symbols.
5. **Structure.** A state machine for the game's screens and loops, and
   entity types with their own update routines, all described as data.
6. **An engine.** Controller, NMI, sprites, metatiles, collision, physics,
   scrolling, objects, sound: built from layers 2–5.
7. **Perhaps, later, a small language** of expressions and typed variables
   that compiles to layer 2, for logic where registers are just noise.

You'd rarely write layer 1 by hand. Most of a game would be written with
layers 2–6, reaching down only when something needs to be fast or exact.
And when you do reach down, the emulator, debugger and lenses here show
exactly what each instruction did.

## Layer 2: code generators

Because code is data, a function that **returns instructions** composes
like any other function:

```clojure
(defn add16 [dst src]          ; dst += src, 16-bit
  [[:clc]
   [:lda dst]       [:adc src]       [:sta dst]
   [:lda (inc dst)] [:adc (inc src)] [:sta (inc dst)]])

(defn times [n body]           ; a counted loop around any code
  (let [top (gensym "loop")]
    [[:ldx n] [:label top] body [:dex] [:bne top]]))

(times 8 (add16 :x :speed))    ; composes: just nested data
```

What has to be handled:

- **Unique labels.** `gensym`, the same way Clojure keeps macros from
  capturing names.
- **Registers get clobbered.** Each generator should state, as data, which
  registers and flags it uses and changes. The composer can then warn about
  (or save) registers that clash.
- **Optimization is data transformation.** E.g. drop a `LDA x` that follows
  `STA x`. Plain functions over vectors.

## Layer 3: subroutines

A `defsub` registers a routine once and gives back a "call this" form; the
linker includes only routines that are actually reached, so unused ones
never take ROM space.

- **Calling convention:** the 6502 has three registers (A, X, Y) and a
  256-byte stack, so arguments go in registers or in reserved zero-page
  "argument" bytes.
- **No recursion,** in practice. NES games don't need it.
- **Inline or call?** The same code can be either. Inlining is faster;
  calling is smaller. That can be a decision made per use, at build time.

## Layer 4: memory declared as data

Don't hand-pick RAM addresses. Declare variables, and let a build-time
allocator place them:

```clojure
(defvars
  {:player  {:x :u16-fixed, :y :u16-fixed, :vx :s8, :vy :s8, :state :u8}
   :objects (soa 16 {:type :u8, :x :u8, :y :u8, :hp :u8})})
```

- **The lens comes for free.** Once the compiler knows where everything
  lives, it can register a lens for the game, and debugger symbols:
  `STA player.x` in the disassembly instead of `STA $0043`, and "break on
  write to `player.hp`". Everything built for reverse-engineering Mega Man 2
  and Zelda becomes the IDE for your own game.
- **Structure of arrays (`soa`):** one table per field, indexed by slot.
  It's what Mega Man 2 does (`$0400,x` sprite, `$0420,x` flags, `$0460,x`
  X...), because on the 6502 `LDA table,X` is cheap. Laying a table of
  records out that way is just a data transformation at build time.
- **Zero page** (the first 256 bytes, faster to reach) is scarce. The
  allocator can put the most-used variables there.

## Layer 5: game loops and state machines

An NES game is a main loop plus the NMI interrupt that fires at every
vertical blank:

- **Main loop:** run the current state's logic, then wait for the next
  frame.
- **NMI:** copy sprites to the PPU (OAM DMA) and flush queued video memory
  writes. Zelda keeps exactly such a queue at `$0302`.

A game is a series of these loops (title, playing, paused, game over...)
with transitions between them: a state machine. As Clojure data:

```clojure
{:title     {:enter  [...]
             :update [...]
             :on     {:start-pressed :playing}}
 :playing   {:enter  load-level
             :update [read-pad update-objects scroll]
             :on     {:player-dead :game-over}}
 :game-over {...}}
```

The compiler turns this into a jump table and a "current state" variable.

Entity types work the same way: each kind of object (player, enemy,
bullet) declares its update and draw routines, and the compiler builds the
dispatch table. A protocol or multimethod, resolved at build time.

## Layer 6: a platformer engine

| Piece | Notes |
|---|---|
| Controller | read once a frame, keep held and newly pressed |
| NMI handler | sprite DMA, queue of video memory writes, scroll |
| Metasprites | a character made of several 8×8 sprites, as data |
| Metatiles | 16×16 blocks with collision attributes |
| Physics | fixed-point positions and speeds (Mega Man 2 uses a whole number plus a fraction) |
| Collision | against metatiles, and box-against-box between objects |
| Scrolling | camera, and streaming new columns into the nametable during vblank |
| Objects | slots with spawn/despawn, animation tables |
| Sound | a small sound engine (or an existing one, like FamiTone) |
| Mapper | NROM (32 KB, no banks) first, MMC1 when the game outgrows it |

Level data is where Clojure shines most: design levels as EDN (or in an
editor here), and compress them into metatiles at build time.

## Real incompatibilities

1. **You can't compile ordinary ClojureScript to the 6502.** Lazy
   sequences, maps and closures need a heap; the NES has 2 KB. The game is
   written in a *restricted* form: generators and data, or (layer 7) a
   small language. Anything that looks like general Clojure on the target
   is a trap.
2. **The frame budget is strict.** About 29,800 CPU cycles per frame for
   everything, and only about 2,270 during vblank for writing to video
   memory. Abstractions have to cost nothing at run time: build-time
   computation and inlining matter.
3. **Arithmetic is 8-bit.** No multiply or divide, and every 16-bit value
   costs extra instructions. Types like `:u8`, `:s8`, `:u16-fixed` must be
   explicit so the generators pick the right instructions.
4. **No immutability on the target.** You can still get its benefits while
   developing: if game logic is written in a small DSL, it could have two
   back ends, an interpreter in ClojureScript (fast iteration, property
   tests, the REPL) and 6502 code generation. Run both and compare RAM
   through the generated lens: differential testing of the compiler. It's
   twice the work, so worth it only for core logic.

## ClojureScript features that help

- **Code as data:** instructions are vectors, so composing, inspecting and
  optimizing them needs no macros.
- **test.check:** property-test a generated routine by running it in the
  emulator here against a reference Clojure function ("16-bit add matches
  `+` for all inputs").
- **spec or malli:** validate instruction forms and level data before they
  become bytes.
- **The REPL and the emulator on one page:** recompile and reload in a
  second. Someday maybe hot-swap code while keeping RAM, since the
  allocator knows where each variable moved.
- **What's already here:** the disassembler, breakpoints, lenses, the
  partial-frame stepping and the PPU views are a debugger for the games
  we'll build.

## The SICP connection

Chapter 5 of *Structure and Interpretation of Computer Programs* ("Computing
with Register Machines") is close to this plan:

- **5.1–5.2** describe register machines in a little Lisp language of
  instructions (`assign`, `test`, `branch`, `goto`, `save`, `restore`) and
  build a simulator for them in Scheme. Our layer 1 is the same idea, with
  the 6502 as the machine and jsnes as the simulator.
- **5.4** writes a Lisp evaluator *as* register-machine code: a real
  program written at the bottom of the ladder.
- **5.5** builds a **compiler** from Scheme to register-machine instructions.
  It's written as functions that return instruction sequences and combine
  them while tracking which registers each piece needs and changes. That's
  layers 2 and 3 above, almost exactly.

The difference: SICP's machine is idealized (unlimited registers, a stack,
a garbage-collected heap in 5.3), and ours is real, with three registers,
256 bytes of stack and 2 KB of RAM. That's why the game-level layers
(4–6) lean so heavily on static, build-time decisions. It's also why the
restriction in incompatibility 1 exists: SICP can compile all of Scheme
because its machine has a heap.

Chapter 4's "metalinguistic abstraction" (making new languages to suit the
problem) is the spirit of layers 5 and 7.

## About assembly being intimidating

- **There isn't much of it.** The 6502 has 56 instructions, and a game uses
  a couple of dozen of them most of the time. Most are "load this", "store
  that", "add", "compare", "branch if".
- **You'd write little of it by hand.** Layer 2 generators exist so the
  repetitive parts (16-bit math, loops, copying) are written once.
- **It's very visible here.** Every instruction can be stepped, with the
  registers, flags, memory, bus traffic and even the half-drawn picture
  shown as it happens. Few environments make assembly this inspectable.
- **Getting used to it with a real game first:** step through Concentration
  Room's NMI handler (it starts at `$C001`: `INC $10`), or set a breakpoint
  on writes to Mega Man's HP and read the code that subtracts damage. Reading
  real code before writing any is a gentle way in.

## Prior art

- Henry Baker, **COMFY-6502** (1997): a 6502 compiler embedded in Lisp, very
  close to this idea.
- **NESFab**: a modern language designed for NES games.
- **cc65**: C for the 6502, and a lesson in what doesn't map well.
- **8bitworkshop**: NES development in the browser, emulator beside the
  code.
- The **NESdev wiki** (nesdev.org) and **nesdoug**'s tutorials.

## A first milestone

Tiny on purpose: an assembler (layer 1) that builds a ROM showing a colored
screen and a sprite you can move with the controller, loaded straight into
the emulator here. Then the allocator and symbol export (layer 4), because
that's what connects it to the inspectors. Then the state machine and NMI
framework, objects, and the platformer pieces.

## Open questions

- Where does game source live: files in the repo, built with shadow-cljs?
  Or edited in the browser, with the assembler running in the page?
- Which mapper to target after NROM: UxROM (simple bank switching) or MMC1
  (more common, more complicated)?
- How should code generators declare what they clobber, and how strict
  should composition be about it?
- How far up the ladder to go before writing a real game, and which game
  (Pong? a single-screen platformer?) makes the best first target?
