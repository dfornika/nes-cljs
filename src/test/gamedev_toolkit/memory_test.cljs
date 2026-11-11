(ns gamedev-toolkit.memory-test
  (:require [cljs.test :refer [deftest is testing]]
            [gamedev-toolkit.memory :as memory]
            [gamedev-toolkit.rom :as rom]))

(defn- error-of [decls & [regions]]
  (try (if regions (memory/allocate decls regions) (memory/allocate decls)) nil
       (catch :default e (ex-message e))))

(deftest places-variables
  (let [m (memory/allocate
           [[:frame :u8]
            [:score :u16]
            [:state [:enum {0 :title 1 :playing}]]
            [:player {:x :u8 :y :u16 :hp :u8}]
            [:enemies [:soa 8 {:x :u8 :y :u8}]]
            [:buffer [:array 32 :u8]]
            [:oam [:array 256 :u8] {:at 0x0200 :hide true :doc "sprites"}]])]
    (testing "single values and structs go in the zero page, in order"
      (is (= {:frame 0 :score 1 :state 3 :player 4 :player/x 4 :player/y 5 :player/hp 7}
             (select-keys (:symbols m) [:frame :score :state :player :player/x :player/y :player/hp]))))
    (testing "arrays go in RAM from $0300; a structure of arrays is a table per field"
      (is (= {:enemies 0x300 :enemies/x 0x300 :enemies/y 0x308 :buffer 0x310 :oam 0x200}
             (select-keys (:symbols m) [:enemies :enemies/x :enemies/y :buffer :oam]))))
    (is (= [:scalar :scalar :scalar :struct :soa :array :array] (map :kind (:variables m))))
    (is (= [1 2 1 4 16 32 256] (map :size (:variables m))))
    (is (= {:name :oam :addr 0x200 :hide true :doc "sprites"}
           (select-keys (last (:variables m)) [:name :addr :hide :doc])))
    (is (= {:zero-page (- 256 8) :ram (- 0x500 48)} (:free m)))))

(deftest placement-options
  (testing ":in overrides where a kind of variable usually goes"
    (let [m (memory/allocate [[:big :u8 {:in :ram}] [:ptrs [:array 4 :u8] {:in :zero-page}]])]
      (is (= {:big 0x300 :ptrs 0} (:symbols m)))))
  (testing "variables fit around ones with a fixed address"
    (let [m (memory/allocate [[:a1 :u8] [:pinned :u16 {:at 1}] [:b1 :u16] [:c1 :u8]])]
      (is (= {:a1 0 :pinned 1 :b1 3 :c1 5} (:symbols m)))))
  (testing "a smaller zero page; what doesn't fit spills into RAM"
    (let [m (memory/allocate [[:a1 :u8] [:b1 :u8] [:c1 :u16]] {:zero-page [0x10 0x11]})]
      (is (= {:a1 0x10 :b1 0x11 :c1 0x300} (:symbols m)))))
  (testing "more than 8 fields: pairs keep their order"
    (let [fields (mapv #(vector (keyword (str "f" %)) :u8) (range 12))
          m (memory/allocate [[:thing fields]])]
      (is (= 11 (:thing/f11 (:symbols m)))))))

(deftest errors
  (is (re-find #"Unknown type :u32" (error-of [[:n :u32]])))
  (is (re-find #"declared twice" (error-of [[:n :u8] [:n :u8]])))
  (is (re-find #"plain keyword" (error-of [[:player/x :u8]])))
  (is (re-find #"No room left" (error-of [[:big [:array 2000 :u8]]])))
  (is (re-find #"No room left" (error-of [[:n :u8 {:in :zero-page}]] {:zero-page [0 -1]})))
  (is (re-find #"overlap" (error-of [[:a1 :u16 {:at 0x10}] [:b1 :u8 {:at 0x11}]])))
  (is (re-find #"must be one byte" (error-of [[:es [:soa 4 {:x :u16}]]])))
  (is (re-find #":at must be" (error-of [[:n :u8 {:at 0x800}]])))
  (is (re-find #"in \[:n :u32\]" (error-of [[:n :u32]])) "the message shows the declaration"))

(deftest used-by-the-rom-builder
  (let [m (memory/allocate [[:count :u8]])
        built (rom/nrom {:code [[:label :reset] [:inc :count] [:jmp :reset]]
                         :vectors {:nmi :reset :reset :reset :irq :reset}
                         :memory m})]
    (is (= [0xE6 0x00] (subvec (:prg built) 0 2)) "INC count, in its zero page form")
    (is (= m (:memory built)))
    (is (thrown-with-msg? js/Error #"both a variable and one of the :symbols"
                          (rom/nrom {:code [[:label :reset]] :memory m :symbols {:count 5}
                                     :vectors {:nmi :reset :reset :reset :irq :reset}})))))
