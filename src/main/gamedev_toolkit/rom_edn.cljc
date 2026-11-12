(ns gamedev-toolkit.rom-edn
  "Cartridges as EDN: text you can read, diff, keep in git and transform
  with ordinary Clojure, and turn back into exactly the same .nes file.

  There are two kinds.

  A *container* works for any ROM. It's the .nes file's parts, with the
  bytes written as hexadecimal text:

    {:format :ines
     :about  {:mapper 0 :prg-banks 1 :chr-banks 1 :mirroring :horizontal}
     :header \"4E45531A 01010000 ...\"
     :prg    \"78D8A240 8E1740A2 ...\"
     :chr    \"...\"}

  A *source* is for games built with gamedev-toolkit. In place of the
  program's bytes it has the program: every instruction, as the
  assembler was given it, after the generators have run and the
  subroutines have been linked in. And the graphics are drawings:

    {:format  :gamedev-toolkit/source
     :symbols {:player/x 12 :ppuctrl 8192 ...}
     :code    [[:label :reset] [:sei] [:cld] [:ldx [:imm 64]] ...]
     :tiles   [[\"..111...\" \".1...1..\" ...] ...]}

  Either kind goes back to a .nes file with `->rom`, byte for byte. (For
  ROMs that weren't built here, turning the bytes back into instructions
  is disassembly, which this doesn't do: see the Code tab.)"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.chr :as chr]
            [gamedev-toolkit.rom :as rom]))

;; ---------------------------------------------------------------------------
;; Bytes as hex text

(def ^:private digits "0123456789ABCDEF")

(defn hex
  "Bytes as text: two hex digits each, in groups of four bytes, eight
  groups to a line."
  [bytes]
  (->> bytes
       (map #(str (nth digits (bit-shift-right % 4)) (nth digits (bit-and % 15))))
       (partition-all 4)
       (map #(apply str %))
       (partition-all 8)
       (map #(str/join " " %))
       (str/join "\n")))

(defn unhex
  "The bytes in hex text (spaces and line breaks are ignored)."
  [text]
  (let [value (fn [c]
                (let [i (str/index-of digits (str/upper-case (str c)))]
                  (or i (throw (ex-info (str "Not a hex digit: " (pr-str c)) {})))))
        cs (remove #{\space \newline \return \tab} text)]
    (when (odd? (count cs))
      (throw (ex-info "Hex text needs two digits for every byte" {})))
    (mapv (fn [[a b]] (+ (* 16 (value a)) (value b))) (partition 2 cs))))

;; ---------------------------------------------------------------------------
;; Any ROM: a container

(defn container
  "A .nes file (a sequence of bytes) as a container map."
  [rom-bytes]
  (let [bytes (vec rom-bytes)
        _ (when-not (= [0x4E 0x45 0x53 0x1A] (take 4 bytes))
            (throw (ex-info "Not an iNES (.nes) file" {})))
        [_ _ _ _ prg-banks chr-banks flags6 flags7] bytes
        trainer-size (if (bit-test flags6 2) 512 0)
        prg-size (* prg-banks rom/prg-bank-size)
        chr-size (* chr-banks rom/chr-bank-size)
        prg-at (+ 16 trainer-size)
        chr-at (+ prg-at prg-size)
        end (+ chr-at chr-size)
        part (fn [from to] (subvec bytes (min from (count bytes)) (min to (count bytes))))]
    (cond-> {:format :ines
             ;; for people; `->rom` goes by :header
             :about {:mapper (bit-or (bit-shift-right flags6 4) (bit-and flags7 0xF0))
                     :prg-banks prg-banks
                     :chr-banks chr-banks
                     :mirroring (cond (bit-test flags6 3) :four-screen
                                      (bit-test flags6 0) :vertical
                                      :else :horizontal)
                     :battery? (bit-test flags6 1)}
             :header (hex (part 0 16))
             :prg (hex (part prg-at chr-at))
             :chr (hex (part chr-at end))}
      (pos? trainer-size) (assoc :trainer (hex (part 16 prg-at)))
      ;; a few ROMs have more after the graphics
      (> (count bytes) end) (assoc :rest (hex (part end (count bytes)))))))

;; ---------------------------------------------------------------------------
;; A game built here: its source

(defn source
  "What a game's `build` returned, as a source map."
  [{:keys [name] {:keys [org symbols code cartridge chr]} :source :as built}]
  (when-not code
    (throw (ex-info "This needs what a game's build returns (from rom/nrom or runtime/program)" {})))
  {:format :gamedev-toolkit/source
   :version 1
   :name name
   :cartridge cartridge
   :org org
   :symbols (into (sorted-map-by (fn [a b] (compare [(symbols a) (str a)] [(symbols b) (str b)]))) symbols)
   :code (vec code)
   :tiles (mapv chr/rows (chr/bank-tiles chr))})

;; ---------------------------------------------------------------------------
;; Back to a .nes file

(defn ->rom
  "The .nes file (a vector of bytes) for a container or a source map."
  [m]
  (case (:format m)
    :ines (vec (mapcat #(unhex (get m % "")) [:header :trainer :prg :chr :rest]))
    :gamedev-toolkit/source
    (rom/ines (merge (:cartridge m)
                     {:prg (:bytes (asm/assemble (:code m) {:org (:org m) :symbols (:symbols m)}))
                      :chr (chr/bank (map chr/tile (:tiles m)))}))
    (throw (ex-info (str "Unknown :format " (pr-str (:format m)) ". There are :ines and :gamedev-toolkit/source") {}))))

;; ---------------------------------------------------------------------------
;; Reading and writing the text

(defn- lines
  "A collection printed one item to a line, between `open` and `close`."
  [open close indent items]
  (str open (str/join (str "\n" indent) (map pr-str items)) close))

(defn write
  "A container or source map as EDN text, laid out for reading: one
  instruction to a line, one row of pixels to a line."
  [m]
  (let [entry (fn [k]
                (when (contains? m k)
                  (str " " (pr-str k) " "
                       (case k
                         :code (lines "[" "]" "  " (:code m))
                         :symbols (lines "{" "}" "  " (map (fn [[n a]] (symbol (str (pr-str n) " " a))) (:symbols m)))
                         :tiles (str "[" (str/join "\n  " (map #(lines "[" "]" "   " %) (:tiles m))) "]")
                         (pr-str (get m k))))))
        order [:format :version :name :about :cartridge :header :trainer :org :symbols :code :tiles :prg :chr :rest]]
    (str "{" (subs (str/join "\n" (keep entry order)) 1) "}\n")))

(defn parse
  "The map in EDN text written by `write` (or by hand)."
  [text]
  (edn/read-string text))
