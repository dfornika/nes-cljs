(ns nes.sha1-test
  (:require [cljs.test :refer [deftest is]]
            [nes.rom :as rom]
            [nes.sha1 :refer [sha1]]))

(defn- bytes-of [s] (js/Uint8Array. (map #(.charCodeAt % 0) s)))

(deftest known-hashes
  ;; test vectors from FIPS 180-1
  (is (= "da39a3ee5e6b4b0d3255bfef95601890afd80709" (sha1 (bytes-of ""))))
  (is (= "a9993e364706816aba3e25717850c26c9cd0d89d" (sha1 (bytes-of "abc"))))
  (is (= "84983e441c3bd26ebaae4aa1f95129e5e54670f1"
         (sha1 (bytes-of "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))))
  ;; a million 'a's: many blocks
  (is (= "34aa973cd4c4daa4f61eeb2bdbad27316534016f"
         (sha1 (.fill (js/Uint8Array. 1000000) 0x61)))))

(deftest rom-hash-skips-the-header
  (let [body (bytes-of "abc")
        rom (js/Uint8Array. 19)]
    (.set rom #js [0x4E 0x45 0x53 0x1A 1 1])
    (.set rom body 16)
    (is (= (sha1 body) (rom/rom-hash rom)))))
