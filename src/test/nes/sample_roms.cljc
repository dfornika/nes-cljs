(ns nes.sample-roms
  "The sample games the site serves (resources/public/roms/cartridj),
  read from disk for the tests: they're our test cartridges, built with
  cartridj (see the README there)."
  (:require [nes.inspect.debug-info :as debug-info]
            #?(:clj [clojure.java.io :as io])))

(def ^:private dir "resources/public/roms/cartridj/")

#?(:cljs (def ^:private fs (js/require "fs")))

(defn- read-bytes [file]
  #?(:clj (with-open [in (io/input-stream (io/file file))] (.readAllBytes in))
     :cljs (js/Uint8Array. (.readFileSync fs file))))

#?(:cljs
   (defn rom
     "The .nes file for sample game `game-name` (\"hello\", \"ladder-pad\"...),
     as a Uint8Array."
     [game-name]
     (read-bytes (str dir game-name ".nes"))))

(defn rom-vec
  "The .nes file for sample game `game-name`, as a vector of numbers 0-255."
  [game-name]
  (mapv #(bit-and % 0xFF) (read-bytes (str dir game-name ".nes"))))

(defn names
  "Sample game `game-name`'s names file, read (see nes.inspect.debug-info)."
  [game-name]
  (debug-info/read-names (#?(:clj slurp :cljs #(.readFileSync fs % "utf8")) (str dir game-name ".debug.edn"))))
