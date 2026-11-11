(ns nes.workbench
  "Where games built here meet the console: put a freshly built cartridge
  in, and tell the inspectors what the game calls things."
  (:require [nes.console :as console]
            [nes.debug-info :as debug-info]
            [nes.lens :as lens]
            [nes.rom :as rom]))

(defn install!
  "Register what's known about a game built with gamedev-toolkit (what its
  `build` returns), under its ROM's hash: a lens of its variables (shown
  in the Lens tab) and its symbol table (names in the disassembly, at the
  REPL and for breakpoints). Returns the ROM as a Uint8Array."
  [built]
  (let [bytes (js/Uint8Array.from (clj->js (:rom built)))
        sha1 (rom/rom-hash bytes)
        fields (debug-info/lens-fields (:memory built))]
    (debug-info/register! sha1 (debug-info/symbol-table built))
    (when (seq fields)
      (lens/register! {:rom sha1
                       :name "Variables"
                       :doc "The game's variables, as declared in its source (with gamedev-toolkit.memory)."
                       :fields fields}))
    bytes))

(defn play!
  "Build-and-run's second half: put `built` (a map with :rom, the .nes
  file's bytes, and maybe :name, :labels and :memory; or just the bytes)
  into the console and start it. Returns the ROM's size in bytes."
  [built]
  (let [built (if (map? built) built {:rom built})]
    (console/load-bytes! (or (:name built) "built.nes") (install! built))
    (count (:rom built))))
