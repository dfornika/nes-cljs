(ns gamedev-toolkit.sound
  "Sound effects.

  The NES's sound chip (the APU) has five channels. Two make square
  waves ('pulse': the bleeps), one a triangle wave (bass), one noise
  (hiss, for drums and explosions), and one plays samples. Each is
  controlled by four registers.

  A lot can be done by setting a channel going and leaving it to itself:
  the chip can fade a note out on its own (an 'envelope') and bend its
  pitch as it plays (a 'sweep'). So an effect here is one burst of four
  register writes, with no code needed on later frames:

    (def jump {:channel :pulse1 :hz 220 :duty 2 :decay 3
               :sweep {:period 2 :shift 3 :up? true}})

    (play jump)    ; a generator: the instructions that start it

  A new effect on a channel cuts off whatever that channel was playing.

  Music is a table of notes, played by gamedev-toolkit.runtime a note at a
  time (see `song`). It uses the triangle channel, which effects here
  don't, so the two never cut each other off.

  The runtime turns the channels on at reset; without it, write $0F to
  :snd-chn first.

  See https://www.nesdev.org/wiki/APU"
  (:require [gamedev-toolkit.hardware :as hw]))

(def ^:private cpu-hz 1789773)

(defn period
  "The pulse channels' timer value (0 to 2047) for a pitch of `hz`. The
  channel's pitch is the CPU's clock divided by 16 times (this + 1), so
  lower numbers are higher notes."
  [hz]
  (let [p (int (- (/ cpu-hz (* 16.0 hz)) 0.5))]
    (when-not (<= 8 p 0x7FF)
      (throw (ex-info (str hz " Hz is out of a pulse channel's range (about 55 Hz to 12 kHz)") {:hz hz})))
    p))

(defn- volume-byte
  "The low six bits of a channel's first register: fade out at rate
  `decay` (0 fastest to 15 slowest), or stay at `volume` (0 to 15)."
  [{:keys [decay volume]}]
  (if decay
    decay                                ; bit 4 clear: the envelope sets the volume
    (bit-or 0x10 (or volume 15))))       ; bit 4 set: constant volume

(def ^:private long-note
  "Length counter index 1: 254 ticks, about two seconds. Effects that
  fade end themselves; ones at a constant volume need a :length."
  1)

(defn registers
  "The four bytes an effect writes to its channel's registers.

  Pulse channels (:pulse1 :pulse2):
    :hz      the pitch
    :duty    0 to 3: how much of each wave is 'on' (12.5%, 25%, 50%, 75%),
             which changes the tone: 2 is a plain square, 0 is thin and reedy
    :decay   fade out, 0 (quickest, about a 16th of a second) to 15, or
    :volume  0 to 15, and stay there
    :length  how long, as an index into the chip's table of lengths
             (https://www.nesdev.org/wiki/APU_Length_Counter); default: long
    :sweep   bend the pitch: {:period 0-7 (how often), :shift 1-7 (how
             much: bigger is gentler), :up? true to rise}

  Noise (:noise):
    :pitch   0 (a high hiss) to 15 (a low rumble)
    :decay / :volume / :length as above"
  [{:keys [channel hz duty length sweep pitch] :as effect}]
  (let [length (bit-shift-left (or length long-note) 3)]
    (case channel
      (:pulse1 :pulse2)
      (let [p (period hz)
            {:keys [period shift up?]} sweep]
        [(bit-or (bit-shift-left (or duty 2) 6) (volume-byte effect))
         (if sweep
           (bit-or 0x80 (bit-shift-left (or period 0) 4) (if up? 0x08 0) (or shift 1))
           0)
         (bit-and p 0xFF)
         (bit-or length (bit-shift-right p 8))])
      :noise
      [(volume-byte effect)
       0
       (bit-and (or pitch 8) 0x0F)
       length]
      (throw (ex-info (str "Effects play on :pulse1, :pulse2 or :noise, not " (pr-str channel)) {})))))

(defn play
  "Start a sound effect (see `registers`). Uses A."
  [{:keys [channel] :as effect}]
  (let [base (hw/registers channel)]
    (map-indexed (fn [i value] [[:lda [:imm value]] [:sta (+ base i)]])
                 (registers effect))))

;; ---------------------------------------------------------------------------
;; Music

(def ^:private semitones
  {"c" 0 "cs" 1 "db" 1 "d" 2 "ds" 3 "eb" 3 "e" 4 "f" 5 "fs" 6 "gb" 6
   "g" 7 "gs" 8 "ab" 8 "a" 9 "as" 10 "bb" 10 "b" 11})

(defn note-hz
  "The pitch of a note named like :a4 (the A above middle C: 440 Hz), :c4
  (middle C), :fs3 (F sharp) or :bb2 (B flat), in equal temperament."
  [note]
  (let [[_ letter octave] (re-matches #"([a-g][sb]?)(\d)" (name note))
        n (some-> letter semitones)]
    (when-not n
      (throw (ex-info (str "Not a note: " (pr-str note) ". Notes look like :c4, :fs3, :bb2") {:note note})))
    ;; semitones away from A4, each a factor of the twelfth root of 2
    (* 440.0 (Math/pow 2.0 (/ (+ n (* 12 (- (parse-long octave) 4)) -9) 12.0)))))

(defn triangle-period
  "The triangle channel's timer value for a pitch of `hz`. Its pitch is
  the CPU's clock divided by 32 times (this + 1): an octave below a pulse
  channel with the same timer."
  [hz]
  (let [p (int (- (/ cpu-hz (* 32.0 hz)) 0.5))]
    (when-not (<= 2 p 0x7FF)
      (throw (ex-info (str hz " Hz is out of the triangle channel's range (about 27 Hz to 18 kHz)") {:hz hz})))
    p))

(defn song
  "A tune as the bytes the runtime's music player reads. `notes` is
  [[note frames] ...], where a note is like :c4 (see `note-hz`) or :rest,
  and frames is how long it lasts (1 to 255; 60 is a second). Three bytes
  a note: frames, then the timer's low and high bytes (a high byte of $FF
  means a rest). A 0 ends it, and the tune starts again."
  [notes]
  (when (empty? notes)
    (throw (ex-info "A song needs at least one note" {})))
  (conj (vec (mapcat (fn [[note frames]]
                       (when-not (and (int? frames) (<= 1 frames 255))
                         (throw (ex-info (str "A note lasts 1 to 255 frames, not " (pr-str frames)) {:note note})))
                       (if (= note :rest)
                         [frames 0 0xFF]
                         (let [p (triangle-period (note-hz note))]
                           [frames (bit-and p 0xFF) (bit-shift-right p 8)])))
                     notes))
        0))
