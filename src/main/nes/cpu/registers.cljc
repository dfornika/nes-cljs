(ns nes.cpu.registers
  "Where nes.cpu.core keeps the CPU's registers: six whole numbers in an
  array, one slot each (an Int32Array in ClojureScript, an int-array on
  the JVM), so the same code works on both.

    (reg s :a)          ; read A
    (set-reg! s :a v)   ; set A to v

  `reg` and `set-reg!` are macros: `(reg s :a)` becomes `(aget s 0)` when
  the code is compiled, so there's no function call left on the CPU's hot
  path. That's why the register has to be written out as a keyword, not a
  value worked out while running (use `index` for that).

  (Why macros, and not plain functions: we measured, on 2026-11-26 with
  `npm run bench:cpu`. Plain functions with `^:const` slot numbers, as in
  `(reg s A)`, ran our CPU about 4% slower inside jsnes; without
  `^:const`, about 5%. Probably because nes.cpu.core/step! is one very
  large function, and V8 only inlines so much into one function, so some
  of its 170-odd register reads stay real calls. Our whole console showed no
  difference: its PPU takes most of the time.)

  (Why an Int32Array, and not a plain JS array or a Float64Array: both of
  those made the CPU a few percent slower. Values in a Float64Array also
  come out as floating-point numbers, and once a float is written into a
  JS array, V8 keeps that array, and new ones made the same way, as
  floats from then on: jsnes's memory, for one.)"
  #?(:cljs (:require-macros [nes.cpu.registers])))

(def index
  "Each register's slot in the array. `p` is the status register:
  N V - B D I Z C, bit 7 first."
  {:a 0 :x 1 :y 2 :sp 3 :pc 4 :p 5})

(def names
  "The registers, in slot order."
  [:a :x :y :sp :pc :p])

#?(:clj
   (defn- slot [k]
     (or (index k)
         (throw (ex-info (str "Not a register: " (pr-str k) " (one of " names ")") {:register k})))))

#?(:clj
   (defmacro reg
     "The value of register `k` (a keyword, written out) in `s`."
     [s k]
     (if (:ns &env)
       `(aget ~s ~(slot k))
       (let [r (with-meta (gensym "regs") {:tag 'ints})]
         `(let [~r ~s] (aget ~r ~(slot k)))))))

#?(:clj
   (defmacro set-reg!
     "Set register `k` (a keyword, written out) in `s` to `v`. Returns `v`."
     [s k v]
     (if (:ns &env)
       `(aset ~s ~(slot k) ~v)
       (let [r (with-meta (gensym "regs") {:tag 'ints})]
         `(let [~r ~s] (aset ~r ~(slot k) (int ~v)))))))
