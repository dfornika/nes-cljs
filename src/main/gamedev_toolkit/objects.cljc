(ns gamedev-toolkit.objects
  "A pool of objects: the things in a game that come and go. Enemies,
  bullets, coins, puffs of smoke.

  There's no asking for memory as a game runs: every byte is spoken for
  when the ROM is built. So a game decides the most objects it will ever
  have at once, sets aside that many *slots*, and reuses them. An object
  appearing ('spawning') takes a free slot; one disappearing frees its
  slot. Nothing is ever moved or tidied up.

  The slots are a structure of arrays (see gamedev-toolkit.memory): one
  table per field, so with a slot's number in the X register, its x is
  at `objects/x,X` and its type at `objects/type,X`: one instruction each.
  A type of 0 means the slot is free.

    (def things (pool :thing 8 [:coin :bat] {:x :u8 :y :u8 :dir :u8}))

    (:variable things)                    ; goes in the game's variables
    (spawn things :coin {:x [:imm 40] :y [:imm 96]})
    (each things                          ; for every one that exists:
      (by-type things                     ;   X is its slot
        {:coin [...]
         :bat  [(gen/add (field things :x) [:imm 1]) ...]}))
    (remove-it things)                    ; inside `each`: this one's gone

  Needs gamedev-toolkit.runtime (for :rt/slot and :rt/temp)."
  (:require [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.gen :as gen]))

(defn pool
  "A pool called `name` (a keyword) with room for `size` objects, of the
  types in `types` (keywords; they're numbered from 1), each with the
  fields in `fields` ({name type}, one byte each) besides its :type."
  [name size types fields]
  (when-not (<= 1 size 255)
    (throw (ex-info "A pool has 1 to 255 slots" {:size size})))
  {:name name
   :size size
   :types (zipmap types (iterate inc 1))
   :fields (into [:type] (map first fields))
   :variable [name [:soa size (into [[:type :u8]] (map vec fields))]]})

(defn- table
  "The table for field `k`: :thing/x"
  [pool k]
  (when-not (some #{k} (:fields pool))
    (throw (ex-info (str "The pool " (:name pool) " has no field " k ". It has: " (:fields pool)) {})))
  (keyword (name (:name pool)) (name k)))

(defn field
  "Field `k` of the object whose slot is in X, as an operand for the
  one-byte generators and gen/with-operand."
  [pool k]
  (gen/indexed (table pool k) :x))

(defn type-id
  "The number that stands for type `t` in the pool's :type field."
  [pool t]
  (or (get (:types pool) t)
      (throw (ex-info (str "The pool " (:name pool) " has no type " t ". It has: " (keys (:types pool))) {}))))

(defn clear
  "Free every slot. Uses A and X."
  [pool]
  (gen/fill (table pool :type) (:size pool) [:imm 0]))

(defn find-free
  "Put the number of a free slot in X, with the carry flag clear; if
  they're all taken, the carry flag is set. Uses A and X."
  [pool]
  (let [look (asm/genlabel "look")
        found (asm/genlabel "found")]
    [[:clc]
     [:ldx [:imm 0]]
     [:label look]
     [:lda (table pool :type) :x]
     [:beq found]
     [:inx]
     [:cpx [:imm (:size pool)]]          ; (leaves carry clear while X is less)
     [:bne look]
     [:label found]]))

(defn spawn
  "Make a new object of type `t`, with `values` ({field src-operand}) in
  its fields; fields not given are 0. If there's no free slot, nothing
  happens: the game carries on without it, which is what NES games do
  too. Afterwards X is the new object's slot (if the carry flag is
  clear). Uses A and X."
  [pool t values]
  [(find-free pool)
   (gen/when-flag :no-carry
     (for [k (rest (:fields pool))]
       [(gen/with-operand :lda (get values k [:imm 0]))
        [:sta (table pool k) :x]])
     [:lda [:imm (type-id pool t)]]
     [:sta (table pool :type) :x])])

(defn remove-it
  "Free the slot in X: the object is gone. Uses A."
  [pool]
  [[:lda [:imm 0]]
   [:sta (table pool :type) :x]])

(defn slot
  "Inside `each`: put the current object's slot back in X, after code
  that used X for something else."
  []
  [[:ldx :rt/slot]])

(defn each
  "Run `body` for every object that exists, with its slot in X. The body
  may change X (and A and Y) freely: call (slot) to get it back."
  [pool & body]
  [[:ldx [:imm 0]]
   (gen/do-while :not-equal
     [:stx :rt/slot]
     [:lda (table pool :type) :x]
     (gen/when-flag :not-zero body)
     [:ldx :rt/slot]
     [:inx]
     [:cpx [:imm (:size pool)]])])

(defn each-rotating
  "Like `each`, but starting from a different object each frame, and going
  round. For drawing: the PPU shows only 8 sprites on a line, and drops
  the ones that came last in its list. Drawing the objects in a different
  order each frame makes them take turns, so crowded objects flicker
  instead of some vanishing. (Uses :rt/turn, which this moves on.)"
  [pool & body]
  (let [done (asm/genlabel "rotated")]
    [;; start one further along than last frame
     [:inc :rt/turn]
     [:lda :rt/turn]
     [:cmp [:imm (:size pool)]]
     (gen/when-flag :carry (gen/move :rt/turn [:imm 0]))
     [:ldx :rt/turn]
     (gen/forever
       [:stx :rt/slot]
       [:lda (table pool :type) :x]
       (gen/when-flag :not-zero body)
       [:ldx :rt/slot]
       [:inx]
       [:cpx [:imm (:size pool)]]
       (gen/when-flag :equal [:ldx [:imm 0]])
       [:cpx :rt/turn]
       [:beq done])
     [:label done]]))

(defn by-type
  "Inside `each`: run the code for the current object's type. `cases` is
  {type forms}; types left out do nothing."
  [pool cases]
  (let [done (asm/genlabel "typed")]
    [(for [[t forms] cases]
       [[:lda (table pool :type) :x]
        [:cmp [:imm (type-id pool t)]]
        (gen/when-flag :equal
          forms
          [:jmp done])])
     [:label done]]))

;; ---------------------------------------------------------------------------
;; A level's worth of objects, from a table in the ROM

(defn placements
  "The bytes of a table of objects to spawn: `objects` is [[type x y]
  ...]. Three bytes each (type number, x, y), then a 0."
  [pool objects]
  (conj (vec (mapcat (fn [[t x y]] [(type-id pool t) x y]) objects)) 0))

(defn spawn-all
  "Spawn every object in the table at `label` (see `placements`: at most
  85 of them), setting its :type, :x and :y; other fields are left as
  they were, so `clear` the pool first. Uses A, X and Y."
  [pool label]
  (let [done (asm/genlabel "spawned")]
    [[:ldy [:imm 0]]
     (gen/forever
       [:lda label :y]
       [:beq done]                       ; the 0 at the end
       [:sta :rt/temp]
       (find-free pool)
       [:bcs done]                       ; no room for more
       [:lda :rt/temp] [:sta (table pool :type) :x]
       [:iny]
       [:lda label :y] [:sta (table pool :x) :x]
       [:iny]
       [:lda label :y] [:sta (table pool :y) :x]
       [:iny])
     [:label done]]))

(defn in-drawing
  "Find the objects in a level drawn as text (as for
  gamedev-toolkit.screen: rows of characters, one per 16x16 block).
  `legend` is {character type}. Returns [[type x y] ...] in pixels, for
  `placements`."
  [rows legend]
  (vec (for [[row line] (map-indexed vector rows)
             [col ch] (map-indexed vector line)
             :when (contains? legend ch)]
         [(legend ch) (* 16 col) (* 16 row)])))
